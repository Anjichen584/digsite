package com.digsite.museum;

import com.digsite.DigSiteConfig;
import com.digsite.DigSitePlugin;
import com.digsite.catalog.ArtifactCatalog;
import com.digsite.model.Model;
import com.digsite.store.MuseumStore;
import com.digsite.util.Announcer;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 博物馆服务（C 部分）：分馆/展台生成、捐献陈列、总览 GUI、展品移除与重启补齐。
 *
 * <p>对 MuseumStore（代理A 实现）调用的 API：
 * <pre>
 *   boolean addHall(Model.HallInfo hall)                          // 同名（忽略大小写）返回 false
 *   java.util.List&lt;Model.HallInfo&gt; halls()
 *   Model.HallInfo hallByName(String name)
 *   java.util.List&lt;Model.Pedestal&gt; pedestals(String hallName)
 *   void setPedestal(String hallName, Model.Pedestal pedestal)    // 按 index 覆盖
 *   java.util.Optional&lt;Model.Pedestal&gt; firstFreePedestal(String hallName)
 *   void initPedestals(String hallName, List&lt;Model.Pedestal&gt; list)
 *   String addExhibit(Model.ExhibitInfo exhibit)                  // 返回最终 exhibitId
 *   Model.ExhibitInfo exhibit(String exhibitId)
 *   java.util.Collection&lt;Model.ExhibitInfo&gt; exhibits()
 *   int exhibitCount()
 *   void removeExhibit(String exhibitId)                          // store 侧同步清空引用展台
 *   boolean vote / Map&lt;String,Integer&gt; votes / lastPeriodKey / setLastPeriodKey
 *   void flush()
 * </pre>
 */
public final class MuseumService implements Listener {

    private final DigSitePlugin plugin;
    private final MuseumStore store;
    private final ItemCodec codec;
    private final Announcer announcer;
    private final ArtifactCatalog catalog;
    private final DigSiteConfig config;

    public MuseumService(DigSitePlugin plugin, MuseumStore store, ItemCodec codec, Announcer announcer,
                         ArtifactCatalog catalog, DigSiteConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.codec = codec;
        this.announcer = announcer;
        this.catalog = catalog;
        this.config = config;
        // 总览 GUI 自注册（同 RestoreMenu 的防抖规则）
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ---------- 分馆与展台 ----------

    /**
     * 以 center 为圆心环形生成展台（契约入口，无玩家来源）：
     * 每台一根 QUARTZ_PILLAR 柱，柱位 = floor(center.x + cos·r), center.y, floor(center.z + sin·r)
     * （要求管理员站平地施法）；柱方块上方 0.3 处为展台点位。
     * 持久化 HallInfo + pedestalsPerHall() 个 Pedestal（exhibitId 均为 null）。
     * max-halls 与重名校验失败时只记日志并放弃（命令层请用带 Player 的重载获得反馈）。
     */
    public void addHall(String name, Location center) {
        if (name == null || name.isBlank()) {
            plugin.getLogger().warning("addHall：分馆名为空，已忽略。");
            return;
        }
        if (store.halls().size() >= config.museumMaxHalls()) {
            plugin.getLogger().warning("addHall：分馆已达上限 " + config.museumMaxHalls() + "，「" + name + "」未落成。");
            return;
        }
        if (store.hallByName(name) != null) {
            plugin.getLogger().warning("addHall：重名分馆「" + name + "」未落成。");
            return;
        }
        addHallInternal(name, center);
        plugin.getLogger().info("分馆「" + name + "」落成（" + config.pedestalsPerHall() + " 台展台）。");
    }

    /** 带管理员来源的重载：校验失败时给玩家反馈。供命令层使用。 */
    public boolean addHall(String name, Player admin, Location center) {
        if (name == null || name.isBlank()) {
            Msg.send(admin, "<red>分馆名不能为空。</red>");
            return false;
        }
        if (store.halls().size() >= config.museumMaxHalls()) {
            Msg.send(admin, "<red>分馆已达上限（</red><white>" + config.museumMaxHalls()
                    + "</white><red>），无法再建。</red>");
            return false;
        }
        if (store.hallByName(name) != null) {
            Msg.send(admin, "<red>已存在同名分馆「</red><yellow>" + name + "</yellow><red>」。</red>");
            return false;
        }
        addHallInternal(name, center);
        Msg.send(admin, "<green>分馆「</green><gold>" + name + "</gold><green>」落成：环形 </green><white>"
                + config.pedestalsPerHall() + "</white><green> 台展台已就位。</green>");
        return true;
    }

    private void addHallInternal(String name, Location center) {
        World world = center.getWorld();
        int n = Math.max(1, config.pedestalsPerHall());
        double r = config.pedestalRadius();

        store.addHall(new Model.HallInfo(name, world.getName(),
                center.getX(), center.getY(), center.getZ()));

        List<Model.Pedestal> peds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            int px = (int) Math.floor(center.getX() + Math.cos(angle) * r);
            int py = center.getBlockY();
            int pz = (int) Math.floor(center.getZ() + Math.sin(angle) * r);

            // 柱体 + 清空柱上方两格给展示实体留位
            Block pillar = world.getBlockAt(px, py, pz);
            pillar.setType(Material.QUARTZ_PILLAR);
            world.getBlockAt(px, py + 1, pz).setType(Material.AIR);
            world.getBlockAt(px, py + 2, pz).setType(Material.AIR);

            // 展台点位 = 柱方块上方 0.3 处
            double ex = px + 0.5, ey = py + 1 + 0.3, ez = pz + 0.5;
            peds.add(new Model.Pedestal(i, ex, ey, ez, null, null, null));
        }
        store.initPedestals(name, peds);

        announcer.announce("<gold>新分馆落成</gold><gray>：「</gray><yellow>" + name
                + "</yellow><gray>」正在征集文物，/digs donate 捐出你的修复成果！</gray>");
    }

    // ---------- 捐献陈列 ----------

    /** /digs donate：读主手成品 → 分配第一个空台 → 生成 ItemDisplay + TextDisplay → 持久化。 */
    public void donateHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        // E3：纪念拓片不可回流捐献
        if (com.digsite.util.KitCodec.isReplica(hand)) {
            Msg.send(p, "<red>这是纪念拓片，博物馆不收。</red><gray>它是馆藏纪念品，请留作展示。</gray>");
            return;
        }
        // I2：迷你复原件同样不可回流捐献（museum 包内自判 PDC，不改 KitCodec）
        if (MiniReplica.is(hand)) {
            Msg.send(p, "<red>这是迷你复原件，博物馆不收。</red><gray>馆藏监制的装饰品，请留作展示。</gray>");
            return;
        }
        Model.ArtifactData data = codec.readArtifact(hand);
        if (data == null) {
            Msg.send(p, "<red>主手不是修复完成的文物成品（无 PDC 标识）。</red>");
            return;
        }
        Model.ArtifactDef def = catalog.byId(data.artifactId());
        if (def == null) {
            // PDC 被伪造成未知 id：不没收，仅提示无效
            Msg.send(p, "<red>该物品的文物 id 未登记在目录中，无法入馆（物品已退回）。</red>");
            return;
        }

        // 找第一个空台（跨全馆扫描，馆序优先）
        Model.HallInfo targetHall = null;
        Model.Pedestal targetPed = null;
        for (Model.HallInfo hall : store.halls()) {
            var free = store.firstFreePedestal(hall.name());
            if (free.isPresent()) {
                targetHall = hall;
                targetPed = free.get();
                break;
            }
        }
        if (targetHall == null) {
            Msg.send(p, "<red>全馆没有空展台了，等管理员加盖分馆或移除旧展品。</red>");
            return;
        }

        World world = Bukkit.getWorld(targetHall.worldName());
        if (world == null) {
            Msg.send(p, "<red>目标分馆所在世界未加载，捐献失败。</red>");
            return;
        }
        Location loc = new Location(world, targetPed.x(), targetPed.y(), targetPed.z());
        // exhibitId 自生成（addExhibit 原样接收并返回，保持展台/展品/纪录三处一致）
        String exhibitId = "e" + Long.toHexString(System.currentTimeMillis()) + "-"
                + UUID.randomUUID().toString().substring(0, 4);

        // 展品实体：ItemDisplay 托文物 + TextDisplay 铭牌
        ItemStack displayItem = hand.clone();
        displayItem.setAmount(1);
        ItemDisplay item = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(displayItem);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(0f, 0f, 0f), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.Quaternionf())); // 缩放 0.6
            d.setShadowRadius(0f);
            d.setPersistent(true);
        });
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        TextDisplay plaque = world.spawn(loc.clone().add(0, 0.55, 0), TextDisplay.class, d -> {
            d.text(Msg.mm("<gold>" + def.name() + "</gold><newline>"
                    + ItemCodec.qualityLine(data.repairDegree()) + "<newline>"
                    + "<gray>修复者：</gray><white>" + data.restorer() + "</white><newline>"
                    + "<gray>捐献日期：</gray><white>" + date + "</white>"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(100, 0, 0, 0)); // 半透明黑底
            d.setShadowed(true);
            d.setPersistent(true);
        });

        // 持久化：展台写回 exhibitId + 两实体 UUID，展品入档
        store.setPedestal(targetHall.name(), new Model.Pedestal(targetPed.index(), targetPed.x(),
                targetPed.y(), targetPed.z(), exhibitId, item.getUniqueId(), plaque.getUniqueId()));
        store.addExhibit(new Model.ExhibitInfo(exhibitId, data.artifactId(), data.repairDegree(),
                p.getName(), targetHall.name(), System.currentTimeMillis()));

        // 图鉴捐献回调（epic+ 公告 / 馆藏稀有度首件纪录）
        plugin.codexService().onDonation(p, def);
        // 捐献统计 + 成就回调（E2/E3）：donate_5 以 PlayerStore.donations 为准
        plugin.playerStore().bumpDonations(p.getUniqueId(), p.getName(), 1);
        if (plugin.achieveService() != null) plugin.achieveService().checkDonate(p);

        Msg.send(p, "<green>捐献成功！</green><gray>「</gray><gold>" + def.name() + "</gold><gray>」陈列于分馆「</gray>"
                + targetHall.name() + "<gray>」展台 #</gray><white>" + (targetPed.index() + 1)
                + "</white><gray>（</gray><white>" + loc.getBlockX() + ", " + loc.getBlockY() + ", "
                + loc.getBlockZ() + "</white><gray>）</gray>");
        // I4 分馆主题建议：按稀有度写死推荐（不依赖 F 轨 series，零编译依赖）
        String hallTip = hallSuggestionByRarity(def.rarity());
        if (hallTip != null) Msg.send(p, hallTip);
        // 待 F 轨落地后启用（series 相关，合体时替换上方稀有度建议为更细的系列推荐）：
        // String series = def.series(); // dynasty→王朝礼器馆 / nature→自然史馆 / relic→异域奇珍馆 / daily→日常旧物馆
        // 与 targetHall.name() 比对，不一致时追加一行「这件 <series名> 文物或许更适合 <对应分馆>」
    }

    /**
     * I4：按稀有度推荐分馆（写死逻辑）：epic/legendary → 珍品馆，common → 民俗馆，rare/未知 → 不建议。
     * 纯提示文案，不校验分馆是否真实存在（馆名以服主实际建馆为准）。
     */
    private static String hallSuggestionByRarity(String rarity) {
        if (rarity == null) return null;
        return switch (rarity.toLowerCase()) {
            case "epic", "legendary" -> "<gray>分馆建议：</gray>此类珍品更适合「<gold>珍品馆</gold>」<gray>。</gray>";
            case "common" -> "<gray>分馆建议：</gray>此类旧物更适合「<gold>民俗馆</gold>」<gray>。</gray>";
            default -> null;
        };
    }

    // ---------- 总览 GUI ----------

    private enum Mode { HALLS, EXHIBITS, RANKING, WORKSHOP }

    private static final class OverviewState {
        Mode mode = Mode.HALLS;
        String hall;
        int page;
    }

    private final Map<UUID, OverviewState> states = new HashMap<>();
    /** halls 页槽位 → 馆名。 */
    private final Map<UUID, List<String>> hallButtons = new HashMap<>();
    /** exhibits / ranking 页槽位 → 展品（ranking 页存 Top10 有序表）。 */
    private final Map<UUID, List<Model.ExhibitInfo>> exhibitButtons = new HashMap<>();
    /** workshop 页槽位 → 可订做的 artifactId。 */
    private final Map<UUID, List<String>> workshopButtons = new HashMap<>();

    /** /digs museum：总览 GUI，每馆一个按钮（名称 + 馆藏/空台数）。 */
    public void openOverview(Player p) {
        OverviewState st = states.computeIfAbsent(p.getUniqueId(), k -> new OverviewState());
        st.mode = Mode.HALLS;
        st.page = 0;
        renderHalls(p, st);
    }

    private void renderHalls(Player p, OverviewState st) {
        OverviewHolder holder = new OverviewHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.mm("<dark_green>博物馆 · 分馆总览</dark_green>"));
        holder.inv = inv;
        List<Model.HallInfo> halls = store.halls();
        int slot = 0;
        for (Model.HallInfo hall : halls) {
            if (slot >= 45) break; // 分馆数有 max-halls 上限，正常到不了 45
            int occupied = 0;
            int total = 0;
            for (Model.Pedestal ped : store.pedestals(hall.name())) {
                total++;
                if (ped.exhibitId() != null) occupied++;
            }
            // I1：该馆累计参观数 = 馆内全部展品 visit 计数求和（AchieveStore KV，只调用）
            long visits = ExhibitStats.hallVisits(store, plugin.achieveStore(), hall.name());
            ItemStack btn = new ItemStack(Material.CHEST);
            var meta = btn.getItemMeta();
            meta.displayName(Msg.mm("<gold>" + hall.name() + "</gold>"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Msg.mm("<gray>馆藏：</gray><white>" + occupied + "</white><gray> / 展台 </gray><white>"
                    + total + "</white><gray> 台（空 </gray><yellow>" + (total - occupied) + "</yellow><gray>）</gray>"));
            lore.add(Msg.mm("<gray>累计参观：</gray><white>" + visits + "</white><gray> 次</gray>"));
            lore.add(Msg.mm("<dark_gray>点击查看本馆展品</dark_gray>"));
            meta.lore(lore);
            btn.setItemMeta(meta);
            inv.setItem(slot++, btn);
        }
        if (halls.isEmpty()) {
            inv.setItem(22, infoItem("<dark_gray>还没有分馆，/digs admin hall add <名> 落成第一座</dark_gray>"));
        }
        // I3：馆藏指数信息书（45 槽）
        inv.setItem(45, indexBook());
        inv.setItem(49, infoItem("<gray>馆藏总计 </gray><white>" + store.exhibitCount() + "</white><gray> 件</gray>"));
        // I1：参观排行入口（53 槽）
        inv.setItem(53, rankEntryButton());
        p.openInventory(inv);
        List<String> names = new ArrayList<>();
        for (Model.HallInfo h : halls) names.add(h.name());
        hallButtons.put(p.getUniqueId(), names);
    }

    /** 某馆展品页：每展品一格 + 分页/返回按钮。 */
    private void renderExhibits(Player p, OverviewState st) {
        OverviewHolder holder = new OverviewHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Msg.mm("<dark_green>博物馆 · " + st.hall + " <gray>(第 " + (st.page + 1) + " 页)</gray></dark_green>"));
        holder.inv = inv;
        List<Model.ExhibitInfo> exhibits = new ArrayList<>();
        for (Model.Pedestal ped : store.pedestals(st.hall)) {
            if (ped.exhibitId() == null) continue;
            Model.ExhibitInfo ex = store.exhibit(ped.exhibitId());
            if (ex != null) exhibits.add(ex);
        }
        // I1：馆内按累计参观次数降序展示（并列保持捐献先后）
        exhibits.sort((a, b) -> {
            long va = ExhibitStats.visitsOf(plugin.achieveStore(), a.exhibitId());
            long vb = ExhibitStats.visitsOf(plugin.achieveStore(), b.exhibitId());
            if (va != vb) return Long.compare(vb, va);
            return Long.compare(a.donatedAt(), b.donatedAt());
        });
        int start = st.page * 45;
        int slot = 0;
        for (int i = start; i < exhibits.size() && slot < 45; i++, slot++) {
            inv.setItem(slot, exhibitButton(exhibits.get(i)));
        }
        if (st.page > 0) inv.setItem(45, navItem(Material.ARROW, "<yellow>◀ 上一页</yellow>"));
        inv.setItem(48, navItem(Material.ARROW, "<yellow>◀ 返回总览</yellow>"));
        inv.setItem(49, infoItem("<gray>左键看详情 · 右键兑换拓片 · 「复原件工坊」订做装饰品 · 管理员 shift+点击移除</gray>"));
        inv.setItem(50, replicaButton());
        // I2：复原件工坊入口（51 槽，拓片兑换的旁路）
        inv.setItem(51, workshopEntryButton());
        if (start + 45 < exhibits.size()) inv.setItem(53, navItem(Material.ARROW, "<yellow>下一页 ▶</yellow>"));
        p.openInventory(inv);
        exhibitButtons.put(p.getUniqueId(), exhibits);
    }

    /** 展品按钮：按 def 材质展示，lore：成色/捐献者（兼修复者，ExhibitInfo 无独立字段）/日期。 */
    private ItemStack exhibitButton(Model.ExhibitInfo ex) {
        Model.ArtifactDef def = catalog.byId(ex.artifactId());
        ItemStack it = new ItemStack(defMaterial(def));
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>" + (def == null ? ex.artifactId() : def.name()) + "</gold>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm(ItemCodec.qualityLine(ex.repairDegree())));
        lore.add(Msg.mm("<gray>捐献者：</gray><white>" + ex.donorName() + "</white>"));
        lore.add(Msg.mm("<gray>捐献日期：</gray><white>"
                + new SimpleDateFormat("yyyy-MM-dd").format(new Date(ex.donatedAt())) + "</white>"));
        lore.add(Msg.mm("<gray>展馆：</gray><white>" + ex.hallName() + "</white>"));
        // I1：该展品累计参观次数
        lore.add(Msg.mm("<gray>参观：</gray><white>"
                + ExhibitStats.visitsOf(plugin.achieveStore(), ex.exhibitId()) + "</white><gray> 次</gray>"));
        // I4：所在馆与稀有度推荐不符时给一行灰色建议（series 比对待 F 轨落地后启用）
        String recHall = recommendedHall(def);
        if (recHall != null && !recHall.equals(ex.hallName())) {
            lore.add(Msg.mm("<gray>" + ("珍品馆".equals(recHall)
                    ? "这件珍品或许更适合珍品馆" : "这件旧物或许更适合民俗馆") + "</gray>"));
        }
        // 待 F 轨落地后启用：按 def.series() 与分馆主题比对给系列级建议
        lore.add(Msg.mm("<dark_gray>ID：</dark_gray><dark_green>" + ex.exhibitId() + "</dark_green>"));
        lore.add(Msg.mm("<dark_gray>点击输出铭牌详情</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** 拓片兑换说明按钮（展品页 50 号槽）。 */
    private ItemStack replicaButton() {
        ItemStack it = new ItemStack(Material.EMERALD);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<green>拓片兑换</green>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>在展品上按</gray><yellow> 右键</yellow><gray>，消耗</gray><white> 绿宝石 ×"
                + com.digsite.util.KitCodec.REPLICA_COST + "</white><gray> 兑换该文物拓片。</gray>"));
        lore.add(Msg.mm("<dark_gray>拓片为馆藏纪念品，不可捐献。</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** I2：复原件工坊入口按钮（展品页 51 号槽）。 */
    private ItemStack workshopEntryButton() {
        ItemStack it = new ItemStack(Material.SMITHING_TABLE);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<aqua>复原件工坊</aqua>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>把心爱的文物「订做」成迷你复原件（纯装饰）。</gray>"));
        lore.add(Msg.mm("<gray>条件：该文物</gray><yellow>已被任意玩家捐献过</yellow><gray>，且备齐材料：</gray>"));
        lore.add(Msg.mm("<white>绿宝石 ×" + MiniReplica.COST_EMERALD + " + 铁锭 ×" + MiniReplica.COST_IRON
                + " + 文物同款材质 ×" + MiniReplica.COST_DISPLAY + "</white>"));
        lore.add(Msg.mm("<dark_gray>点击进入工坊选品</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** I3：馆藏指数信息书（总览页 45 号槽）。 */
    private ItemStack indexBook() {
        long idx = ExhibitStats.collectionIndex(store, catalog);
        Map<String, Integer> dist = ExhibitStats.rarityDistribution(store, catalog);
        ItemStack it = new ItemStack(Material.WRITABLE_BOOK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<aqua>馆藏指数</aqua>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>全服馆藏稀有度评分：</gray><white>" + idx + "</white>"));
        lore.add(Msg.mm("<gray>普通 </gray><white>" + dist.get("common") + "</white><gray> 件 · 稀有 </gray><white>"
                + dist.get("rare") + "</white><gray> 件</gray>"));
        lore.add(Msg.mm("<gray>史诗 </gray><white>" + dist.get("epic") + "</white><gray> 件 · 传说 </gray><white>"
                + dist.get("legendary") + "</white><gray> 件</gray>"));
        lore.add(Msg.mm("<dark_gray>评分 = 普通1 / 稀有2 / 史诗3 / 传说4 逐件求和</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** I1：参观排行入口按钮（总览页 53 号槽）。 */
    private ItemStack rankEntryButton() {
        ItemStack it = new ItemStack(Material.GOLD_BLOCK);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<yellow>参观排行</yellow>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>全馆最热门展品 Top10</gray>"));
        lore.add(Msg.mm("<dark_gray>点击查看榜单</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** I1：参观排行子 GUI（Top10，名称/捐献者/参观次数；空数据给提示）。 */
    private void renderRanking(Player p, OverviewState st) {
        OverviewHolder holder = new OverviewHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.mm("<dark_green>博物馆 · 参观排行</dark_green>"));
        holder.inv = inv;
        List<ExhibitStats.Ranked> ranked = ExhibitStats.ranking(store, plugin.achieveStore());
        List<Model.ExhibitInfo> ordered = new ArrayList<>();
        if (ranked.isEmpty()) {
            inv.setItem(22, infoItem("<dark_gray>还没有展品参观数据，先去捐出第一件文物吧（/digs donate）</dark_gray>"));
        } else {
            int slot = 0;
            for (int i = 0; i < ranked.size() && slot < 10; i++, slot++) {
                ExhibitStats.Ranked r = ranked.get(i);
                ordered.add(r.exhibit());
                inv.setItem(slot, rankRowButton(r, i + 1));
            }
        }
        inv.setItem(48, navItem(Material.ARROW, "<yellow>◀ 返回总览</yellow>"));
        inv.setItem(49, infoItem("<gray>按累计参观次数排序（左键点开展品详情即 +1）</gray>"));
        p.openInventory(inv);
        exhibitButtons.put(p.getUniqueId(), ordered); // 供点击条目输出铭牌详情
    }

    /** I1：排行单条按钮：名次 + 名称 + 捐献者 + 参观次数。 */
    private ItemStack rankRowButton(ExhibitStats.Ranked r, int place) {
        Model.ExhibitInfo ex = r.exhibit();
        ItemStack it = new ItemStack(defMaterial(catalog.byId(ex.artifactId())));
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<yellow>#" + place + "</yellow> <gold>" + safeName(ex.artifactId()) + "</gold>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>捐献者：</gray><white>" + ex.donorName() + "</white>"));
        lore.add(Msg.mm("<gray>参观次数：</gray><white>" + r.visits() + "</white>"));
        lore.add(Msg.mm("<gray>展馆：</gray><white>" + ex.hallName() + "</white>"));
        lore.add(Msg.mm("<dark_gray>点击输出铭牌详情</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** I2：工坊页：列出已被捐献过的文物（按首次捐献顺序去重），点击订做。 */
    private void renderWorkshop(Player p, OverviewState st) {
        OverviewHolder holder = new OverviewHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Msg.mm("<dark_green>博物馆 · 复原件工坊 <gray>(第 "
                + (st.page + 1) + " 页)</gray></dark_green>"));
        holder.inv = inv;
        // 条件一：该文物已有任意玩家捐献过（store.exhibits 去重取 artifactId）
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> ids = new ArrayList<>();
        for (Model.ExhibitInfo ex : store.exhibits()) {
            if (catalog.byId(ex.artifactId()) == null) continue; // 目录未登记的无法订做
            if (seen.add(ex.artifactId())) ids.add(ex.artifactId());
        }
        int start = st.page * 45;
        int slot = 0;
        for (int i = start; i < ids.size() && slot < 45; i++, slot++) {
            inv.setItem(slot, workshopItem(ids.get(i)));
        }
        if (ids.isEmpty()) {
            inv.setItem(22, infoItem("<dark_gray>还没有可订做的文物：需要先有玩家捐献过该文物</dark_gray>"));
        }
        if (st.page > 0) inv.setItem(45, navItem(Material.ARROW, "<yellow>◀ 上一页</yellow>"));
        inv.setItem(48, navItem(Material.ARROW, "<yellow>◀ 返回展品页</yellow>"));
        inv.setItem(49, infoItem("<gray>点击文物订做迷你复原件：绿宝石 ×" + MiniReplica.COST_EMERALD
                + " + 铁锭 ×" + MiniReplica.COST_IRON + " + 同款材质 ×" + MiniReplica.COST_DISPLAY + "</gray>"));
        if (start + 45 < ids.size()) inv.setItem(53, navItem(Material.ARROW, "<yellow>下一页 ▶</yellow>"));
        p.openInventory(inv);
        workshopButtons.put(p.getUniqueId(), ids);
    }

    /** I2：工坊单件按钮：文物名 + 稀有度 + 材料清单。 */
    private ItemStack workshopItem(String artifactId) {
        Model.ArtifactDef def = catalog.byId(artifactId);
        ItemStack it = new ItemStack(defMaterial(def));
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm("<gold>" + (def == null ? artifactId : def.name()) + "</gold> <gray>· 订做</gray>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (def != null) lore.add(Msg.mm("<gray>稀有度：</gray><white>" + def.rarity() + "</white>"));
        lore.add(Msg.mm("<gray>材料：</gray><white>绿宝石 ×" + MiniReplica.COST_EMERALD + " + 铁锭 ×"
                + MiniReplica.COST_IRON + " + </white><yellow>" + defMaterialName(def) + " ×"
                + MiniReplica.COST_DISPLAY + "</yellow>"));
        lore.add(Msg.mm("<dark_gray>点击订做（产出迷你复原件，不可捐献）</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** def 的 display 材质展示名（目录缺失时兜底 BRICK）。 */
    private static String defMaterialName(Model.ArtifactDef def) {
        return def == null ? Material.BRICK.name() : def.displayMaterial();
    }

    /**
     * I2：订做迷你复原件。
     * 条件：文物已被任意玩家捐献过 + 绿宝石×16 + 铁锭×8 + display 材质×4；
     * 先校验后扣款（跨槽位精确扣除），再发货 SPYGLASS（PDC mini_replica）；背包满掉脚下。
     * 复原件不计入馆藏、不触发捐献统计（donateHand 已拦截回流）。
     */
    private void orderMiniReplica(Player p, String artifactId) {
        Model.ArtifactDef def = catalog.byId(artifactId);
        if (def == null) {
            Msg.send(p, "<red>该文物未登记在目录中，无法订做。</red>");
            return;
        }
        boolean donated = false;
        for (Model.ExhibitInfo ex : store.exhibits()) {
            if (artifactId.equals(ex.artifactId())) {
                donated = true;
                break;
            }
        }
        if (!donated) {
            Msg.send(p, "<red>「</red><yellow>" + def.name()
                    + "</yellow><red>」尚无馆藏原件，暂不可订做复原件。</red>");
            return;
        }
        Material disp = defMaterial(def);
        int haveE = countMat(p, Material.EMERALD);
        int haveI = countMat(p, Material.IRON_INGOT);
        int haveD = countMat(p, disp);
        List<String> missing = new ArrayList<>();
        if (haveE < MiniReplica.COST_EMERALD) missing.add("绿宝石 ×" + (MiniReplica.COST_EMERALD - haveE));
        if (haveI < MiniReplica.COST_IRON) missing.add("铁锭 ×" + (MiniReplica.COST_IRON - haveI));
        if (haveD < MiniReplica.COST_DISPLAY) missing.add(defMaterialName(def) + " ×" + (MiniReplica.COST_DISPLAY - haveD));
        if (!missing.isEmpty()) {
            Msg.send(p, "<red>材料不足，还缺：</red><yellow>" + String.join("、", missing) + "</yellow>");
            return;
        }
        // 先扣后给：跨槽位精确扣除三类材料
        takeMat(p, Material.EMERALD, MiniReplica.COST_EMERALD);
        takeMat(p, Material.IRON_INGOT, MiniReplica.COST_IRON);
        takeMat(p, disp, MiniReplica.COST_DISPLAY);
        ItemStack mini = MiniReplica.create(artifactId, def.name());
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(mini);
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        // 订做计数 KV 约定：replica:<playerUuid>（只记复原件订做次数，供后续扩展）
        if (plugin.achieveStore() != null) plugin.achieveStore().bumpCounter("replica:" + p.getUniqueId());
        Msg.send(p, "<green>复原件完工！</green><gray>「</gray><gold>" + def.name()
                + "</gold><gray> · 复原件」已放入背包，可放进物品展示框装点你的基地（不可捐献）。</gray>");
    }

    /** 统计背包（含主手）中某材质总件数。 */
    private static int countMat(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == mat) n += it.getAmount();
        }
        return n;
    }

    /** 跨槽位精确扣除 amount 件某材质（调用方须先 countMat 校验充足）。 */
    private static void takeMat(Player p, Material mat, int amount) {
        ItemStack[] storage = p.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(remaining, it.getAmount());
            remaining -= take;
            if (it.getAmount() <= take) {
                p.getInventory().setItem(i, null);
            } else {
                ItemStack rest = it.clone();
                rest.setAmount(it.getAmount() - take);
                p.getInventory().setItem(i, rest);
            }
        }
    }

    /** I4：按稀有度推荐分馆名（epic/legendary→珍品馆，common→民俗馆，其余 null）；series 建议待 F 轨落地后叠加。 */
    private static String recommendedHall(Model.ArtifactDef def) {
        if (def == null || def.rarity() == null) return null;
        return switch (def.rarity().toLowerCase()) {
            case "epic", "legendary" -> "珍品馆";
            case "common" -> "民俗馆";
            default -> null;
        };
    }
    /** def 材质解析兜底（等价 codec 内部逻辑）。 */
    private static Material defMaterial(Model.ArtifactDef def) {
        if (def == null) return Material.BRICK;
        Material mat = Material.matchMaterial(def.displayMaterial());
        return mat == null ? Material.BRICK : mat;
    }

    private ItemStack navItem(Material mat, String mini) {
        ItemStack it = new ItemStack(mat);
        var meta = it.getItemMeta();
        meta.displayName(Msg.mm(mini));
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

    /** 总览 GUI holder（每次打开新建实例，事件按 holder 类型路由）。 */
    private static final class OverviewHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ---------- 点击路由（起手全取消，再按槽位放行） ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof OverviewHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return;
        if (!(e.getClickedInventory().getHolder() instanceof OverviewHolder)) return; // 底部背包点击一律取消

        OverviewState st = states.get(p.getUniqueId());
        if (st == null) return;
        int slot = e.getSlot();

        // EXHIBITS 页的例外：管理员 shift+点击展品 → 移除该展品（放行后再刷新）
        if (st.mode == Mode.EXHIBITS && e.getClick().isShiftClick()) {
            List<Model.ExhibitInfo> list = exhibitButtons.getOrDefault(p.getUniqueId(), List.of());
            int idx = st.page * 45 + slot;
            if (slot < 45 && idx < list.size() && p.hasPermission("digs.admin")) {
                Model.ExhibitInfo ex = list.get(idx);
                if (removeExhibit(ex.exhibitId(), true)) {
                    Msg.send(p, "<green>已移除展品「</green><yellow>"
                            + safeName(ex.artifactId()) + "</yellow><green>」，展台已清空。</green>");
                    renderExhibits(p, st); // 原地刷新
                }
            }
            return; // 其余 shift 一律取消（上面已 setCancelled）
        }

        if (e.getClick().isShiftClick()) return; // 非 admin shift 一律取消

        if (st.mode == Mode.HALLS) {
            // I1：53 号槽 → 参观排行子 GUI（45 号槽指数书为纯展示，无点击动作）
            if (slot == 53) {
                st.mode = Mode.RANKING;
                st.page = 0;
                renderRanking(p, st);
                return;
            }
            List<String> names = hallButtons.getOrDefault(p.getUniqueId(), List.of());
            if (slot < names.size()) {
                st.mode = Mode.EXHIBITS;
                st.hall = names.get(slot);
                st.page = 0;
                renderExhibits(p, st);
            }
            return;
        }

        if (st.mode == Mode.RANKING) {
            if (slot == 48) { // 返回总览
                openOverview(p);
                return;
            }
            // Top10 条目：点击输出铭牌详情（同样计入参观）
            List<Model.ExhibitInfo> ranked = exhibitButtons.getOrDefault(p.getUniqueId(), List.of());
            if (slot < 10 && slot < ranked.size()) showPlaqueDetail(p, ranked.get(slot));
            return;
        }

        if (st.mode == Mode.WORKSHOP) {
            if (slot == 48) { // 返回展品页（st.hall 保留）
                st.mode = Mode.EXHIBITS;
                st.page = 0;
                renderExhibits(p, st);
                return;
            }
            if (slot == 45 && st.page > 0) {
                st.page--;
                renderWorkshop(p, st);
                return;
            }
            if (slot == 53) {
                List<String> ids = workshopButtons.getOrDefault(p.getUniqueId(), List.of());
                if ((st.page + 1) * 45 < ids.size()) {
                    st.page++;
                    renderWorkshop(p, st);
                }
                return;
            }
            List<String> ids = workshopButtons.getOrDefault(p.getUniqueId(), List.of());
            int wIdx = st.page * 45 + slot;
            if (slot < 45 && wIdx < ids.size()) orderMiniReplica(p, ids.get(wIdx));
            return;
        }

        // EXHIBITS 页普通点击
        if (slot == 48) { // 返回总览
            openOverview(p);
            return;
        }
        if (slot == 45 && st.page > 0) {
            st.page--;
            renderExhibits(p, st);
            return;
        }
        if (slot == 51) { // I2：进入复原件工坊
            st.mode = Mode.WORKSHOP;
            st.page = 0;
            renderWorkshop(p, st);
            return;
        }
        if (slot == 53) {
            List<Model.ExhibitInfo> list = exhibitButtons.getOrDefault(p.getUniqueId(), List.of());
            if ((st.page + 1) * 45 < list.size()) {
                st.page++;
                renderExhibits(p, st);
            }
            return;
        }
        List<Model.ExhibitInfo> list = exhibitButtons.getOrDefault(p.getUniqueId(), List.of());
        int idx = st.page * 45 + slot;
        if (slot < 45 && idx < list.size()) {
            Model.ExhibitInfo ex = list.get(idx);
            // 右键 → 兑换拓片（E3）
            if (e.getClick() == org.bukkit.event.inventory.ClickType.RIGHT) {
                exchangeReplica(p, ex);
                return;
            }
            // 左键普通点击 → 聊天输出铭牌详情 + 参观计数（E3/I1，计数存 AchieveStore 通用 KV）
            showPlaqueDetail(p, ex);
        }
    }

    /** 左键展品/排行条目：聊天输出铭牌详情 + 参观计数 +1（visit:&lt;exhibitId&gt; KV）。 */
    private void showPlaqueDetail(Player p, Model.ExhibitInfo ex) {
        long visits = -1;
        if (plugin.achieveStore() != null) {
            visits = plugin.achieveStore().bumpCounter("visit:" + ex.exhibitId());
        }
        Msg.send(p, "<gold>" + safeName(ex.artifactId()) + "</gold> "
                + ItemCodec.qualityLine(ex.repairDegree()));
        Msg.send(p, "<gray>修复者/捐献者：</gray><white>" + ex.donorName()
                + "</white><gray> · 馆：</gray><white>" + ex.hallName()
                + "</white><gray> · ID：</gray><white>" + ex.exhibitId() + "</white>"
                + (visits >= 0 ? "<gray> · 参观 </gray><white>" + visits + "</white><gray> 次</gray>" : ""));
        Msg.send(p, "<gray>想留个纪念？</gray><yellow>右键点击本展品</yellow><gray>，绿宝石 ×"
                + com.digsite.util.KitCodec.REPLICA_COST + " 兑换拓片；</gray><aqua>工坊可订做复原件</aqua><gray>。</gray>");
    }

    /**
     * 拓片兑换（E3）：绿宝石 ×{@link com.digsite.util.KitCodec#REPLICA_COST} → 该文物拓片。
     * 先精确扣款（跨槽位累计扣除）后发货；拓片不入账本，无需回滚。背包满则掉在脚下。
     */
    private void exchangeReplica(Player p, Model.ExhibitInfo ex) {
        int cost = com.digsite.util.KitCodec.REPLICA_COST;
        ItemStack[] storage = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack it : storage) {
            if (it != null && it.getType() == Material.EMERALD) have += it.getAmount();
        }
        if (have < cost) {
            Msg.send(p, "<red>绿宝石不足：</red><gray>兑换拓片需要</gray><white> 绿宝石 ×" + cost
                    + "</white><gray>，你背包里只有 </gray><yellow>" + have + "</yellow><gray> 颗。</gray>");
            return;
        }
        // 先扣：跨槽位累计扣除 cost 颗
        int remaining = cost;
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack it = storage[i];
            if (it == null || it.getType() != Material.EMERALD) continue;
            int take = Math.min(remaining, it.getAmount());
            remaining -= take;
            if (it.getAmount() <= take) {
                p.getInventory().setItem(i, null);
            } else {
                ItemStack rest = it.clone();
                rest.setAmount(it.getAmount() - take);
                p.getInventory().setItem(i, rest);
            }
        }
        // 后给：拓片纪念品（PDC replica=artifactId）
        ItemStack rp = com.digsite.util.KitCodec.replica(ex.artifactId(), safeName(ex.artifactId()));
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(rp);
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        Msg.send(p, "<green>拓片到手！</green><gray>「</gray><gold>" + safeName(ex.artifactId())
                + "</gold><gray> · 拓片」已放入背包（馆藏纪念，不可捐献）。</gray>");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof OverviewHolder) {
            e.setCancelled(true);
        }
    }

    private String safeName(String artifactId) {
        Model.ArtifactDef def = catalog.byId(artifactId);
        return def == null ? artifactId : def.name();
    }

    // ---------- 展品移除与重启补齐 ----------

    /**
     * 移除展品：回收两个展示实体（UUID 查找，无效跳过）、清展台 exhibitId、store.removeExhibit。
     * byAdmin=false 时按契约应校验操作者是捐献者本人——当前方法无操作者入参，校验为预留位。
     *
     * @return 是否移除成功
     */
    public boolean removeExhibit(String exhibitId, boolean byAdmin) {
        Model.ExhibitInfo ex = store.exhibit(exhibitId);
        if (ex == null) return false;
        if (!byAdmin) {
            // 预留：捐献者本人移除校验（需要操作者身份参数，命令层接入时补）
            return false;
        }
        // 先回收实体（store.removeExhibit 内部会清空引用展台，无需再 setPedestal）
        for (Model.HallInfo hall : store.halls()) {
            for (Model.Pedestal ped : store.pedestals(hall.name())) {
                if (exhibitId.equals(ped.exhibitId())) {
                    despawn(ped.entityUuid());
                    despawn(ped.plaqueUuid());
                }
            }
        }
        store.removeExhibit(exhibitId);
        return true;
    }

    private void despawn(UUID uuid) {
        if (uuid == null) return;
        Entity en = Bukkit.getEntity(uuid);
        if (en != null) en.remove();
    }

    /** 重启/区块卸载后展品补齐：exhibitId 非空但实体 UUID 无效 → 重建实体并更新 UUID。 */
    public void respawnMissing() {
        for (Model.HallInfo hall : store.halls()) {
            World world = Bukkit.getWorld(hall.worldName());
            if (world == null) continue; // 世界未加载：等下次启用再补
            for (Model.Pedestal ped : store.pedestals(hall.name())) {
                if (ped.exhibitId() == null) continue;
                Model.ExhibitInfo ex = store.exhibit(ped.exhibitId());
                if (ex == null) continue; // 展品档案丢失：跳过，避免幽灵台位
                boolean itemOk = ped.entityUuid() != null && Bukkit.getEntity(ped.entityUuid()) != null;
                boolean plaqueOk = ped.plaqueUuid() != null && Bukkit.getEntity(ped.plaqueUuid()) != null;
                if (itemOk && plaqueOk) continue;

                Model.ArtifactDef def = catalog.byId(ex.artifactId());
                Location loc = new Location(world, ped.x(), ped.y(), ped.z());
                UUID itemUuid = ped.entityUuid();
                UUID plaqueUuid = ped.plaqueUuid();
                if (!itemOk) {
                    // lambda 引用要求实际最终变量：先定型再进 spawn 回调
                    final ItemStack displayItem = (def != null)
                            ? codec.artifact(new Model.ArtifactData(ex.artifactId(), ex.repairDegree(),
                                    ex.donorName(), ex.donatedAt()), def)
                            : new ItemStack(defMaterial(def)); // def 为 null 时的兜底展示物
                    ItemDisplay item = world.spawn(loc, ItemDisplay.class, d -> {
                        d.setItemStack(displayItem);
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        d.setBillboard(Display.Billboard.FIXED);
                        d.setTransformation(new org.bukkit.util.Transformation(
                                new org.joml.Vector3f(0f, 0f, 0f), new org.joml.Quaternionf(),
                                new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.Quaternionf()));
                        d.setShadowRadius(0f);
                        d.setPersistent(true);
                    });
                    itemUuid = item.getUniqueId();
                }
                if (!plaqueOk) {
                    String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(ex.donatedAt()));
                    TextDisplay plaque = world.spawn(loc.clone().add(0, 0.55, 0), TextDisplay.class, d -> {
                        d.text(Msg.mm("<gold>" + (def == null ? ex.artifactId() : def.name()) + "</gold><newline>"
                                + ItemCodec.qualityLine(ex.repairDegree()) + "<newline>"
                                + "<gray>修复者：</gray><white>" + ex.donorName() + "</white><newline>"
                                + "<gray>捐献日期：</gray><white>" + date + "</white>"));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(100, 0, 0, 0));
                        d.setShadowed(true);
                        d.setPersistent(true);
                    });
                    plaqueUuid = plaque.getUniqueId();
                }
                store.setPedestal(hall.name(), new Model.Pedestal(ped.index(), ped.x(), ped.y(), ped.z(),
                        ped.exhibitId(), itemUuid, plaqueUuid));
            }
        }
    }

    /** 关停：冲刷博物馆存档。 */
    public void shutdown() {
        store.flush();
    }
}
