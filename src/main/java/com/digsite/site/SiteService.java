package com.digsite.site;

import com.digsite.DigSiteConfig;
import com.digsite.DigSitePlugin;
import com.digsite.catalog.ArtifactCatalog;
import com.digsite.codex.CodexService;
import com.digsite.model.Model;
import com.digsite.store.PlayerStore;
import com.digsite.store.SitesStore;
import com.digsite.util.ItemCodec;
import com.digsite.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 遗址生成与挖掘状态机（B 部分）。
 * 职责：sites.yml 模板加载 / 随机与定点落遗址（含偏好水边落点）/ 壳层建造（D4 通用化）/
 *       刷挖状态机（手速惩罚、分层削除、碎片掉落、传说遗物 BossBar）/ 遗址保护查询 / 盗墓贼与 BossBar 生命周期。
 * 所有世界编辑均由 Bukkit 主线程调度保证（事件与定时任务都在主线程触发）。
 */
public final class SiteService {

    /** sites.yml 单档模板。shell 为可选外圈壳层方块（null = 无壳）；preferWater 为落点偏好水边。 */
    private record Tier(String id, String display, String desc,
                        int size, int layers, Material top, Material fill,
                        Material shell, boolean preferWater) {}

    /** 方块坐标 → (遗址 id, cells 下标)。cells 下标 = (dz+h)*size + (dx+h)，dz 外层 dx 内层行优先。 */
    private record CellRef(String siteId, int index) {}

    private final DigSitePlugin plugin;
    private final SitesStore store;
    private final ArtifactCatalog catalog;
    private final ItemCodec codec;
    private final CodexService codexService;
    private final PlayerStore playerStore;
    private final DigSiteConfig config;

    /** 档位模板（key = tier id，如 pit/tomb/palace）。 */
    private final Map<String, Tier> tiers = new LinkedHashMap<>();

    /** 内存索引：world;x;y;z → 所属格子。重启时从 store 现有 active 遗址重建。 */
    private final Map<String, CellRef> blockIndex = new ConcurrentHashMap<>();
    /** 每格上次刷挖 tick（key = siteId;dx;dz）。 */
    private final Map<String, Long> lastBrushTick = new ConcurrentHashMap<>();
    /** 每格本层累计刷挖次数。 */
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    /** 每遗址最后刷挖者名（结算归属）。 */
    private final Map<String, String> lastBrusher = new ConcurrentHashMap<>();

    /** D3：传说遗物 BossBar（siteId → bar）。 */
    private final SiteBossBar bossBar;
    /** D2：盗墓贼事件（palace/glacier 夜间骚扰）。 */
    private final SiteGuard guard;
    /** H2：挖掘事件池（层完成时 roll，概率来自 sites.yml events 段）。 */
    private final SiteEvents siteEvents;
    /** H4：考古笔记 PDC 标记（com.digsite:site_note = 遗址 id）。 */
    private final NamespacedKey noteKey;

    /** H3：盗墓头目配置（sites.yml guard 段，loadTiers 同源加载；缺段用缺省）。 */
    private SiteGuard.BossCfg guardCfg = new SiteGuard.BossCfg(0.15, 40,
            List.of("sharpness", "protection", "efficiency", "unbreaking", "fortune"));
    /** H2：事件概率表（sites.yml events 段原序；loadTiers 填充，构造 SiteEvents 时注入）。 */
    private Map<String, Double> eventProbs = Map.of();

    public SiteService(DigSitePlugin plugin, SitesStore store, ArtifactCatalog catalog, ItemCodec codec,
                       CodexService codexService, PlayerStore playerStore, DigSiteConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.catalog = catalog;
        this.codec = codec;
        this.codexService = codexService;
        this.playerStore = playerStore;
        this.config = config;
        this.noteKey = new NamespacedKey(plugin, "site_note");
        saveDefaultSites();
        loadTiers();
        rebuildIndex();
        this.bossBar = new SiteBossBar(plugin, this);
        this.siteEvents = new SiteEvents(plugin, eventProbs);
        this.guard = new SiteGuard(plugin, this, guardCfg);
        this.guard.start();
    }

    // ---------------------------------------------------------------- 模板加载

    /** 主类没帮存 sites.yml，这里自己落一份默认模板到 dataFolder。 */
    private void saveDefaultSites() {
        File f = new File(plugin.getDataFolder(), "sites.yml");
        if (!f.exists()) plugin.saveResource("sites.yml", false);
    }

    private void loadTiers() {
        File f = new File(plugin.getDataFolder(), "sites.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("tiers");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection t = root.getConfigurationSection(id);
                if (t == null) continue;
                Material top = match(t.getString("top-block"), Material.SUSPICIOUS_SAND);
                Material fill = match(t.getString("fill-block"), Material.SAND);
                // D4 壳层通用化：可选 shell-block，非空则建场时包壳（非法材质名视为无壳）
                String shellName = t.getString("shell-block");
                Material shell = (shellName == null || shellName.isBlank()) ? null : match(shellName, null);
                int size = Math.max(3, t.getInt("size", 5));
                if (size % 2 == 0) size++; // 强制奇数，保证有中心
                int layers = Math.max(1, t.getInt("layers", 2));
                tiers.put(id, new Tier(id,
                        t.getString("display-name", id),
                        t.getString("description", ""),
                        size, layers, top, fill,
                        shell, t.getBoolean("prefer-water", false)));
            }
        }
        if (tiers.isEmpty()) {
            // 兜底：sites.yml 被删空也不至于无法开局
            tiers.put("pit", new Tier("pit", "小型灰坑", "浅浅两层的先民灰坑。", 5, 2,
                    Material.SUSPICIOUS_SAND, Material.SAND, null, false));
            tiers.put("tomb", new Tier("tomb", "贵族墓", "三层夯土下的随葬珍品。", 7, 3,
                    Material.SUSPICIOUS_GRAVEL, Material.GRAVEL, null, false));
            tiers.put("palace", new Tier("palace", "宫殿基址", "规模宏大的四层殿基。", 9, 4,
                    Material.SUSPICIOUS_GRAVEL, Material.SAND, null, false));
            plugin.getLogger().warning("sites.yml 缺失或为空，已启用内置三档默认模板。");
        }
        // H2：事件概率（全局段，所有档位共用；按 yml 声明顺序保留，roll 时首个命中即触发）
        Map<String, Double> ev = new LinkedHashMap<>();
        ConfigurationSection es = yml.getConfigurationSection("events");
        if (es != null) {
            for (String k : es.getKeys(false)) {
                ev.put(k, Math.max(0.0D, es.getDouble(k, 0.0D)));
            }
        }
        this.eventProbs = ev;
        // H3：盗墓头目配置（guard 段；缺段/缺项用上方字段初始化的缺省值兜底）
        ConfigurationSection gs = yml.getConfigurationSection("guard");
        if (gs != null) {
            List<String> enchants = new ArrayList<>();
            for (String s : gs.getStringList("boss-enchants")) {
                if (s != null && !s.isBlank()) enchants.add(s.trim());
            }
            this.guardCfg = new SiteGuard.BossCfg(
                    Math.max(0.0D, Math.min(1.0D, gs.getDouble("boss-chance", guardCfg.bossChance()))),
                    Math.max(1, gs.getInt("boss-max-health", guardCfg.bossMaxHealth())),
                    enchants.isEmpty() ? guardCfg.bossEnchants() : enchants);
        }
    }

    private static Material match(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? def : m;
    }

    // ---------------------------------------------------------------- 落遗址

    /** 主世界随机落点：受出生点距离 / 现存遗址间距约束；全部尝试失败返回 null。 */
    public Model.SiteInfo spawnRandom(String tier) {
        Tier t = tiers.get(tier);
        if (t == null) return null;
        if (activeSites().size() >= Math.max(1, config.siteMaxActive())) return null;
        World w = Bukkit.getWorlds().get(0);
        if (w == null) return null;
        Location spawn = w.getSpawnLocation();
        Random rnd = plugin.random();
        int attempts = Math.max(1, config.spawnAttempts());
        int minFromSpawn = Math.max(0, config.siteMinDistanceFromSpawn());
        // D1 落点特性「偏好水边」：以 2 倍尝试次数优先找周围 3 格含水的候选点，找不到退回普通点
        boolean preferWater = t.preferWater();
        int totalTries = preferWater ? attempts * 2 : attempts;
        int fallbackX = Integer.MIN_VALUE, fallbackZ = 0;
        for (int i = 0; i < totalTries; i++) {
            double dist = minFromSpawn + rnd.nextInt(1500);
            double ang = rnd.nextDouble() * Math.PI * 2;
            int x = spawn.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = spawn.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            double dSpawn = Math.hypot(x - spawn.getX(), z - spawn.getZ());
            if (dSpawn < minFromSpawn) continue;
            if (tooCloseToExisting(x, z)) continue;
            if (preferWater) {
                if (nearWater(w, x, z, 3)) {
                    Model.SiteInfo s = buildSite(t, w, x, z);
                    if (s == null) continue; // 高度越界等，换下一个落点
                    announceSpawn(t, w, x, z, dSpawn);
                    return s;
                }
                if (fallbackX == Integer.MIN_VALUE) { fallbackX = x; fallbackZ = z; } // 留作无水兜底
                continue;
            }
            Model.SiteInfo s = buildSite(t, w, x, z);
            if (s == null) continue; // 高度越界等，换下一个落点
            announceSpawn(t, w, x, z, dSpawn);
            return s;
        }
        if (preferWater && fallbackX != Integer.MIN_VALUE) {
            Model.SiteInfo s = buildSite(t, w, fallbackX, fallbackZ);
            if (s != null) {
                announceSpawn(t, w, fallbackX, fallbackZ,
                        Math.hypot(fallbackX - spawn.getX(), fallbackZ - spawn.getZ()));
                return s;
            }
        }
        return null;
    }

    /** 落点发现公告（原逻辑抽出复用：水边优先 / 无水兜底两条路径共用）。 */
    private void announceSpawn(Tier t, World w, int x, int z, double dSpawn) {
        Location spawn = w.getSpawnLocation();
        plugin.announcer().announce("<gold>野外发现新遗址</gold> <white>「" + t.display() + "」</white>"
                + "<gray>（" + t.desc() + "）位于出生点</gray> <yellow>" + compass(x - spawn.getX(), z - spawn.getZ())
                + "</yellow> <gray>约</gray> <yellow>" + (int) dSpawn + "</yellow> <gray>格（x=" + x + ", z=" + z + "）。</gray>");
    }

    /** 视作「水」的方块：水 / 海草 / 海带 / 气泡柱 / 各种冰。 */
    private static final Set<Material> WATERISH = EnumSet.of(
            Material.WATER, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP,
            Material.KELP_PLANT, Material.BUBBLE_COLUMN,
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    /** 落点偏好：周围 radius 格内任意列的最高方块含水系材质 → 视作水边。 */
    private static boolean nearWater(World w, int x, int z, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (WATERISH.contains(w.getHighestBlockAt(x + dx, z + dz).getType())) return true;
            }
        }
        return false;
    }

    /** 管理员定点落遗址：不走距离约束，但仍受 max-active 约束；失败返回 null。 */
    public Model.SiteInfo spawnAt(String tier, Location center) {
        Tier t = tiers.get(tier);
        if (t == null || center == null || center.getWorld() == null) return null;
        if (activeSites().size() >= Math.max(1, config.siteMaxActive())) return null;
        Model.SiteInfo s = buildSite(t, center.getWorld(), center.getBlockX(), center.getBlockZ());
        if (s != null) {
            plugin.announcer().announce("<gold>考古队开启新遗址</gold> <white>「" + t.display() + "」</white>"
                    + "<gray>（" + t.desc() + "）位于</gray> <yellow>" + s.worldName()
                    + " x=" + s.cx() + ", z=" + s.cz() + "</yellow> <gray>。</gray>");
        }
        return s;
    }

    /** 距全部现存遗址（含已关闭的残迹）过近则拒绝落点。 */
    private boolean tooCloseToExisting(int x, int z) {
        int min = Math.max(0, config.siteMinDistanceBetween());
        for (Model.SiteInfo s : store.all()) {
            if (Math.hypot(x - s.cx(), z - s.cz()) < min) return true;
        }
        return false;
    }

    /**
     * 建场地：扫描每列地表取众数作基准 y；每列从 cy 向下叠 layers 层（顶=SUSPICIOUS，下=fill），
     * 底座最下一层 STONE；柱顶上方清 3 格 AIR。成功返回已落库的 SiteInfo。
     */
    private Model.SiteInfo buildSite(Tier t, World w, int cx, int cz) {
        int h = t.size() / 2;
        // 1) 每列地表 y（最高非空气方块 +1），取众数
        Map<Integer, Integer> freq = new HashMap<>();
        for (int dz = -h; dz <= h; dz++) {
            for (int dx = -h; dx <= h; dx++) {
                int y = w.getHighestBlockYAt(cx + dx, cz + dz) + 1;
                freq.merge(y, 1, Integer::sum);
            }
        }
        int cy = Integer.MIN_VALUE, bestN = -1;
        for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
            int y = e.getKey(), n = e.getValue();
            if (n > bestN || (n == bestN && y < cy)) { cy = y; bestN = n; }
        }
        // 2) 高度边界：柱顶上方留 3 格，柱底含石座留在世界内
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        if (cy + 3 >= maxY) cy = maxY - 4;
        if (cy - t.layers() - 1 < minY || cy < minY) return null;

        // 3) 预加载覆盖到的 chunk
        int minCX = (cx - h) >> 4, maxCX = (cx + h) >> 4;
        int minCZ = (cz - h) >> 4, maxCZ = (cz + h) >> 4;
        for (int ccx = minCX; ccx <= maxCX; ccx++) {
            for (int ccz = minCZ; ccz <= maxCZ; ccz++) {
                Chunk c = w.getChunkAt(ccx, ccz);
                if (!c.isLoaded()) c.load();
            }
        }
        // 4) 建格子柱
        for (int dz = -h; dz <= h; dz++) {
            for (int dx = -h; dx <= h; dx++) {
                buildColumn(t, w, cx + dx, cy, cz + dz);
            }
        }
        // 5) D4 壳层（可选）：格子柱外圈包壳，不属格子层、不进保护索引
        if (t.shell() != null) buildShell(t, w, cx, cy, cz);
        // 6) 落库 + 索引 + 公告
        String id = newSiteId();
        Model.SiteInfo site = new Model.SiteInfo(id, t.id(), w.getName(), cx, cy, cz, t.size(), t.layers(),
                System.currentTimeMillis(), false);
        List<Model.SiteCell> cells = new ArrayList<>(t.size() * t.size());
        for (int dz = -h; dz <= h; dz++) {
            for (int dx = -h; dx <= h; dx++) {
                cells.add(new Model.SiteCell(dx, dz, t.layers(), 100, false));
            }
        }
        store.put(site);
        store.setCells(id, cells);
        indexSite(site);
        return site;
    }

    /** 单列：顶格 top，向下 fill*(layers-1)，底座一层 STONE，顶上方清 3 格 AIR。 */
    private void buildColumn(Tier t, World w, int x, int cy, int z) {
        w.getBlockAt(x, cy, z).setType(t.top());
        for (int i = 1; i < t.layers(); i++) {
            w.getBlockAt(x, cy - i, z).setType(t.fill());
        }
        w.getBlockAt(x, cy - t.layers(), z).setType(Material.STONE);
        for (int i = 1; i <= 3; i++) {
            w.getBlockAt(x, cy + i, z).setType(Material.AIR);
        }
    }

    /**
     * D4 壳层：在格子柱边格外一圈（半径 h+1 的方环，含四角）就地放置 shell 方块，
     * 垂直覆盖 y ∈ [cy-layers, cy+1]（高度 = layers + 2），完整罩住柱体。
     * 壳方块不编入 blockIndex → 保护查询天然放行玩家挖壳；挖掉不回滚（无存档数据）。
     */
    private void buildShell(Tier t, World w, int cx, int cy, int cz) {
        int h = t.size() / 2;
        int r = h + 1; // 边格外一圈
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (Math.abs(dx) <= h && Math.abs(dz) <= h) continue; // 只包外圈，不碰格子柱
                for (int y = cy - t.layers(); y <= cy + 1; y++) {
                    w.getBlockAt(cx + dx, y, cz + dz).setType(t.shell());
                }
            }
        }
    }

    private String newSiteId() {
        String base = "S" + Long.toString(System.currentTimeMillis(), Character.MAX_RADIX);
        String id = base;
        while (store.get(id) != null) id = base + "-" + (100 + plugin.random().nextInt(900));
        return id;
    }

    // ---------------------------------------------------------------- 挖掘状态机

    /**
     * 刷挖状态机入口（事件已在监听器初步过滤，此处最终裁决）。
     * 无论格子状态如何都取消事件（拦原版刷子战利品）；有效刷累计 hits，满 hitsPerLayer 削一层。
     */
    public void handleBrush(Player player, Block block, PlayerInteractEvent event) {
        event.setCancelled(true); // 拦原版刷子战利品 / 刷子进度
        CellRef ref = blockIndex.get(key(block));
        if (ref == null) return;
        Model.SiteInfo site = store.get(ref.siteId());
        if (site == null || site.closed()) {
            blockIndex.remove(key(block)); // 残留索引自愈
            return;
        }
        List<Model.SiteCell> cells = store.cells(site.id());
        if (ref.index() < 0 || ref.index() >= cells.size()) return;
        Model.SiteCell cell = cells.get(ref.index());
        if (cell.done() || cell.layer() <= 0) return; // 已挖完（含柱底石头），静默
        Tier tier = tiers.get(site.tier());
        if (tier == null) return;

        String cellKey = site.id() + ";" + cell.dx() + ";" + cell.dz();
        int nowTick = Bukkit.getCurrentTick();
        Long last = lastBrushTick.get(cellKey);
        int integrity = cell.integrity();

        // 手速惩罚：安全间隔内再刷 → integrity 受损，但仍算一次有效刷
        if (last != null && nowTick - last < Math.max(0, config.brushCooldownTicks())) {
            integrity = Math.max(10, integrity - Math.max(1, config.hastyDamage()));
            playerStore.bumpHasty(player);
            Msg.send(player, "<red>慢一点，土层在受损</red> <gray>(本格成色 " + integrity + ")</gray>");
        }
        lastBrushTick.put(cellKey, (long) nowTick);
        lastBrusher.put(site.id(), player.getName());

        // H2 时来运转：本格若有待生效的 lucky 标记，则本层 hits 阈值减半（consume 即销毁，一次生效）
        int need = Math.max(1, config.hitsPerLayer());
        if (siteEvents.consumeLucky(cellKey)) {
            need = Math.max(1, need / 2);
        }
        int hitCount = hits.merge(cellKey, 1, Integer::sum);
        if (hitCount >= need) {
            hits.remove(cellKey);
            completeLayer(player, site, cell, integrity, true);
        } else {
            store.updateCell(site.id(), new Model.SiteCell(cell.dx(), cell.dz(), cell.layer(), integrity, false));
        }
    }

    /** 管理员破坏：允许并同步状态 —— 视为一次完整层清除（不 roll 掉落）。 */
    public void handleAdminBreak(Player player, Block block) {
        CellRef ref = blockIndex.get(key(block));
        if (ref == null) return;
        Model.SiteInfo site = store.get(ref.siteId());
        if (site == null || site.closed()) return;
        List<Model.SiteCell> cells = store.cells(site.id());
        if (ref.index() < 0 || ref.index() >= cells.size()) return;
        Model.SiteCell cell = cells.get(ref.index());
        if (cell.done() || cell.layer() <= 0) {
            Msg.send(player, "<gray>该格已清理完毕。</gray>");
            return;
        }
        String cellKey = site.id() + ";" + cell.dx() + ";" + cell.dz();
        hits.remove(cellKey);
        lastBrusher.put(site.id(), player.getName());
        Model.SiteCell updated = completeLayer(player, site, cell, cell.integrity(), false);
        Msg.send(player, "<gray>已同步挖掘状态：第</gray> <white>" + (site.layers() - updated.layer())
                + "/" + site.layers() + "</white> <gray>层清除。</gray>");
    }

    /**
     * 削掉当前顶层一格：粒子 + 声音 → 顶方块变 AIR 露出下层（layer 将到 0 时即挖穿）。
     * 返回写库后的新 cell。rollDrop=true 时按 tier roll 碎片掉落。
     */
    private Model.SiteCell completeLayer(Player player, Model.SiteInfo site, Model.SiteCell cell,
                                         int integrity, boolean rollDrop) {
        World w = Bukkit.getWorld(site.worldName());
        int layers = Math.max(1, site.layers());
        int kOld = cell.layer();
        int kNew = Math.max(0, kOld - 1);
        // cell.layer = k 时顶块 y = cy - (layers - k)
        int yTop = site.cy() - (layers - kOld);
        Block top = w.getBlockAt(site.cx() + cell.dx(), yTop, site.cz() + cell.dz());
        Location center = top.getLocation().add(0.5, 0.5, 0.5);
        if (top.getType() != Material.AIR) {
            BlockData bd = top.getBlockData();
            w.spawnParticle(Particle.BLOCK, center, 30, 0.3, 0.3, 0.3, 0.1, bd);
        }
        w.playSound(top.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 0.9f, 1.0f);
        top.setType(Material.AIR);

        boolean done = kNew <= 0;
        Model.SiteCell updated = new Model.SiteCell(cell.dx(), cell.dz(), kNew, integrity, done);
        store.updateCell(site.id(), updated);
        if (rollDrop) {
            rollDrop(player, site, w, top.getLocation(), integrity);
            // H2 挖掘事件池：与 roll 掉落同点触发（每层完成时 roll 一次，不叠加、可无事件）。
            // extraDrop 通道只负责把物品落到层顶上方（与 rollDrop 落点口径一致），计数由事件自己负责。
            Location dropAt = top.getLocation();
            Consumer<ItemStack> extraDrop = item -> w.dropItemNaturally(dropAt.clone().add(0.5, 1.0, 0.5), item);
            siteEvents.onLayerComplete(player, site, cell, extraDrop);
        }
        if (done) {
            bossBar.update(site.id()); // D3：格子挖穿 → BossBar 进度刷新（handleBrush/handleAdminBreak 共用此点）
            maybeFinishSite(site);
        }
        return updated;
    }

    /** 每层完成 roll 掉落：缺哪片随机，碎片成色 = clamp(cell.integrity + rand(0..20), 10, 100)。 */
    private void rollDrop(Player player, Model.SiteInfo site, World w, Location at, int integrity) {
        Model.ArtifactDef def = catalog.roll(plugin.random(), site.tier());
        if (def == null || def.pieces() <= 0) return;
        Random rnd = plugin.random();
        int piece = rnd.nextInt(def.pieces());
        int fragIntegrity = Math.max(10, Math.min(100, integrity + rnd.nextInt(21)));
        ItemStack frag = codec.fragment(new Model.FragmentData(def.id(), piece, fragIntegrity, site.id()),
                def.name(), def.pieces());
        w.dropItemNaturally(at.clone().add(0.5, 1.0, 0.5), frag);
        playerStore.bumpFragments(player, 1);
        // D3：roll 到 legendary → 全服 urgent 公告 + 该遗址挂 BossBar
        if ("legendary".equalsIgnoreCase(def.rarity())) {
            plugin.announcer().announceUrgent("<light_purple>传说遗物现世！</light_purple> <white>" + def.name()
                    + "</white> <gray>出土于遗址</gray> <yellow>「" + tierDisplay(site.tier()) + "」" + site.id()
                    + "</yellow> <gray>，剩余发掘进度已挂 BossBar。</gray>");
            bossBar.show(site.id(), def.name());
        }
    }

    /** 全格 done → 关场结算：关库 + 图鉴 + 计数 + 全服庆祝公告。 */
    private void maybeFinishSite(Model.SiteInfo site) {
        for (Model.SiteCell c : store.cells(site.id())) {
            if (!c.done()) return;
        }
        String name = lastBrusher.getOrDefault(site.id(), "无名考古队");
        store.close(site.id());
        clearIndex(site.id());
        cleanupCellKeys(site.id());
        lastBrusher.remove(site.id());
        bossBar.hide(site.id());   // D3 泄漏红线：关场必撤 Bar
        guard.clearSite(site.id()); // D2：关场顺带清掉该遗址名下盗墓贼
        codexService.onSiteCleared(site.id(), site.tier(), name);
        playerStore.bumpSitesCleared(name);
        dropSiteNote(site, name); // H4：考古笔记（WRITABLE_BOOK + PDC，掉在遗址中心）
        plugin.announcer().announceUrgent("<gold>遗址完整发掘！</gold> <white>「" + tierDisplay(site.tier())
                + "」" + site.id() + "</white> <yellow>全部格子清理完毕，向 " + name + " 及考古队致敬！</yellow>");
    }

    /**
     * H4 考古笔记：遗址完整发掘时，在遗址中心掉落一本「考古笔记」。
     * WRITABLE_BOOK（原版可写书，右键即原生编辑行为，不做额外交互）+ PDC com.digsite:site_note = 遗址 id；
     * Lore 记载：档位 / 编号 / 清剿者 / 日期 / 一句话氛围文案（取档位 description）。
     */
    private void dropSiteNote(Model.SiteInfo site, String clearer) {
        World w = Bukkit.getWorld(site.worldName());
        if (w == null) return;
        Tier t = tiers.get(site.tier());
        String flavor = (t == null || t.desc().isBlank()) ? "此处的地层已被完整翻开。" : t.desc();
        ItemStack note = new ItemStack(Material.WRITABLE_BOOK);
        var meta = note.getItemMeta();
        meta.displayName(Msg.mm("<gold>考古笔记</gold> <gray>·</gray> <yellow>" + site.id() + "</yellow>"));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Msg.mm("<gray>遗址档位：</gray><white>" + tierDisplay(site.tier()) + "</white>"));
        lore.add(Msg.mm("<gray>遗址编号：</gray><white>" + site.id() + "</white>"));
        lore.add(Msg.mm("<gray>清剿者：</gray><white>" + clearer + "</white>"));
        lore.add(Msg.mm("<gray>日期：</gray><white>" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + "</white>"));
        lore.add(Msg.mm("<dark_gray>「" + flavor + "」</dark_gray>"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(noteKey, PersistentDataType.STRING, site.id());
        note.setItemMeta(meta);
        w.dropItemNaturally(new Location(w, site.cx() + 0.5, site.cy() + 1.0, site.cz() + 0.5), note);
    }

    // ---------------------------------------------------------------- 保护 / 查询

    /** 保护查询：该方块属于未关闭遗址的格子柱（含石座）。 */
    public boolean isSiteBlock(Block block) {
        CellRef ref = blockIndex.get(key(block));
        if (ref == null) return false;
        Model.SiteInfo s = store.get(ref.siteId());
        if (s == null || s.closed()) {
            blockIndex.remove(key(block)); // 残留索引自愈
            return false;
        }
        return true;
    }

    public List<Model.SiteInfo> activeSites() {
        return new ArrayList<>(store.allActive());
    }

    public Model.SiteInfo byId(String siteId) {
        return siteId == null ? null : store.get(siteId);
    }

    /** 关闭遗址：标 closed + 清索引 + 撤 BossBar + 清盗墓贼 + 公告（不做任何奖励结算）。 */
    public void closeSite(String siteId, String reason) {
        Model.SiteInfo s = store.get(siteId);
        if (s == null || s.closed()) return;
        store.close(siteId);
        clearIndex(siteId);
        cleanupCellKeys(siteId);
        lastBrusher.remove(siteId);
        bossBar.hide(siteId);      // D3 泄漏红线：关场必撤 Bar
        guard.clearSite(siteId);   // D2：关场清掉该遗址名下盗墓贼
        plugin.announcer().announce("<gray>遗址</gray> <white>「" + tierDisplay(s.tier()) + "」" + siteId
                + "</white> <gray>" + (reason == null || reason.isBlank() ? "已关闭" : reason) + "。</gray>");
    }

    public List<String> tierNames() {
        return new ArrayList<>(tiers.keySet());
    }

    // ---------------------------------------------------------------- BossBar 进度查询（包内供 SiteBossBar 调用）

    /** D3：遗址格子总数（BossBar 进度分母）。 */
    int totalCells(String siteId) {
        return store.cells(siteId).size();
    }

    /** D3：剩余未完成格数（BossBar 进度分子）。 */
    int remainingCells(String siteId) {
        int n = 0;
        for (Model.SiteCell c : store.cells(siteId)) {
            if (!c.done()) n++;
        }
        return n;
    }

    public void shutdown() {
        guard.shutdown();       // D2：撤任务 + 清场（不落盘，直接移除在场盗墓贼）
        bossBar.shutdownAll();  // D3 泄漏红线：disable 时 removeAllBar，map 全清
        store.flush();
    }

    // ---------------------------------------------------------------- 索引维护

    /** 把遗址整根格子柱（cy 顶块 → cy-layers 石座）编入索引。 */
    private void indexSite(Model.SiteInfo site) {
        int h = site.size() / 2;
        for (int dz = -h; dz <= h; dz++) {
            for (int dx = -h; dx <= h; dx++) {
                int idx = (dz + h) * site.size() + (dx + h);
                CellRef ref = new CellRef(site.id(), idx);
                for (int y = site.cy(); y >= site.cy() - site.layers(); y--) {
                    blockIndex.put(site.worldName() + ";" + (site.cx() + dx) + ";" + y + ";" + (site.cz() + dz), ref);
                }
            }
        }
    }

    private void clearIndex(String siteId) {
        blockIndex.values().removeIf(ref -> ref.siteId().equals(siteId));
    }

    private void cleanupCellKeys(String siteId) {
        String prefix = siteId + ";";
        lastBrushTick.keySet().removeIf(k -> k.startsWith(prefix));
        hits.keySet().removeIf(k -> k.startsWith(prefix));
        siteEvents.clearSite(siteId); // H2：顺手清掉该遗址名下的 lucky 标记
    }

    /** 重启恢复：从 store 现有 active 遗址重建方块索引，保证跨重启仍可挖。 */
    private void rebuildIndex() {
        for (Model.SiteInfo s : store.allActive()) {
            if (store.cells(s.id()).isEmpty()) continue; // 缺格数据的残档不索引，避免误保护
            indexSite(s);
        }
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    // ---------------------------------------------------------------- 文案辅助

    private String tierDisplay(String tierId) {
        Tier t = tiers.get(tierId);
        return t == null ? tierId : t.display();
    }

    /** 8 方位罗盘（Minecraft：北 = -Z，东 = +X）。 */
    private static String compass(double dx, double dz) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        int idx = (int) Math.floor((((ang + 22.5) % 360 + 360) % 360) / 45.0);
        return names[idx % 8];
    }
}
