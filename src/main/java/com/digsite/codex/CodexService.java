package com.digsite.codex;

import com.digsite.DigSiteConfig;
import com.digsite.DigSitePlugin;
import com.digsite.catalog.ArtifactCatalog;
import com.digsite.model.Model;
import com.digsite.store.CodexStore;
import com.digsite.util.Announcer;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图鉴 / 纪录墙服务（C 部分）。
 *
 * <p>对 CodexStore（代理A 实现）调用的 API：
 * <pre>
 *   boolean putFirstRestoration(Model.FirstRestoration fr)   // 已有同 artifactId 首修时返回 false，不覆盖
 *   Model.FirstRestoration firstRestoration(String artifactId)
 *   java.util.Collection&lt;Model.FirstRestoration&gt; allFirstRestorations()
 *   void putRecord(String key, String label, String holder, String value)
 *   java.util.Collection&lt;Model.RecordEntry&gt; records()
 *   void bumpSitesCleared()
 *   int sitesClearedTotal()
 *   void flush()
 * </pre>
 */
public final class CodexService implements Listener {

    private final DigSitePlugin plugin;
    private final CodexStore store;
    private final Announcer announcer;
    private final ItemCodec codec;
    private final ArtifactCatalog catalog;
    private final DigSiteConfig config;

    /** 每页展示的文物数（54 格布局：0..44 内容区 + 45/49/53 导航区）。 */
    private static final int PAGE_SIZE = 45;

    public CodexService(DigSitePlugin plugin, CodexStore store, Announcer announcer,
                        ItemCodec codec, ArtifactCatalog catalog, DigSiteConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.announcer = announcer;
        this.codec = codec;
        this.catalog = catalog;
        this.config = config;
        // 自注册：GUI 点击事件由本服务自己处理
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------- 事件回调（由修复/遗址/博物馆服务触发） ----------

    /**
     * 修复完成事件：
     * <ol>
     *   <li>首次修复留名（putFirstRestoration 返回 true = 全服第一件）→ 插队公告「全服首修」；</li>
     *   <li>rarity ∈ rare/epic/legendary 的全服第一件 → putRecord 落纪录（key = "first-&lt;rarity&gt;"）；</li>
     *   <li>epic+（epic/legendary）且 announce-epic-plus 开启 → 紧急公告。</li>
     * </ol>
     */
    public void onArtifactRestored(Player p, Model.ArtifactDef def, int degree) {
        long now = System.currentTimeMillis();
        boolean firstEver = store.putFirstRestoration(
                new Model.FirstRestoration(def.id(), p.getName(), p.getUniqueId(), now));
        if (firstEver) {
            announcer.announceUrgent("<gold>全服首修</gold><gray>：「</gray><yellow>" + def.name()
                    + "</yellow><gray>」由 </gray><white>" + p.getName()
                    + "</white><gray> 首次修复，图鉴留名！</gray>");
        }
        String rarity = def.rarity() == null ? "" : def.rarity().toLowerCase(Locale.ROOT);
        if (rarity.equals("rare") || rarity.equals("epic") || rarity.equals("legendary")) {
            String key = "first-" + rarity;
            if (record(key) == null) {
                store.putRecord(key, "首件 " + rarity, p.getName(), def.name());
            }
        }
        if ((rarity.equals("epic") || rarity.equals("legendary")) && config.announceEpicPlus()) {
            announcer.announceUrgent("<light_purple>✦ 传世时刻</light_purple><gray>：</gray><white>"
                    + p.getName() + "</white><gray> 修复出了 </gray>" + rarityColor(rarity)
                    + rarity + "<gray> 级文物「</gray><yellow>" + def.name() + "</yellow><gray>」！</gray>");
        }
    }

    /**
     * 遗址清空事件：累计计数进 store，只做公告（单日清剿数纪录按契约不落）。
     */
    public void onSiteCleared(String siteId, String tier, String clearerName) {
        store.bumpSitesCleared();
        long total = store.sitesClearedTotal();
        announcer.announce("<green>遗址清剿</green><gray>：</gray><white>" + clearerName
                + "</white><gray> 拆掉了 </gray><yellow>" + tier + "</yellow><gray> 遗址 </gray><white>"
                + siteId + "</white><gray>！全服累计清剿 </gray><white>" + total + "</white><gray> 处。</gray>");
    }

    /**
     * 捐献事件：只做公告（epic+ 紧急公告）与「馆藏稀有度首件」纪录（key = "hall-first-&lt;rarity&gt;"）。
     * 捐献榜计数由 PlayerStore/MuseumService 负责，不在本方法。
     */
    public void onDonation(Player p, Model.ArtifactDef def) {
        String rarity = def.rarity() == null ? "" : def.rarity().toLowerCase(Locale.ROOT);
        if (rarity.equals("rare") || rarity.equals("epic") || rarity.equals("legendary")) {
            String key = "hall-first-" + rarity;
            if (record(key) == null) {
                store.putRecord(key, "馆藏首件 " + rarity, p.getName(), def.name());
            }
        }
        if ((rarity.equals("epic") || rarity.equals("legendary")) && config.announceEpicPlus()) {
            announcer.announceUrgent("<light_purple>✦ 馆藏荣耀</light_purple><gray>：</gray><white>"
                    + p.getName() + "</white><gray> 将 </gray>" + rarityColor(rarity) + rarity
                    + "<gray> 级文物「</gray><yellow>" + def.name()
                    + "</yellow><gray>」捐入博物馆！</gray>");
        }
    }

    /** 镇馆之宝结算：落纪录（key = "vote-winner:&lt;exhibitId&gt;"，取 at 最新者为「最近获胜者」）+ 公告。 */
    public void recordVoteWinner(String exhibitId, String artifactName, String donorName) {
        store.putRecord("vote-winner:" + exhibitId, "镇馆之宝", donorName, artifactName);
        announcer.announce("<gold>镇馆之宝</gold><gray> 诞生：「</gray><yellow>" + artifactName
                + "</yellow><gray>」（捐献者 </gray><white>" + donorName + "</white><gray>）！快去博物馆一睹真容。</gray>");
    }

    /** 纪录墙文本（MiniMessage 行数组），供 /digs board 使用。 */
    public List<String> recordLines() {
        List<String> out = new ArrayList<>();
        out.add("<dark_gray>———————— 纪录墙 ————————</dark_gray>");
        out.add("<gold>图鉴首修</gold><gray>：已有 </gray><white>" + store.allFirstRestorations().size()
                + "</white><gray> 件文物被全服首修留名。</gray>");
        out.add("<gold>遗址清剿</gold><gray>：全服累计 </gray><white>" + store.sitesClearedTotal()
                + "</white><gray> 处遗址被清空。</gray>");
        for (String rarity : List.of("rare", "epic", "legendary")) {
            Model.RecordEntry e = record("first-" + rarity);
            if (e == null) {
                out.add(rarityColor(rarity) + "首件 " + rarity
                        + " <gray>：</gray><dark_gray>虚位以待</dark_gray>");
            } else {
                out.add(rarityColor(rarity) + "首件 " + rarity
                        + " <gray>：</gray><white>" + e.holderName()
                        + "</white><gray>（</gray><yellow>" + e.value() + "</yellow><gray>）</gray>");
            }
        }
        // 镇馆之宝最近获胜者：key 前缀 vote-winner: 中 at 最新的一条
        Model.RecordEntry win = null;
        for (Model.RecordEntry e : store.records()) {
            if (e.key().startsWith("vote-winner:") && (win == null || e.at() > win.at())) win = e;
        }
        if (win == null) {
            out.add("<gold>镇馆之宝</gold><gray>：</gray><dark_gray>尚无胜者，/digs vote 走起</dark_gray>");
        } else {
            out.add("<gold>镇馆之宝</gold><gray>：</gray><yellow>" + win.value()
                    + "</yellow><gray>（捐献者 </gray><white>" + win.holderName() + "</white><gray>）</gray>");
        }
        out.add("<gold>博物馆馆藏</gold><gray>：</gray><white>" + plugin.museumStore().exhibitCount()
                + "</white><gray> 件展品。</gray>");
        return out;
    }

    // ---------- 图鉴 GUI（54 格分页） ----------

    /**
     * 打开全服图鉴。已首修文物用 codec.artifact 展示（repairDegree=100、restorer=首修者）；
     * 未首修显示 BARRIER「？？？」+ 稀有度色条。
     */
    public void openCodex(Player p, int page) {
        List<Model.ArtifactDef> defs = catalog.all();
        int maxPage = Math.max(1, (defs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage - 1));

        CodexHolder holder = new CodexHolder(page, maxPage);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<dark_purple>考古图鉴</dark_purple> <gray>(" + (page + 1) + "/" + maxPage + ")</gray>"));
        holder.inv = inv;

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < defs.size(); i++) {
            Model.ArtifactDef def = defs.get(start + i);
            Model.FirstRestoration fr = store.firstRestoration(def.id());
            if (fr != null) {
                // 已首修：真实展示（成色按 100 展示，修复者写首修者），并追加系列信息 lore
                ItemStack it = codec.artifact(
                        new Model.ArtifactData(def.id(), 100, fr.restorerName(), fr.at()), def);
                appendSeriesLore(it, def);
                inv.setItem(i, it);
            } else {
                inv.setItem(i, unknownItem(def));
            }
        }
        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "<yellow>◀ 上一页</yellow>"));
        inv.setItem(49, navItem(Material.BOOK, "<white>第 " + (page + 1) + " / " + maxPage + " 页</white>"));
        // 53 放「系列进度」信息书；仅当未来文物 >45 件出现下一页时，信息书让位到 52
        if (page < maxPage - 1) {
            inv.setItem(53, navItem(Material.ARROW, "<yellow>下一页 ▶</yellow>"));
            inv.setItem(52, seriesBook());
        } else {
            inv.setItem(53, seriesBook());
        }
        p.openInventory(inv);
    }

    /** 给成品文物追加两行系列 lore：系列名 + 该系列已首修 x/y。 */
    private void appendSeriesLore(ItemStack it, Model.ArtifactDef def) {
        var meta = it.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        List<net.kyori.adventure.text.Component> existing = meta.lore(); // Paper 组件版 getter
        if (existing != null) lore.addAll(existing);
        lore.add(Msg.mm("<dark_aqua>系列：" + ArtifactCatalog.seriesDisplayName(def.series()) + "</dark_aqua>"));
        int[] xy = seriesProgress(def.series());
        lore.add(Msg.mm("<gray>该系列已首修 </gray><white>" + xy[0] + "</white><gray>/</gray><white>"
                + xy[1] + "</white><gray> 件</gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
    }

    /** 系列进度：该系列已全服首修件数 / 系列总件数。 */
    private int[] seriesProgress(String series) {
        List<Model.ArtifactDef> list = catalog.bySeries(series);
        int done = 0;
        for (Model.ArtifactDef d : list) {
            if (store.firstRestoration(d.id()) != null) done++;
        }
        return new int[]{done, list.size()};
    }

    /** 「系列进度」信息书：列出四个系列各自的已首修 / 总数。 */
    private ItemStack seriesBook() {
        ItemStack it = new ItemStack(Material.KNOWLEDGE_BOOK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<aqua>系列进度</aqua> <gray>（全服首修）</gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String s : catalog.seriesNames()) {
            int[] xy = seriesProgress(s);
            lore.add(Msg.mm("<dark_aqua>" + ArtifactCatalog.seriesDisplayName(s)
                    + "</dark_aqua><gray>：已首修 </gray><white>" + xy[0]
                    + "</white><gray>/</gray><white>" + xy[1] + "</white><gray> 件</gray>"));
        }
        lore.add(Msg.mm("<dark_gray>集齐一个系列的首修，是每位考古学家的荣誉。</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 未首修剪影：BARRIER + 「？？？」+ 稀有度色条 + 所属系列（系列名可见，藏品内容保密）。 */
    private ItemStack unknownItem(Model.ArtifactDef def) {
        ItemStack it = new ItemStack(Material.BARRIER);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<dark_gray>？？？</dark_gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        String rarity = def.rarity() == null ? "common" : def.rarity().toLowerCase(Locale.ROOT);
        lore.add(Msg.mm(rarityColor(rarity) + "稀有度：" + rarity + "</color>"));
        lore.add(Msg.mm("<dark_aqua>系列：" + ArtifactCatalog.seriesDisplayName(def.series()) + "</dark_aqua>"));
        lore.add(Msg.mm("<dark_gray>尚未有修复者留名，等待第一位考古学家。</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack navItem(Material mat, String nameMini) {
        ItemStack it = new ItemStack(mat);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(nameMini));
        it.setItemMeta(meta);
        return it;
    }

    /** 稀有度 → MiniMessage 颜色（MuseumService 同款）。 */
    public static String rarityColor(String rarity) {
        return switch (rarity == null ? "" : rarity.toLowerCase(Locale.ROOT)) {
            case "legendary" -> "<gold>";
            case "epic" -> "<light_purple>";
            case "rare" -> "<aqua>";
            default -> "<gray>";
        };
    }

    /** records() 是 Collection，按键线性查找（纪录条目量小，无需索引）。 */
    private Model.RecordEntry record(String key) {
        for (Model.RecordEntry e : store.records()) {
            if (e.key().equals(key)) return e;
        }
        return null;
    }

    // ---------- GUI 点击（自注册，CANCEL 一切非功能点击） ----------

    /** 图鉴界面专属 holder。 */
    private static final class CodexHolder implements InventoryHolder {
        final int page;
        final int maxPage;
        Inventory inv;

        CodexHolder(int page, int maxPage) {
            this.page = page;
            this.maxPage = maxPage;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof CodexHolder holder)) return;
        e.setCancelled(true); // 起手全取消，再按槽位放行
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return;
        if (!(e.getClickedInventory().getHolder() instanceof CodexHolder)) return; // 底部背包点击一律取消
        if (e.getClick().isShiftClick()) return; // Shift 一律取消

        int slot = e.getSlot();
        if (slot == 45 && holder.page > 0) {
            openCodex(p, holder.page - 1);
        } else if (slot == 53 && holder.page < holder.maxPage - 1) {
            openCodex(p, holder.page + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof CodexHolder) {
            e.setCancelled(true);
        }
    }
}
