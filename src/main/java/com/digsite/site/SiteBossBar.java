package com.digsite.site;

import com.digsite.DigSitePlugin;
import com.digsite.model.Model;
import com.digsite.util.Msg;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 传说遗物现世 BossBar（D3）。
 * 每条 roll 出 legendary 的活跃遗址挂一条：BarColor PURPLE，标题含文物名与遗址 id，
 * 进度 = 剩余未 done 格子数 / 总格数（格子挖穿时由 SiteService.completeLayer 调 update）。
 * 观众 = 遗址 32 格内在线玩家，每秒轻量刷新，任务随 bar 生命周期启停。
 * 泄漏红线：站点关闭（hide）/ 插件 shutdown（shutdownAll）必须 removeAll 并清空 map。
 */
public final class SiteBossBar {

    /** 观众可见半径（格）。 */
    private static final double VIEW_RANGE = 32.0;

    private final DigSitePlugin plugin;
    private final SiteService service;
    /** siteId → BossBar（主线程访问；hide/shutdownAll 同步清理）。 */
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    /** 观众刷新任务：首个 bar 挂上时启动，map 清空后取消（随 bar 生命周期启停）。 */
    private BukkitTask viewerTask;

    SiteBossBar(DigSitePlugin plugin, SiteService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 传说遗物 roll 出现时调用：挂 / 刷新该遗址 BossBar。 */
    public synchronized void show(String siteId, String artifactName) {
        if (siteId == null || artifactName == null) return;
        Model.SiteInfo site = service.byId(siteId);
        if (site == null || site.closed()) return;
        BossBar bar = bars.get(siteId);
        if (bar == null) {
            bar = Bukkit.createBossBar(legacyTitle(artifactName, siteId), BarColor.PURPLE, BarStyle.SEGMENTED_10);
            bars.put(siteId, bar);
            ensureViewerTask();
        } else {
            bar.setTitle(legacyTitle(artifactName, siteId)); // 同遗址再次 roll 传说 → 只换标题
        }
        update(siteId);
    }

    /** 格子挖穿时调用（SiteService.completeLayer）：刷新进度 = 剩余未 done 格 / 总格。 */
    public synchronized void update(String siteId) {
        BossBar bar = bars.get(siteId);
        if (bar == null) return;
        int total = service.totalCells(siteId);
        if (total <= 0) {
            hide(siteId);
            return;
        }
        double remaining = service.remainingCells(siteId);
        bar.setProgress(Math.max(0.0, Math.min(1.0, remaining / total)));
    }

    /** 站点关闭 / 结算：撤 Bar + 清全部观众。 */
    public synchronized void hide(String siteId) {
        BossBar bar = bars.remove(siteId);
        if (bar != null) bar.removeAll();
        maybeStopViewerTask();
    }

    /** 插件 shutdown（泄漏红线）：全量 removeAllBar，map 清空，刷新任务取消。 */
    public synchronized void shutdownAll() {
        for (BossBar bar : bars.values()) {
            bar.removeAll();
        }
        bars.clear();
        maybeStopViewerTask();
    }

    // ------------------------------------------------------------ 内部

    /** 每秒轻量任务：按 32 格半径增删观众 + 校准进度；站点已消失的顺手撤 Bar。 */
    private void refreshViewers() {
        // 快照迭代：hide 会改 map
        for (String siteId : List.copyOf(bars.keySet())) {
            BossBar bar = bars.get(siteId);
            if (bar == null) continue;
            Model.SiteInfo site = service.byId(siteId);
            if (site == null || site.closed()) {
                hide(siteId);
                continue;
            }
            Location center = new Location(Bukkit.getWorld(site.worldName()),
                    site.cx() + 0.5, site.cy() + 0.5, site.cz() + 0.5);
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = center.getWorld() != null
                        && p.getWorld().getName().equals(site.worldName())
                        && p.getLocation().distanceSquared(center) <= VIEW_RANGE * VIEW_RANGE;
                if (near) {
                    bar.addPlayer(p);
                } else {
                    bar.removePlayer(p);
                }
            }
        }
    }

    /** 首个 bar 挂上时启动刷新任务；map 清空后取消。 */
    private void ensureViewerTask() {
        if (viewerTask != null) return;
        viewerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshViewers, 20L, 20L);
    }

    private void maybeStopViewerTask() {
        if (viewerTask != null && bars.isEmpty()) {
            viewerTask.cancel();
            viewerTask = null;
        }
    }

    /**
     * 标题：MiniMessage → legacy § 字符串。BossBar 的 String 构造按 legacy 渲染，
     * 既保留颜色又只依赖稳定 API（不走 adventure BossBar 重载，规避编译面风险）。
     */
    private static String legacyTitle(String artifactName, String siteId) {
        return LegacyComponentSerializer.legacySection().serialize(Msg.mm(
                "<light_purple>传说遗物现世</light_purple> <white>" + artifactName
                        + "</white> <gray>· 遗址</gray> <yellow>" + siteId + "</yellow>"));
    }
}
