package com.digsite.achieve;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 成就服务（E2）：12 条成就 + 达成播报 + /digs achieve 54 格查看 GUI。
 *
 * <p>触发方式分两类：
 * <ul>
 *   <li><b>事件式</b>：修复/捐献/投票/套件路径由 RestorationService、MuseumService、VoteService
 *       在成功点直接回调 {@link #checkRestore} / {@link #checkDonate} / {@link #checkVote}；</li>
 *   <li><b>轮询式</b>：site/** 由并行代理维护，不直接回调——每 60s 扫一遍在线玩家的
 *       PlayerStore.stats（fragmentsFound / sitesCleared / artifactsRestored / donations）
 *       判定 first_fragment、site_3 等被动成就。</li>
 * </ul>
 *
 * <p>达成表现：玩家 Title + 全服公告（Announcer 节流）；mark 幂等保证不重复弹。
 */
public final class AchieveService implements Listener {

    /** 成就定义（id / 名称 / 条件描述 / 是否有进度）。 */
    public record Def(String id, String name, String desc) {}

    /** 12 条成就，顺序即 GUI 展示顺序。 */
    public static final List<Def> DEFS = List.of(
            new Def("first_fragment", "首片入怀", "拾取第一块文物残片"),
            new Def("first_restore", "开卷初修", "首次修复出一件成品文物"),
            new Def("first_perfect", "无损首件", "首次修复出无损（成色 ≥ 95）文物"),
            new Def("restore_10", "修复十件", "累计修复 10 件成品文物"),
            new Def("donate_5", "藏而好施", "向博物馆捐献 5 件文物"),
            new Def("site_3", "踏遍三墟", "清剿 3 处遗址"),
            new Def("epic_first", "稀世初现", "首次修复出 epic 文物"),
            new Def("legendary_first", "国之重器", "首次修复出 legendary 文物"),
            new Def("perfect_3", "三件无损", "累计修复 3 件无损文物"),
            new Def("all_common", "凡物皆全", "修复全系列 common 文物各至少 1 件"),
            new Def("vote_first", "一票之缘", "首次为镇馆之宝投票"),
            new Def("kit_master", "精修大师", "首次使用精修钳或粘合剂完成修复"));

    private final DigSitePlugin plugin;
    private final AchieveStore store;
    private BukkitTask pollTask;

    public AchieveService(DigSitePlugin plugin, AchieveStore store) {
        this.plugin = plugin;
        this.store = store;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** 启动 60s 被动轮询（site 清剿/拾取等不由本轨触发的成就）。 */
    public void start() {
        stop();
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollPassive, 20L * 60L, 20L * 60L);
    }

    /** 关停轮询（onDisable 调用；存档 flush 由 AchieveStore 负责）。 */
    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    public AchieveStore store() {
        return store;
    }

    // ---------- 达成核心 ----------

    /** 达成标记 + Title + 公告；已达成时静默返回 false。 */
    private boolean grant(Player p, String id) {
        if (!store.mark(p.getUniqueId(), id)) return false;
        Def def = defOf(id);
        String name = def == null ? id : def.name();
        p.showTitle(Title.title(
                Msg.mm("<gold>✦ 成就达成</gold>"),
                Msg.mm("<yellow>" + name + "</yellow>"),
                Title.Times.times(java.time.Duration.ofMillis(200L),
                        java.time.Duration.ofMillis(2800L),
                        java.time.Duration.ofMillis(600L))));
        plugin.announcer().announce("<gold>成就</gold><gray>：</gray><white>" + p.getName()
                + "</white><gray> 达成了「</gray><yellow>" + name + "</yellow><gray>」！</gray>");
        return true;
    }

    private static Def defOf(String id) {
        for (Def d : DEFS) if (d.id().equals(id)) return d;
        return null;
    }

    // ---------- 事件式检查（由各服务在成功点回调） ----------

    /**
     * 修复成功回调。usedKit = 本次修复携带精修钳/粘合剂（无论实际是否改变数值，按契约只要套件参与即算）。
     * 覆盖：first_restore / restore_10 / first_perfect / perfect_3 / epic_first / legendary_first /
     * all_common / kit_master。
     */
    public void checkRestore(Player p, Model.ArtifactDef def, int degree, boolean usedKit) {
        grant(p, "first_restore");
        if (plugin.playerStore().stats(p.getUniqueId(), p.getName()).artifactsRestored() >= 10) {
            grant(p, "restore_10");
        }
        int perfectThreshold = plugin.digConfig().perfectDegree();
        if (degree >= perfectThreshold) {
            grant(p, "first_perfect");
            long n = store.bumpCounter("perfect:" + p.getUniqueId());
            if (n >= 3) grant(p, "perfect_3");
        }
        String rarity = def == null || def.rarity() == null ? "" : def.rarity().toLowerCase(Locale.ROOT);
        if (rarity.equals("epic")) grant(p, "epic_first");
        if (rarity.equals("legendary")) grant(p, "legendary_first");
        if (def != null && rarity.equals("common")) {
            // common 全集进度：计数器 key = common:<uuid>:<artifactId>
            store.bumpCounter("common:" + p.getUniqueId() + ":" + def.id());
            if (allCommonDone(p)) grant(p, "all_common");
        }
        if (usedKit) grant(p, "kit_master");
    }

    /** common 全系列是否都已由该玩家修复过。 */
    private boolean allCommonDone(Player p) {
        for (Model.ArtifactDef def : plugin.catalog().byRarity("common")) {
            if (store.getCounter("common:" + p.getUniqueId() + ":" + def.id()) <= 0) return false;
        }
        return !plugin.catalog().byRarity("common").isEmpty();
    }

    /** 捐献成功回调（覆盖 donate_5；计数以 PlayerStore.donations 为准）。 */
    public void checkDonate(Player p) {
        if (plugin.playerStore().stats(p.getUniqueId(), p.getName()).donations() >= 5) {
            grant(p, "donate_5");
        }
    }

    /** 投票成功回调（覆盖 vote_first）。 */
    public void checkVote(Player p) {
        grant(p, "vote_first");
    }

    // ---------- 轮询式检查 ----------

    private void pollPassive() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Model.PlayerStats s = plugin.playerStore().stats(p.getUniqueId(), p.getName());
            if (s.fragmentsFound() >= 1) grant(p, "first_fragment");
            if (s.sitesCleared() >= 3) grant(p, "site_3");
            if (s.artifactsRestored() >= 10) grant(p, "restore_10");
            if (s.donations() >= 5) grant(p, "donate_5");
        }
    }

    // ---------- 查看 GUI ----------

    /** /digs achieve：54 格成就墙；已达成绿色旗帜，未达成灰色书，lore 写条件与进度。 */
    public void openGui(Player p) {
        AchieveHolder holder = new AchieveHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.mm("<dark_green>考古成就墙</dark_green>"));
        holder.inv = inv;
        int slot = 0;
        for (Def def : DEFS) {
            if (slot >= 45) break; // 12 条用不满，防御性兜底
            boolean achieved = store.has(p.getUniqueId(), def.id());
            inv.setItem(slot++, achieved ? bannerItem(def, p) : bookItem(def, p));
        }
        long unlocked = store.of(p.getUniqueId()).size();
        inv.setItem(49, infoItem("<gray>已解锁 </gray><gold>" + unlocked + "</gold><gray> / </gray><white>"
                + DEFS.size() + "</white> <gray>条成就</gray>"));
        inv.setItem(53, infoItem("<gray>达成条件：修复、捐献、清剿、投票……慢慢探索</gray>"));
        p.openInventory(inv);
    }

    /** 已达成：绿色旗帜 + 条件复述。 */
    private ItemStack bannerItem(Def def, Player p) {
        ItemStack it = new ItemStack(Material.GREEN_BANNER);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<green>✔ " + def.name() + "</green>"));
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>条件：</gray><white>" + def.desc() + "</white>"));
        lore.add(Msg.mm("<green>已达成</green>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 未达成：书 + 条件 + 进度。 */
    private ItemStack bookItem(Def def, Player p) {
        ItemStack it = new ItemStack(Material.BOOK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gray>✘ " + def.name() + "</gray>"));
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>条件：</gray><white>" + def.desc() + "</white>"));
        String progress = progressOf(def, p);
        if (progress != null) lore.add(Msg.mm("<gray>进度：</gray><yellow>" + progress + "</yellow>"));
        lore.add(Msg.mm("<dark_gray>尚未达成</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 有量化进度的成就返回 "x/y"，否则 null。 */
    private String progressOf(Def def, Player p) {
        Model.PlayerStats s = plugin.playerStore().stats(p.getUniqueId(), p.getName());
        return switch (def.id()) {
            case "restore_10" -> Math.min(10, s.artifactsRestored()) + "/10 件";
            case "donate_5" -> Math.min(5, s.donations()) + "/5 件";
            case "site_3" -> Math.min(3, s.sitesCleared()) + "/3 处";
            case "perfect_3" -> Math.min(3, store.getCounter("perfect:" + p.getUniqueId())) + "/3 件";
            case "all_common" -> commonDoneCount(p) + "/" + plugin.catalog().byRarity("common").size() + " 种";
            default -> null;
        };
    }

    private long commonDoneCount(Player p) {
        long n = 0;
        for (Model.ArtifactDef def : plugin.catalog().byRarity("common")) {
            if (store.getCounter("common:" + p.getUniqueId() + ":" + def.id()) > 0) n++;
        }
        return n;
    }

    private ItemStack infoItem(String mini) {
        ItemStack it = new ItemStack(Material.PAPER);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(mini));
        it.setItemMeta(meta);
        return it;
    }

    /** 成就墙专属 holder（事件按 holder 类型路由）。 */
    private static final class AchieveHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof AchieveHolder)) return;
        e.setCancelled(true); // 纯展示墙：一切点击取消
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof AchieveHolder) {
            e.setCancelled(true);
        }
    }
}
