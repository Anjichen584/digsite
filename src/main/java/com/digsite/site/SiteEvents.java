package com.digsite.site;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 挖掘事件池（H2）。
 * 触发点：{@link SiteService} 每层完成 roll 掉落处（completeLayer 的 rollDrop 分支）回调
 * {@link #onLayerComplete}，按 sites.yml 全局 {@code events:} 段的概率 roll <b>一次</b>事件
 * —— 不叠加、可无事件；roll 顺序 = yml 声明顺序，首个命中即触发并返回。
 *
 * <p>四种事件（概率全部来自 sites.yml，代码只留缺省兜底值）：
 * <ul>
 *   <li><b>bonus-fragment 出土惊喜</b>：按遗址档位再 roll 一件文物的随机缺位碎片，走 extraDrop 双倍掉落；</li>
 *   <li><b>sand-fall 落沙惊魂</b>：玩家头顶 2 格沙粒粒子 + BLOCK_GRAVEL_BREAK 音效 + 掉 1 个 SAND（氛围小奖）；</li>
 *   <li><b>rust-fog 铜锈迷雾</b>：玩家 BLINDNESS 5 秒（原版药水效果 addPotionEffect）；</li>
 *   <li><b>lucky-strata 时来运转</b>：内存标记本格 cellKey，下一次（下一层）刷挖的 hits-per-layer 减半，
 *       由 SiteService.handleBrush 调 {@link #consumeLucky} 消费，一次生效。</li>
 * </ul>
 * 每次触发都给当事玩家一条 Msg 私聊提示（事件名 + 效果）。全部在 Bukkit 主线程执行。
 */
final class SiteEvents {

    // 事件 id 常量（与 sites.yml events 段的 key 一一对应）
    static final String EV_BONUS_FRAGMENT = "bonus-fragment";
    static final String EV_SAND_FALL = "sand-fall";
    static final String EV_RUST_FOG = "rust-fog";
    static final String EV_LUCKY_STRATA = "lucky-strata";

    /** yml 缺段/缺项时的缺省概率兜底（正式数值以 sites.yml 为准）。 */
    private static final Map<String, Double> DEFAULT_PROBS = Map.of(
            EV_BONUS_FRAGMENT, 0.08,
            EV_SAND_FALL, 0.10,
            EV_RUST_FOG, 0.06,
            EV_LUCKY_STRATA, 0.05);

    private final DigSitePlugin plugin;
    /** 事件 id → 概率（sites.yml events 段原序，由 SiteService.loadTiers 同源加载注入）。 */
    private final Map<String, Double> probs;
    /** lucky-strata 待生效标记：cellKey（siteId;dx;dz）→ TRUE，消费即移除。 */
    private final Map<String, Boolean> luckyCells = new ConcurrentHashMap<>();

    /**
     * @param probs sites.yml events 段概率表（SiteService.loadTiers 同源加载）；空表时用缺省兜底
     */
    SiteEvents(DigSitePlugin plugin, Map<String, Double> probs) {
        this.plugin = plugin;
        if (probs == null || probs.isEmpty()) {
            this.probs = new LinkedHashMap<>(DEFAULT_PROBS);
        } else {
            this.probs = new LinkedHashMap<>(probs);
        }
    }

    /**
     * 层完成回调（SiteService.completeLayer 的 rollDrop 分支末尾调用）。
     * @param p         触发本层完成的玩家
     * @param site      所属遗址
     * @param cell      本格（更新前的旧记录，layer = 本层原剩余层数）
     * @param extraDrop 额外掉落通道：accept(ItemStack) 由 SiteService 落地到层顶位置（不含计数）
     */
    void onLayerComplete(Player p, Model.SiteInfo site, Model.SiteCell cell, Consumer<ItemStack> extraDrop) {
        Random rnd = plugin.random();
        for (Map.Entry<String, Double> e : probs.entrySet()) {
            double chance = e.getValue();
            if (chance <= 0 || rnd.nextDouble() >= chance) continue; // 未命中，继续看下一个事件
            switch (e.getKey()) {
                case EV_BONUS_FRAGMENT -> bonusFragment(p, site, cell, extraDrop, rnd);
                case EV_SAND_FALL -> sandFall(p, extraDrop);
                case EV_RUST_FOG -> rustFog(p);
                case EV_LUCKY_STRATA -> luckyStrata(p, site, cell);
                default -> { return; } // 未知事件 id：当成本轮无事件处理，避免误 roll 后续
            }
            return; // 单事件池：命中即结束，不叠加
        }
    }

    /**
     * lucky-strata 消费口（SiteService.handleBrush 计算 hits 阈值时调用）。
     * @return true = 该格命中过「时来运转」，本次调用已消费标记，本层阈值应减半
     */
    boolean consumeLucky(String cellKey) {
        return Boolean.TRUE.equals(luckyCells.remove(cellKey));
    }

    /** 遗址关场/结算时清理该遗址名下的 lucky 标记（SiteService.cleanupCellKeys 调用）。 */
    void clearSite(String siteId) {
        String prefix = siteId + ";";
        luckyCells.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ------------------------------------------------------------ 事件实现

    /** 出土惊喜：按档位再 roll 一件文物的随机缺位碎片，成色算法与 rollDrop 一致。 */
    private void bonusFragment(Player p, Model.SiteInfo site, Model.SiteCell cell,
                               Consumer<ItemStack> extraDrop, Random rnd) {
        Model.ArtifactDef def = plugin.catalog().roll(rnd, site.tier());
        if (def == null || def.pieces() <= 0) return;
        int piece = rnd.nextInt(def.pieces());
        int fragIntegrity = Math.max(10, Math.min(100, cell.integrity() + rnd.nextInt(21)));
        ItemStack frag = plugin.codec().fragment(
                new Model.FragmentData(def.id(), piece, fragIntegrity, site.id()), def.name(), def.pieces());
        extraDrop.accept(frag);
        plugin.playerStore().bumpFragments(p, 1); // 碎片计数与 rollDrop 口径一致
        Msg.send(p, "<gold>出土惊喜</gold> <gray>土层里又滚出一片</gray> <white>" + def.name()
                + "</white><gray> 的残片（本次掉落双倍）！</gray>");
    }

    /** 落沙惊魂：头顶 2 格沙粒粒子 + 音效 + 掉 1 个沙子（走 extraDrop，不计碎片数）。 */
    private void sandFall(Player p, Consumer<ItemStack> extraDrop) {
        World w = p.getWorld();
        Location head = p.getLocation().add(0, 2, 0);
        w.spawnParticle(Particle.BLOCK, head, 40, 0.3, 0.3, 0.3, 0.1, Material.SAND.createBlockData());
        w.playSound(p.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.8f, 1.1f);
        extraDrop.accept(new ItemStack(Material.SAND, 1));
        Msg.send(p, "<yellow>落沙惊魂</yellow> <gray>头顶沙粒簌簌而落，脚边多了一捧</gray> <white>沙子</white><gray>。</gray>");
    }

    /** 铜锈迷雾：BLINDNESS 5 秒（原版药水效果，ambient 无害化、粒子隐藏但图标可见）。 */
    private void rustFog(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 5 * 20, 0, false, false, true));
        Msg.send(p, "<dark_aqua>铜锈迷雾</dark_aqua> <gray>一层铜绿色的锈雾蒙住了你的双眼（5 秒）。</gray>");
    }

    /** 时来运转：标记本格 cellKey，下一层刷挖次数减半（已挖穿的格子不标，避免死标记）。 */
    private void luckyStrata(Player p, Model.SiteInfo site, Model.SiteCell cell) {
        if (cell.layer() <= 1) return; // 本层已是最后一层，无「下一层」可言
        luckyCells.put(site.id() + ";" + cell.dx() + ";" + cell.dz(), Boolean.TRUE);
        Msg.send(p, "<green>时来运转</green> <gray>这格土层意外松软，下一层发掘次数减半！</gray>");
    }
}
