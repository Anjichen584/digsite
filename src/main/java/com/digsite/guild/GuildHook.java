package com.digsite.guild;

import java.util.UUID;

/**
 * 行会体系伪接口（预留扩展点）。
 *
 * <p>v0.3.0 起行会/委托玩法暂缓，只保留本接口约定未来接缝：
 * 实现方无需改动核心模块，即可接管玩家行为事件与头衔查询。
 * 主类持有 {@link #NOOP} 默认实现，运行时零行为。
 */
public interface GuildHook {

    /** 玩家行会头衔名；null = 无行会体系（当前默认）。 */
    default String titleOf(UUID player) {
        return null;
    }

    /** 事件钩子：碎片拾取（count=件数）。 */
    default void onFragment(UUID player, int count) {
    }

    /** 事件钩子：修复完成（count=件数）。 */
    default void onRestored(UUID player, int count) {
    }

    /** 事件钩子：捐献（count=件数）。 */
    default void onDonated(UUID player, int count) {
    }

    /** 事件钩子：遗址清剿（count=处数）。 */
    default void onSiteCleared(UUID player, int count) {
    }

    /** 默认空实现（主类当前持有）。 */
    GuildHook NOOP = new GuildHook() {
    };
}
