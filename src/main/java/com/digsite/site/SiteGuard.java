package com.digsite.site;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盗墓贼事件（D2）+ 盗墓头目（H3）。
 * 触发：tier ∈ {palace, glacier} 的活跃遗址，夜间（world.getTime ∈ [13000, 23000]）
 * 每 2 分钟掷一次 0.35 概率（写死，既有代码不动），在遗址外圈 10~16 格随机点刷 1~2 只
 * 「盗墓贼」：60% 尸壳 HUSK / 40% 掠夺者 PILLAGER，PDC 标记 com.digsite:guard_mob = 遗址 id。
 * H3 头目：每轮刷出时按 sites.yml guard.boss-chance（默认 0.15）概率把其中 1 只升级为
 * 「盗墓头目」——强制 PILLAGER + PDC guard_boss=1 + 红名 + max_health 取 guard.boss-max-health
 * （原版属性 Attribute.MAX_HEALTH，非 RPG）；刷出时全服公告「盗墓头目现身！」。
 * 行为：每秒一次把目标设为 16 格内最近非创造玩家；禁改方块（EntityChangeBlockEvent 拦截）；
 * 刷出点避开格子柱与壳层外圈；60 秒后调度兜底 remove 防堆积；每遗址同场 ≤ 4 只（头目也计数）。
 * 击退奖励：普通贼死亡（EntityDeathEvent）掉原版物资包（铁锭 0-2 / 绿宝石 0-1 / 面包 1，
 * glacier 星级铁锭上限 +1）；头目（PDC guard_boss 区分）掉落翻倍（铁锭/面包 x2）+ 绿宝石 3-6
 * + 随机原版附魔书（白名单取 sites.yml guard.boss-enchants，5 个原版附魔随机）。
 * 不落盘：重启丢失追踪（残留个体随原版规则消亡，可接受）；插件 disable 时全量移除防滞留。
 */
final class SiteGuard implements Listener {

    /**
     * H3 头目配置（sites.yml guard 段，SiteService.loadTiers 同源加载注入；缺段用代码缺省兜底）。
     * @param bossChance     每轮刷出盗墓贼时 1 只升级为头目的概率 [0,1]
     * @param bossMaxHealth  头目最大生命值（原版 Attribute.MAX_HEALTH）
     * @param bossEnchants   头目附魔书白名单（minecraft:id 小写，非法项解析时忽略）
     */
    record BossCfg(double bossChance, int bossMaxHealth, List<String> bossEnchants) {
        BossCfg {
            bossEnchants = List.copyOf(bossEnchants);
        }
    }


    /** 刷怪节奏：每 2 分钟掷一次。 */
    private static final long SPAWN_PERIOD_TICKS = 2L * 60L * 20L;
    /** 刷怪概率：写死 0.35（计划指定，暂不接入 config 体系）。 */
    private static final double SPAWN_CHANCE = 0.35;
    /** 夜间窗口（world.getTime）。 */
    private static final long NIGHT_START = 13000L, NIGHT_END = 23000L;
    /** 目标校正：每秒一次。 */
    private static final long TARGET_PERIOD_TICKS = 20L;
    /** 锁定范围：16 格。 */
    private static final double TARGET_RANGE = 16.0;
    /** 单只存活兜底：60 秒后移除，防堆积。 */
    private static final long LIFETIME_TICKS = 60L * 20L;
    /** 每遗址同场盗墓贼上限。 */
    private static final int MAX_PER_SITE = 4;
    /** 刷出点距遗址中心距离区间（10~16 格）。 */
    private static final int SPAWN_MIN_DIST = 10, SPAWN_MAX_DIST = 16;
    /** 受骚扰档位。 */
    private static final Set<String> GUARD_TIERS = Set.of("palace", "glacier");

    private final DigSitePlugin plugin;
    private final SiteService service;
    /** PDC 标记：com.digsite:guard_mob = 遗址 id。 */
    private final NamespacedKey guardKey;
    /** H3 PDC 标记：com.digsite:guard_boss = 1（盗墓头目）。 */
    private final NamespacedKey bossKey;
    /** H3 头目配置（sites.yml guard 段）。 */
    private final BossCfg cfg;
    /** 盗墓贼 uuid → 所属遗址 id（Bukkit 主线程访问）。 */
    private final Map<UUID, String> tracked = new ConcurrentHashMap<>();
    private BukkitTask spawnTask;
    private BukkitTask targetTask;

    SiteGuard(DigSitePlugin plugin, SiteService service, BossCfg cfg) {
        this.plugin = plugin;
        this.service = service;
        this.cfg = cfg;
        this.guardKey = new NamespacedKey(plugin, "guard_mob");
        this.bossKey = new NamespacedKey(plugin, "guard_boss");
    }

    /** 由 SiteService 构造完成后调用：注册监听 + 启动两个周期任务。 */
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnTick, SPAWN_PERIOD_TICKS, SPAWN_PERIOD_TICKS);
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::targetTick, TARGET_PERIOD_TICKS, TARGET_PERIOD_TICKS);
    }

    /** 插件关闭：撤任务 + 移除所有在场盗墓贼（不落盘，防滞留）。 */
    void shutdown() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (targetTask != null) {
            targetTask.cancel();
            targetTask = null;
        }
        for (UUID id : tracked.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
        tracked.clear();
    }

    /** 遗址关场 / 结算：清掉该遗址名下的盗墓贼。 */
    void clearSite(String siteId) {
        Iterator<Map.Entry<UUID, String>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, String> e = it.next();
            if (!e.getValue().equals(siteId)) continue;
            Entity en = Bukkit.getEntity(e.getKey());
            if (en != null && en.isValid()) en.remove();
            it.remove();
        }
    }

    // ------------------------------------------------------------ 刷怪

    private void spawnTick() {
        Random rnd = plugin.random();
        for (Model.SiteInfo site : service.activeSites()) {
            if (!GUARD_TIERS.contains(site.tier())) continue;
            World w = Bukkit.getWorld(site.worldName());
            if (w == null) continue;
            long time = w.getTime();
            if (time < NIGHT_START || time > NIGHT_END) continue; // 只在夜间骚扰
            if (rnd.nextDouble() >= SPAWN_CHANCE) continue;
            if (countAlive(site.id()) >= MAX_PER_SITE) continue;
            // H3：本轮是否出盗墓头目（概率取 sites.yml guard.boss-chance；命中则本波首只为头目）
            boolean bossWave = cfg.bossChance() > 0 && rnd.nextDouble() < cfg.bossChance();
            int n = 1 + rnd.nextInt(2); // 1~2 只
            int spawned = 0;
            boolean bossSpawned = false;
            for (int i = 0; i < n && countAlive(site.id()) < MAX_PER_SITE; i++) {
                boolean asBoss = bossWave && !bossSpawned;
                if (spawnOne(site, w, rnd, asBoss)) {
                    spawned++;
                    bossSpawned |= asBoss;
                }
            }
            if (bossSpawned) {
                // H3：头目刷出公告（走 Announcer 节流通道）
                plugin.announcer().announce("<dark_red>盗墓头目现身！</dark_red> <gray>遗址</gray> <yellow>"
                        + site.id() + "</yellow> <gray>夜色里传来掠夺者的号角，当心！</gray>");
            }
            if (spawned > 0) {
                plugin.getLogger().info("[SiteGuard] 盗墓贼 x" + spawned
                        + (bossSpawned ? "（含盗墓头目）" : "") + " 正在袭扰遗址 " + site.id());
            }
        }
    }

    /** 统计该遗址在场盗墓贼，顺带清掉已失效（死亡/被卸载）的追踪条目。 */
    private int countAlive(String siteId) {
        int n = 0;
        for (Map.Entry<UUID, String> e : tracked.entrySet()) {
            if (!e.getValue().equals(siteId)) continue;
            Entity en = Bukkit.getEntity(e.getKey());
            if (en == null || !en.isValid()) {
                tracked.remove(e.getKey()); // ConcurrentHashMap 迭代中 remove 安全
                continue;
            }
            n++;
        }
        return n;
    }

    /** 在遗址外圈 10~16 格随机点刷一只盗墓贼（boss=true 时升级为盗墓头目）；刷出点避开格子柱与壳层外圈。 */
    private boolean spawnOne(Model.SiteInfo site, World w, Random rnd, boolean boss) {
        int h = site.size() / 2;
        int dx = 0, dz = 0;
        boolean ok = false;
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = SPAWN_MIN_DIST + rnd.nextInt(SPAWN_MAX_DIST - SPAWN_MIN_DIST + 1);
            dx = (int) Math.round(Math.cos(ang) * dist);
            dz = (int) Math.round(Math.sin(ang) * dist);
            if (Math.abs(dx) > h + 1 || Math.abs(dz) > h + 1) { // 在格子柱/壳层外圈之外
                ok = true;
                break;
            }
        }
        if (!ok) return false; // 8 次都落进格内（几乎不可能），放弃本轮
        int x = site.cx() + dx, z = site.cz() + dz;
        int y = w.getHighestBlockYAt(x, z) + 1;
        // 头目强制 PILLAGER；普通贼 60% 尸壳 / 40% 掠夺者（既有配比不动）
        EntityType type = boss ? EntityType.PILLAGER
                : (rnd.nextDouble() < 0.6 ? EntityType.HUSK : EntityType.PILLAGER);
        Mob mob = (Mob) w.spawnEntity(new Location(w, x + 0.5, y, z + 0.5), type);
        mob.getPersistentDataContainer().set(guardKey, PersistentDataType.STRING, site.id());
        mob.setCanPickupItems(false);
        if (boss) {
            // H3 盗墓头目：PDC guard_boss=1 + 红名常显 + max_health 强化（原版属性，非 RPG）
            mob.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            var attr = mob.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(cfg.bossMaxHealth());
                mob.setHealth(Math.min(cfg.bossMaxHealth(), attr.getValue()));
            }
            mob.customName(Msg.mm("<red>盗墓头目</red>"));
            mob.setCustomNameVisible(true);
        }
        UUID id = mob.getUniqueId();
        tracked.put(id, site.id());
        // 60 秒兜底移除：未被打死的个体到期消失，防堆积
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid() && tracked.remove(id) != null) e.remove();
        }, LIFETIME_TICKS);
        return true;
    }

    // ------------------------------------------------------------ 目标与行为

    /** 每秒一次：把盗墓贼目标设为 16 格内最近的非创造玩家；范围内无人则脱战（setTarget(null)）。 */
    private void targetTick() {
        for (Map.Entry<UUID, String> e : tracked.entrySet()) {
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof Mob mob) || !mob.isValid()) {
                tracked.remove(e.getKey());
                continue;
            }
            Player best = null;
            double bestD = Double.MAX_VALUE;
            for (Player p : mob.getWorld().getPlayers()) {
                // 白名单：创造/旁观模式玩家忽略
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
                double d = p.getLocation().distanceSquared(mob.getLocation());
                if (d <= TARGET_RANGE * TARGET_RANGE && d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            mob.setTarget(best);
        }
    }

    /** 盗墓贼不许改方块（拦尸壳刨龟蛋、掠夺者各类破坏等）。 */
    @EventHandler(ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(guardKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ 击退奖励

    /**
     * 盗墓贼死亡 → 掉原版物资包：铁锭 0-2（glacier 星级上限 +1）+ 绿宝石 0-1 + 面包 1。
     * H3：PDC guard_boss=1 的盗墓头目掉落翻倍（铁锭/绿宝石/面包 x2）+ 绿宝石 3-6 + 随机原版附魔书。
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        String siteId = dead.getPersistentDataContainer().get(guardKey, PersistentDataType.STRING);
        if (siteId == null) return; // 非盗墓贼，放行原版掉落
        boolean boss = dead.getPersistentDataContainer().get(bossKey, PersistentDataType.BYTE) == (byte) 1;
        tracked.remove(dead.getUniqueId());
        Model.SiteInfo site = service.byId(siteId);
        boolean hardTier = site != null && "glacier".equals(site.tier());
        Random rnd = plugin.random();
        World w = dead.getWorld();
        Location at = dead.getLocation();
        int iron = rnd.nextInt(hardTier ? 3 : 2); // 0-2（glacier 0-3）
        int emerald = rnd.nextInt(2);             // 0-1
        int bread = 1;
        if (boss) {
            // H3 头目掉落翻倍（钳制单组上限 64）
            iron = Math.min(64, iron * 2);
            emerald = Math.min(64, emerald * 2);
            bread = 2;
            int bonusEmerald = 3 + rnd.nextInt(4); // 3-6
            w.dropItemNaturally(at, new ItemStack(Material.EMERALD, bonusEmerald));
            ItemStack book = bossBook(rnd);
            if (book != null) w.dropItemNaturally(at, book);
        }
        if (iron > 0) w.dropItemNaturally(at, new ItemStack(Material.IRON_INGOT, iron));
        if (emerald > 0) w.dropItemNaturally(at, new ItemStack(Material.EMERALD, emerald));
        w.dropItemNaturally(at, new ItemStack(Material.BREAD, bread));
    }

    /**
     * H3 头目战利品：随机原版附魔书。
     * 白名单来自 sites.yml guard.boss-enchants（minecraft:id），经 Registry.ENCHANTMENT 解析，
     * 非法/未知附魔忽略；等级在 [1, 附魔最大等级] 内随机。白名单全解析失败时返回 null（不掉书）。
     */
    private ItemStack bossBook(Random rnd) {
        List<Enchantment> pool = new ArrayList<>();
        for (String name : cfg.bossEnchants()) {
            try {
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                if (e != null) pool.add(e);
            } catch (IllegalArgumentException ignored) {
                // 非法 key：忽略该条
            }
        }
        if (pool.isEmpty()) return null;
        Enchantment ench = pool.get(rnd.nextInt(pool.size()));
        int level = 1 + rnd.nextInt(Math.max(1, ench.getMaxLevel()));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(ench, level, true); // 白名单内附魔，允许按原版等级上限入书
        meta.displayName(Msg.mm("<gold>盗墓头目的战利品</gold> <gray>(" + ench.getKey().getKey() + " "
                + level + ")</gray>"));
        book.setItemMeta(meta);
        return book;
    }
}
