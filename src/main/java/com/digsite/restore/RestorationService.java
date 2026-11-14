package com.digsite.restore;

import com.digsite.DigSiteConfig;
import com.digsite.DigSitePlugin;
import com.digsite.catalog.ArtifactCatalog;
import com.digsite.codex.CodexService;
import com.digsite.model.Model;
import com.digsite.store.PlayerStore;
import com.digsite.util.Announcer;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修复服务（C 部分）：背包碎片聚合 → RestoreMenu 选组 → 拼图 → 产出成品。
 *
 * <p>对 PlayerStore（代理A 实现）调用的 API：
 * <pre>
 *   void bumpRestored(java.util.UUID uuid, String name, int n)   // artifactsRestored +n（无记录则建档）
 * </pre>
 *
 * <p><b>修复规则最终口径</b>（成色阈值/奖励一律从 config 读取，不写死数字）：
 * <ol>
 *   <li>必须集齐同一 {@link Model.ArtifactDef} 的全部 pieces 片（缺一片即不可修）；</li>
 *   <li>repairDegree = 各片 integrity 均值（四舍五入）；</li>
 *   <li>满配奖励：当<b>所有片</b> integrity ≥ {@code restore.broken-degree} 时，额外 +{@code restore.full-set-bonus}
 *       （阈值从 config 读；任意一片低于阈值则无奖励）；</li>
 *   <li>结果 clamp 到 [{@code restore.min-integrity}, 100]；</li>
 *   <li>成色档位：degree &lt; broken-degree → 残缺 / &lt; perfect-degree → 完整 / ≥ perfect-degree → 无损；</li>
 *   <li>修复消耗全部被指派的碎片物品（按 PDC 数据 exact 匹配移除各 1 件），产出
 *       {@code ItemCodec.artifact} 成品入背包（背包满则掉在玩家脚下）；</li>
 *   <li>玩家统计 bumpRestored + 图鉴回调 onArtifactRestored；无损 → 全服紧急公告。</li>
 * </ol>
 */
public final class RestorationService {

    private final DigSitePlugin plugin;
    private final ItemCodec codec;
    private final ArtifactCatalog catalog;
    private final PlayerStore playerStore;
    private final CodexService codexService;
    private final DigSiteConfig config;
    private final RestoreMenu menu;

    public RestorationService(DigSitePlugin plugin, ItemCodec codec, ArtifactCatalog catalog,
                              PlayerStore playerStore, CodexService codexService, DigSiteConfig config) {
        this.plugin = plugin;
        this.codec = codec;
        this.catalog = catalog;
        this.playerStore = playerStore;
        this.codexService = codexService;
        this.config = config;
        // 拼图菜单自注册监听器（契约要求：RestorationService 构造器里注册）
        this.menu = new RestoreMenu(plugin, this);
    }

    // ---------- 入口 ----------

    /** /digs restore：扫背包聚合碎片，无碎片提示；有则打开组选择页。 */
    public void open(Player p) {
        Map<Integer, Model.FragmentData> scan = scanFragments(p);
        if (scan.isEmpty()) {
            Msg.send(p, "<gray>背包里没有任何文物残片。去遗址挖一铲子吧（手持刷子右键可疑方块）。</gray>");
            return;
        }
        menu.openSelect(p);
    }

    // ---------- 背包扫描（RestoreMenu 复用） ----------

    /** 扫玩家背包全部碎片：背包槽位 → FragmentData（PDC exact）。 */
    public Map<Integer, Model.FragmentData> scanFragments(Player p) {
        Map<Integer, Model.FragmentData> out = new LinkedHashMap<>();
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            Model.FragmentData fd = codec.readFragment(storage[i]);
            if (fd != null) out.put(i, fd);
        }
        return out;
    }

    /** 聚合：artifactId →（piece → 该片最佳 integrity）。供选择页概览。 */
    public Map<String, Map<Integer, Integer>> aggregate(Map<Integer, Model.FragmentData> scan) {
        Map<String, Map<Integer, Integer>> out = new LinkedHashMap<>();
        for (Model.FragmentData fd : scan.values()) {
            Map<Integer, Integer> perPiece = out.computeIfAbsent(fd.artifactId(), k -> new HashMap<>());
            perPiece.merge(fd.piece(), fd.integrity(), Math::max);
        }
        return out;
    }

    // ---------- 修复执行（RestoreMenu 全片就位后调用） ----------

    /**
     * 按 menu 的临时指派执行修复。指派是 piece → FragmentData 的纯内存映射：
     * 碎片始终留在玩家背包，本方法按指派数据 exact 匹配背包内物品再移除。
     *
     * @param assigned piece → 指派碎片数据（来自拼图页内存 Map）
     */
    public void performRestore(Player p, String artifactId, Map<Integer, Model.FragmentData> assigned) {
        Model.ArtifactDef def = catalog.byId(artifactId);
        if (def == null) {
            Msg.send(p, "<red>未知文物，修复中止。</red>");
            return;
        }
        if (assigned.size() < def.pieces()) {
            Msg.send(p, "<red>碎片尚未集齐（" + assigned.size() + "/" + def.pieces() + "），无法修复。</red>");
            return;
        }

        // 1) 按指派在背包中 exact 匹配并移除各 1 件（piece 不同则数据必然不同，槽位不冲突）
        Map<Integer, Model.FragmentData> scan = scanFragments(p);
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        for (Map.Entry<Integer, Model.FragmentData> e : assigned.entrySet()) {
            Model.FragmentData want = e.getValue();
            Integer slot = null;
            for (Map.Entry<Integer, Model.FragmentData> s : scan.entrySet()) {
                if (consumed.contains(s.getKey())) continue;
                if (s.getValue().equals(want)) { slot = s.getKey(); break; }
            }
            if (slot == null) {
                // 玩家在菜单打开期间弄丢了碎片：指派失效，不消耗任何东西
                Msg.send(p, "<red>有碎片已经不在背包里了，指派失效。请重新打开修复台。</red>");
                return;
            }
            consumed.add(slot);
        }
        // 到这里全部匹配成功，真正移除
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (int slot : consumed) {
            ItemStack it = storage[slot];
            if (it.getAmount() <= 1) {
                p.getInventory().setItem(slot, null);
            } else {
                it.setAmount(it.getAmount() - 1);
            }
        }

        // 2) 计算修复度：各片 integrity 均值（四舍五入）+ 满配奖励（所有片 ≥ broken-degree 时 +full-set-bonus），clamp [min-integrity, 100]
        int sum = 0;
        boolean fullBonus = true;
        for (Model.FragmentData fd : assigned.values()) {
            sum += fd.integrity();
            if (fd.integrity() < config.brokenDegree()) fullBonus = false;
        }
        int degree = (int) Math.round(sum / (double) def.pieces());
        if (fullBonus) degree += config.fullSetBonus();
        degree = Math.max(config.minIntegrity(), Math.min(100, degree));

        // 3) 产出成品入背包（满则掉地上）
        ItemStack product = codec.artifact(
                new Model.ArtifactData(def.id(), degree, p.getName(), System.currentTimeMillis()), def);
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(product);
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }

        // 4) 统计 + 图鉴回调
        playerStore.bumpRestored(p.getUniqueId(), p.getName(), 1);
        codexService.onArtifactRestored(p, def, degree);

        // 5) 玩家提示 + 无损全服公告
        Msg.send(p, "<green>修复完成！</green>" + ItemCodec.qualityLine(degree)
                + "<gray> ——「</gray><gold>" + def.name() + "</gold><gray>」已放入背包。</gray>");
        if (degree >= config.perfectDegree()) {
            announcer().announceUrgent("<light_purple>✦ 无损修复</light_purple><gray>：</gray><white>"
                    + p.getName() + "</white><gray> 以完美手法修复出了无损「</gray><gold>" + def.name()
                    + "</gold><gray>」！</gray>");
        }
    }

    /** 拼图菜单（供 GUI 访问 def 等）。 */
    RestoreMenu menu() {
        return menu;
    }

    DigSitePlugin plugin() {
        return plugin;
    }

    private Announcer announcer() {
        return plugin.announcer();
    }

    /** 供菜单做材质兜底。 */
    static Material displayMaterial(Model.ArtifactDef def) {
        Material mat = Material.matchMaterial(def.displayMaterial());
        return mat == null ? Material.BRICK : mat;
    }
}
