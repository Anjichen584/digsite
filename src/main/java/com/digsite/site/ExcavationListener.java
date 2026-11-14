package com.digsite.site;

import com.digsite.DigSitePlugin;
import com.digsite.store.PlayerStore;
import com.digsite.util.Msg;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 挖掘事件监听：刷挖转交状态机、考古区破坏保护（admin 例外并同步状态）、爆炸免疫。
 * PlayerInteractEvent / BlockBreakEvent / 爆炸事件均由 Bukkit 保证在主线程触发。
 */
public final class ExcavationListener implements Listener {

    private final DigSitePlugin plugin;
    private final SiteService siteService;
    private final PlayerStore playerStore;

    public ExcavationListener(DigSitePlugin plugin, SiteService siteService, PlayerStore playerStore) {
        this.plugin = plugin;
        this.siteService = siteService;
        this.playerStore = playerStore;
    }

    /** 手持 BRUSH 右键遗址方块 → 转交挖掘状态机（内部会 cancel 拦原版战利品）。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BRUSH) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !siteService.isSiteBlock(clicked)) return;
        Player player = event.getPlayer();
        siteService.handleBrush(player, clicked, event);
    }

    /** 考古区禁徒手拆：普通玩家取消；admin 允许并同步格子状态。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!siteService.isSiteBlock(block)) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("digs.admin")) {
            event.setCancelled(true);
            Msg.send(player, "<red>这里是考古区，去报备再动土。</red> <gray>手持刷子即可参与发掘。</gray>");
            return;
        }
        // admin 允许并同步状态：视为一次完整层清除，插件自己削方块（cancel 掉原版破坏避免掉落错乱）
        event.setCancelled(true);
        siteService.handleAdminBreak(player, block);
    }

    /** 爆炸不毁遗址：把遗址柱从爆炸清单里剔除。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isSiteBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isSiteBlock);
    }

    private boolean isSiteBlock(Block block) {
        return siteService.isSiteBlock(block);
    }
}
