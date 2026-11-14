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

        Msg.send(p, "<green>捐献成功！</green><gray>「</gray><gold>" + def.name() + "</gold><gray>」陈列于分馆「</gray>"
                + targetHall.name() + "<gray>」展台 #</gray><white>" + (targetPed.index() + 1)
                + "</white><gray>（</gray><white>" + loc.getBlockX() + ", " + loc.getBlockY() + ", "
                + loc.getBlockZ() + "</white><gray>）</gray>");
    }

    // ---------- 总览 GUI ----------

    private enum Mode { HALLS, EXHIBITS }

    private static final class OverviewState {
        Mode mode = Mode.HALLS;
        String hall;
        int page;
    }

    private final Map<UUID, OverviewState> states = new HashMap<>();
    /** halls 页槽位 → 馆名。 */
    private final Map<UUID, List<String>> hallButtons = new HashMap<>();
    /** exhibits 页槽位 → 展品。 */
    private final Map<UUID, List<Model.ExhibitInfo>> exhibitButtons = new HashMap<>();

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
            ItemStack btn = new ItemStack(Material.CHEST);
            var meta = btn.getItemMeta();
            meta.displayName(Msg.mm("<gold>" + hall.name() + "</gold>"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Msg.mm("<gray>馆藏：</gray><white>" + occupied + "</white><gray> / 展台 </gray><white>"
                    + total + "</white><gray> 台（空 </gray><yellow>" + (total - occupied) + "</yellow><gray>）</gray>"));
            lore.add(Msg.mm("<dark_gray>点击查看本馆展品</dark_gray>"));
            meta.lore(lore);
            btn.setItemMeta(meta);
            inv.setItem(slot++, btn);
        }
        if (halls.isEmpty()) {
            inv.setItem(22, infoItem("<dark_gray>还没有分馆，/digs admin hall add <名> 落成第一座</dark_gray>"));
        }
        inv.setItem(49, infoItem("<gray>馆藏总计 </gray><white>" + store.exhibitCount() + "</white><gray> 件</gray>"));
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
        int start = st.page * 45;
        int slot = 0;
        for (int i = start; i < exhibits.size() && slot < 45; i++, slot++) {
            inv.setItem(slot, exhibitButton(exhibits.get(i)));
        }
        if (st.page > 0) inv.setItem(45, navItem(Material.ARROW, "<yellow>◀ 上一页</yellow>"));
        inv.setItem(48, navItem(Material.ARROW, "<yellow>◀ 返回总览</yellow>"));
        inv.setItem(49, infoItem("<gray>点击展品看详情；管理员 shift+点击移除展品</gray>"));
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
        lore.add(Msg.mm("<dark_gray>ID：</dark_gray><dark_green>" + ex.exhibitId() + "</dark_green>"));
        lore.add(Msg.mm("<dark_gray>点击输出铭牌详情</dark_gray>"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
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
            List<String> names = hallButtons.getOrDefault(p.getUniqueId(), List.of());
            if (slot < names.size()) {
                st.mode = Mode.EXHIBITS;
                st.hall = names.get(slot);
                st.page = 0;
                renderExhibits(p, st);
            }
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
            // 普通点击 → 聊天输出该展品铭牌详情
            Model.ExhibitInfo ex = list.get(idx);
            Msg.send(p, "<gold>" + safeName(ex.artifactId()) + "</gold> "
                    + ItemCodec.qualityLine(ex.repairDegree()));
            Msg.send(p, "<gray>修复者/捐献者：</gray><white>" + ex.donorName()
                    + "</white><gray> · 馆：</gray><white>" + ex.hallName()
                    + "</white><gray> · ID：</gray><white>" + ex.exhibitId() + "</white>");
        }
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
