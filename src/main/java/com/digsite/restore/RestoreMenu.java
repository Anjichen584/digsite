package com.digsite.restore;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 修复拼图菜单（54 格两页式，InventoryHolder + 自注册 Listener）。
 *
 * <p><b>核心设计：拼图页不真实移动物品。</b>碎片始终留在玩家背包，菜单里只是引用展示：
 * <ul>
 *   <li>点击碎片按钮 → 只在内存 Map（piece → FragmentData）里做「临时指派」；</li>
 *   <li>顶部 piece 槽位只是可视化（显示已指派碎片的数据）；再点已就位的槽位 = 换回（撤销指派）；</li>
 *   <li>关闭菜单 → 丢弃指派 Map，碎片原封不动，绝不丢件；</li>
 *   <li>点击「修复」且全片就位 → 由 RestorationService.performRestore 按指派从背包 exact 匹配移除。</li>
 * </ul>
 *
 * <p>布局：
 * <pre>
 *   组选择页：槽位 0..44 = 每件「凑齐 ≥1 片」的文物一个按钮
 *   拼图页　：槽位 0..8   = piece 槽位（对应 piece 索引，上限 9 片）
 *            槽位 9..35  = 玩家该文物的碎片按钮（按 piece/成色/出土地聚合，最多 27 组）
 *            槽位 45/49/53 = 返回 / 修复 / 信息
 * </pre>
 */
public final class RestoreMenu implements InventoryHolder, Listener {

    private final DigSitePlugin plugin;
    private final RestorationService service;

    /** 每个玩家的拼图会话（artifactId + 临时指派）。 */
    private static final class Session {
        String artifactId;
        final Map<Integer, Model.FragmentData> assigned = new HashMap<>();
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    /** 最近一次创建的界面（InventoryHolder 约定：本类单例充当所有修复界面的 holder）。 */
    private Inventory view;

    public RestoreMenu(DigSitePlugin plugin, RestorationService service) {
        this.plugin = plugin;
        this.service = service;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Inventory getInventory() {
        return view;
    }

    // ---------- 页面构建 ----------

    /** 组选择页：每件凑齐 ≥1 片的文物一个按钮。 */
    void openSelect(Player p) {
        Map<Integer, Model.FragmentData> scan = service.scanFragments(p);
        Map<String, Map<Integer, Integer>> agg = service.aggregate(scan);

        Inventory inv = Bukkit.createInventory(this, 54, Msg.mm("<dark_green>修复台 · 选择要修复的文物</dark_green>"));
        view = inv;

        List<String> ids = new ArrayList<>();
        int slot = 0;
        for (Map.Entry<String, Map<Integer, Integer>> e : agg.entrySet()) {
            if (slot >= 45) break; // 最多 45 件一组（超过时提示先修手里的）
            Model.ArtifactDef def = plugin.catalog().byId(e.getKey());
            if (def == null) continue; // 目录里没有的碎片（artifacts.yml 被改过）不展示
            ids.add(def.id());

            ItemStack btn = new ItemStack(RestorationService.displayMaterial(def));
            var meta = btn.getItemMeta();
            String r = rarityTag(def);
            meta.displayName(Msg.mm("<gold>" + def.name() + "</gold> <gray>(</gray>"
                    + com.digsite.codex.CodexService.rarityColor(r) + r + "<gray>)</gray>"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            int owned = e.getValue().size();
            String color = owned >= def.pieces() ? "<green>" : "<yellow>";
            lore.add(Msg.mm("<gray>已有 </gray>" + color + owned + "</color><gray>/</gray><white>"
                    + def.pieces() + "</white><gray> 片</gray>"
                    + (owned >= def.pieces() ? " <green>✔ 可修复</green>" : " <gray>（未集齐）</gray>")));
            // 各片成色概览（该片拥有的最高成色）
            for (int piece = 0; piece < def.pieces(); piece++) {
                Integer best = e.getValue().get(piece);
                if (best == null) {
                    lore.add(Msg.mm("<dark_gray>第 " + (piece + 1) + " 片：缺失</dark_gray>"));
                } else {
                    lore.add(Msg.mm("<gray>第 " + (piece + 1) + " 片：最佳</gray> " + ItemCodec.qualityLine(best)));
                }
            }
            lore.add(Msg.mm("<dark_gray>点击进入拼图页</dark_gray>"));
            meta.lore(lore);
            btn.setItemMeta(meta);
            inv.setItem(slot++, btn);
        }
        if (agg.size() > ids.size()) {
            inv.setItem(53, infoItem("<yellow>文物种类过多，仅显示前 45 件</yellow>"));
        }
        p.openInventory(inv);
        // 记录选择页按钮对应的 defId（借助会话容器）
        sessions.computeIfAbsent(p.getUniqueId(), k -> new Session()).artifactId = null;
        selectIds.put(p.getUniqueId(), ids);
    }

    /** 选择页槽位 → defId 的临时映射（随每次 openSelect 刷新）。 */
    private final Map<UUID, List<String>> selectIds = new HashMap<>();

    /** 拼图页：顶部 piece 槽位 + 中部碎片按钮 + 底部操作。 */
    void openAssemble(Player p, String artifactId) {
        Model.ArtifactDef def = plugin.catalog().byId(artifactId);
        if (def == null) {
            Msg.send(p, "<red>未知文物，无法拼图。</red>");
            return;
        }
        // 重开窗口时保留同文物的既有指派（整窗重开是唯一可靠的客户端同步方式）
        java.util.Map<Integer, Model.FragmentData> keep = new HashMap<>();
        Session pre = sessions.get(p.getUniqueId());
        if (pre != null && artifactId.equals(pre.artifactId)) keep.putAll(pre.assigned);
        Inventory inv = Bukkit.createInventory(this, 54,
                Msg.mm("<dark_green>修复台 · " + def.name() + " <gray>(" + keep.size() + "/"
                        + def.pieces() + " 片就位)</gray></dark_green>"));
        view = inv;

        // 顶部 piece 槽位占位（随后 renderAssemble 会填充）
        int slotCount = Math.min(def.pieces(), 9);
        for (int i = 0; i < slotCount; i++) inv.setItem(i, emptyPieceSlot(i));

        // 中部碎片按钮：按 (piece, integrity, siteId) 聚合展示
        Map<Integer, Model.FragmentData> scan = service.scanFragments(p);
        Map<String, Model.FragmentData> groups = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Model.FragmentData fd : scan.values()) {
            if (!fd.artifactId().equals(artifactId)) continue;
            String key = fd.piece() + "/" + fd.integrity() + "/" + fd.siteId();
            groups.putIfAbsent(key, fd);
            counts.merge(key, 1, Integer::sum);
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        assembleKeys.put(p.getUniqueId(), keys); // 顺序先存，槽位渲染再查
        int slot = 9;
        for (String key : keys) {
            if (slot > 35) break; // 最多 27 组
            inv.setItem(slot++, fragmentButton(groups.get(key), counts.get(key), def));
        }
        if (keys.size() > 27) {
            inv.setItem(53, infoItem("<yellow>碎片种类过多，仅显示前 27 组</yellow>"));
        }

        // 底部：45 返回 / 49 修复 / 53 信息（占位，渲染时更新）
        inv.setItem(45, navItem(Material.ARROW, "<yellow>◀ 返回选择</yellow>"));
        inv.setItem(49, repairButton(def, new Session())); // 未指派态占位，开窗后 renderAssemble 会重设
        inv.setItem(53, infoItem("<gray>点碎片 → 指派到对应 piece 槽位；点已就位槽位 = 换回</gray>"));

        // 先开窗：openInventory 触发上一界面的 InventoryCloseEvent 清掉旧会话，
        // 会话/拼图 keys 必须在开窗之后再建，否则拼图页点击全部失效（真机 e2e 实测教训）
        p.openInventory(inv);

        Session s = sessions.computeIfAbsent(p.getUniqueId(), k -> new Session());
        s.artifactId = artifactId;
        s.assigned.clear();
        s.assigned.putAll(keep);
        assembleKeys.put(p.getUniqueId(), keys); // 顺序先存，槽位渲染再查

        // 渲染碎片按钮 lore 里的指派态（此时窗口已打开，renderAssemble 取到的才是新窗口）
        renderAssemble(p);
    }

    /** 拼图页槽位 → 聚合 key 列表（随每次 openAssemble 刷新）。 */
    private final Map<UUID, List<String>> assembleKeys = new HashMap<>();

    /** 刷新拼图页的 piece 槽位与修复按钮（不重建 Inventory，避免闪烁）。 */
    private void renderAssemble(Player p) {
        Session s = sessions.get(p.getUniqueId());
        Inventory inv = p.getOpenInventory().getTopInventory();
        plugin.getLogger().info("[DBG2] render session=" + (s == null ? "null" : s.artifactId + "/" + s.assigned.size())
                + " invHolder=" + (inv.getHolder() == null ? "null" : inv.getHolder().getClass().getSimpleName()));
        if (s == null || !(inv.getHolder() instanceof RestoreMenu)) return;
        Model.ArtifactDef def = plugin.catalog().byId(s.artifactId);
        if (def == null) return;

        int slotCount = Math.min(def.pieces(), 9);
        for (int piece = 0; piece < slotCount; piece++) {
            Model.FragmentData fd = s.assigned.get(piece);
            inv.setItem(piece, fd == null ? emptyPieceSlot(piece) : assignedPieceSlot(fd, piece, def));
        }
        inv.setItem(49, repairButton(def, s));

        // 刷新碎片按钮的「已指派」标记
        List<String> keys = assembleKeys.get(p.getUniqueId());
        if (keys != null) {
            Map<Integer, Model.FragmentData> scan = service.scanFragments(p);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Model.FragmentData fd : scan.values()) {
                if (!fd.artifactId().equals(s.artifactId)) continue;
                counts.merge(fd.piece() + "/" + fd.integrity() + "/" + fd.siteId(), 1, Integer::sum);
            }
            for (int i = 0; i < keys.size() && i < 27; i++) {
                String key = keys.get(i);
                int piece = Integer.parseInt(key.substring(0, key.indexOf('/')));
                Model.FragmentData fd = scanValuesByKey(scan, key);
                if (fd == null) continue; // 碎片在菜单打开期间消失，跳过该组
                boolean marked = fd.equals(s.assigned.get(piece));
                inv.setItem(9 + i, fragmentButton(fd, counts.getOrDefault(key, 0), def, marked));
            }
        }
        // 更新标题中的就位数
        inv.setItem(53, infoItem("<gray>已就位 </gray><yellow>" + s.assigned.size() + "</yellow><gray>/</gray><white>"
                + def.pieces() + "</white><gray> 片；点碎片 → 指派；点已就位槽位 = 换回</gray>"));
    }


    private Model.FragmentData scanValuesByKey(Map<Integer, Model.FragmentData> scan, String key) {
        for (Model.FragmentData fd : scan.values()) {
            if ((fd.piece() + "/" + fd.integrity() + "/" + fd.siteId()).equals(key)) return fd;
        }
        return null;
    }

    // ---------- 按钮工厂 ----------

    private ItemStack emptyPieceSlot(int piece) {
        ItemStack it = new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<dark_aqua>第 " + (piece + 1) + " 片槽位（空）</dark_aqua>"));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack assignedPieceSlot(Model.FragmentData fd, int piece, Model.ArtifactDef def) {
        ItemStack it = new ItemStack(Material.BRICK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<green>第 " + (piece + 1) + " 片 · 已就位</green>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm(ItemCodec.qualityLine(fd.integrity())));
        lore.add(Msg.mm("<dark_gray>点击换回（撤销指派）</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack fragmentButton(Model.FragmentData fd, int count, Model.ArtifactDef def) {
        return fragmentButton(fd, count, def, false);
    }

    private ItemStack fragmentButton(Model.FragmentData fd, int count, Model.ArtifactDef def, boolean assigned) {
        ItemStack it = new ItemStack(Material.BRICK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>残片</gold> <gray>(" + (fd.piece() + 1) + "/" + def.pieces()
                + ")</gray>" + (assigned ? " <green>✔ 已指派</green>" : "")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm(ItemCodec.qualityLine(fd.integrity())));
        lore.add(Msg.mm("<gray>出土地：遗址 </gray><white>" + fd.siteId() + "</white> <gray>× " + count + "</gray>"));
        lore.add(Msg.mm("<dark_gray>点击指派到第 " + (fd.piece() + 1) + " 片槽位</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack repairButton(Model.ArtifactDef def, Session s) {
        boolean ready = s.assigned.size() >= def.pieces();
        ItemStack it = new ItemStack(ready ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(ready ? "<green>✔ 开始修复</green>" : "<dark_gray>修复（未集齐）</dark_gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (ready) {
            lore.add(Msg.mm("<gray>消耗全部指派碎片，产出成品。</gray>"));
            lore.add(Msg.mm("<gray>满配奖励：所有片成色 ≥ </gray><white>" + plugin.digConfig().brokenDegree()
                    + "</white><gray> 时 +</gray><white>" + plugin.digConfig().fullSetBonus() + "</white><gray> 修复度</gray>"));
        } else {
            lore.add(Msg.mm("<dark_gray>还差 </dark_gray><red>" + (def.pieces() - s.assigned.size())
                    + "</red><dark_gray> 片</dark_gray>"));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack infoItem(String mini) {
        ItemStack it = new ItemStack(Material.PAPER);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(mini));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack navItem(Material mat, String mini) {
        ItemStack it = new ItemStack(mat);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(mini));
        it.setItemMeta(meta);
        return it;
    }

    private String rarityTag(Model.ArtifactDef def) {
        String r = def.rarity() == null ? "common" : def.rarity().toLowerCase(java.util.Locale.ROOT);
        return r;
    }

    // ---------- 点击路由（CANCEL 一切非功能点击；Shift 一律取消） ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RestoreMenu)) return;
        e.setCancelled(true); // 起手全取消，再按槽位放行
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return;
        if (!(e.getClickedInventory().getHolder() instanceof RestoreMenu)) return; // 底部背包点击一律取消
        if (e.getClick().isShiftClick()) return;

        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        int slot = e.getSlot();

        // 拼图页
        if (s.artifactId != null) {
            Model.ArtifactDef def = plugin.catalog().byId(s.artifactId);
            if (def == null) return;
            int slotCount = Math.min(def.pieces(), 9);

            if (slot < slotCount) {
                // 点 piece 槽位：已就位 → 换回
                if (s.assigned.remove(slot) != null) openAssemble(p, s.artifactId);
                return;
            }
            if (slot >= 9 && slot <= 35) {
                List<String> keys = assembleKeys.get(p.getUniqueId());
                int idx = slot - 9;
                if (keys == null || idx >= keys.size()) return;
                Model.FragmentData fd = scanValuesByKey(service.scanFragments(p), keys.get(idx));
                if (fd == null) return;
                // 指派（同 piece 重复点击 = 换成新碎片；其他 piece 不受影响）
                s.assigned.put(fd.piece(), fd);
                openAssemble(p, s.artifactId); // 整窗重开同步（增量 setItem 会被 Paper 的 cancel 重发覆盖）
                return;
            }
            if (slot == 45) { // 返回选择页
                openSelect(p);
                return;
            }
            if (slot == 49) { // 修复：全片就位才有效
                if (s.assigned.size() >= def.pieces()) {
                    Map<Integer, Model.FragmentData> snapshot = new HashMap<>(s.assigned);
                    service.performRestore(p, s.artifactId, snapshot);
                    openSelect(p); // 修复后回到选择页（碎片已消耗，重新聚合）
                } else {
                    Msg.send(p, "<red>碎片未集齐（" + s.assigned.size() + "/" + def.pieces() + "）。</red>");
                }
                return;
            }
            return;
        }

        // 组选择页
        List<String> ids = selectIds.get(p.getUniqueId());
        if (ids != null && slot < ids.size()) {
            openAssemble(p, ids.get(slot));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RestoreMenu) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        // 碎片从未离开背包，这里只需丢弃内存会话
        sessions.remove(p.getUniqueId());
        selectIds.remove(p.getUniqueId());
        assembleKeys.remove(p.getUniqueId());
    }
}
