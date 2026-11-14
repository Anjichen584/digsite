package com.digsite.site;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * 遗址维护任务（60s 一轮）：
 * 1) 落点节奏：active 未满且距上次自动落点 ≥ spawn-interval-minutes → 随机档位 spawnRandom；
 * 2) 过期风化：createdAt 超过 expire-days 的遗址 closeSite（不结算奖励）；
 * 3) 每 5 分钟冲刷 sites / players 存档。
 */
public final class SiteScheduler {

    private static final long PERIOD_TICKS = 60L * 20L; // 每 60 秒一轮
    private static final int FLUSH_EVERY_ROUNDS = 5;    // 5 轮 ≈ 5 分钟

    private final DigSitePlugin plugin;
    private final SiteService service;
    private BukkitTask task;
    /** 上次成功自动落点的时间戳（ms）。 */
    private long lastSpawnAt;
    private int flushCounter;

    public SiteScheduler(DigSitePlugin plugin, SiteService service) {
        this.plugin = plugin;
        this.service = service;
        // 兜底：取现有遗址的最大 createdAt，避免重启后立刻刷出一座新遗址
        long maxCreated = 0L;
        for (Model.SiteInfo s : plugin.sitesStore().all()) {
            if (s.createdAt() > maxCreated) maxCreated = s.createdAt();
        }
        this.lastSpawnAt = maxCreated > 0L ? maxCreated : System.currentTimeMillis();
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        // 1) 过期风化
        long expireMs = Math.max(0L, plugin.digConfig().siteExpireDays()) * 86_400_000L;
        if (expireMs > 0L) {
            List<Model.SiteInfo> actives = service.activeSites();
            for (Model.SiteInfo s : actives) {
                if (now - s.createdAt() >= expireMs) {
                    service.closeSite(s.id(), "风化关闭");
                }
            }
        }
        // 2) 落点节奏（成功才推进时钟，失败下一轮换落点重试）
        List<String> tierNames = service.tierNames();
        if (!tierNames.isEmpty()
                && service.activeSites().size() < Math.max(1, plugin.digConfig().siteMaxActive())
                && now - lastSpawnAt >= Math.max(1L, plugin.digConfig().siteSpawnIntervalMinutes()) * 60_000L) {
            String tier = tierNames.get(plugin.random().nextInt(tierNames.size()));
            if (service.spawnRandom(tier) != null) lastSpawnAt = now;
        }
        // 3) 定期冲刷存档
        if (++flushCounter >= FLUSH_EVERY_ROUNDS) {
            flushCounter = 0;
            plugin.sitesStore().flush();
            plugin.playerStore().flush();
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
