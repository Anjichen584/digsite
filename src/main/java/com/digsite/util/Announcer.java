package com.digsite.util;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 统一公告通道：队列 + 节流（announce.throttle-seconds）。
 * urgent=true 的公告（epic+ 事件）插队立即广播；普通公告按节流间隔逐条吐出。
 */
public final class Announcer {
    private final Deque<String> queue = new ArrayDeque<>();
    private final long throttleTicks;
    private BukkitTask task;
    private long lastBroadcast = Long.MIN_VALUE;

    public Announcer(long throttleSeconds) {
        this.throttleTicks = Math.max(1L, throttleSeconds * 20L);
    }

    /** 启动队列泵（1s 一 tick，空队列空转开销可忽略）。 */
    public void start(org.bukkit.plugin.Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        pump(); // 残余公告冲刷
        queue.clear();
    }

    /** 普通公告：入队节流。 */
    public void announce(String mini) {
        queue.addLast(mini);
    }

    /** 紧急公告：立即广播（仍受最小间隔 1 tick 保护）。 */
    public void announceUrgent(String mini) {
        queue.addFirst(mini);
        pump();
    }

    private void pump() {
        // 以 50ms 为一 tick 的系统时间近似节流（目的只是防刷屏，不需要精确 tick）
        long sysNow = System.currentTimeMillis() / 50L;
        if (lastBroadcast != Long.MIN_VALUE && sysNow - lastBroadcast < throttleTicks) return;
        String next = queue.pollFirst();
        if (next == null) return;
        Msg.broadcast(next);
        lastBroadcast = sysNow;
    }

    public int queued() {
        return queue.size();
    }
}
