package com.safeloginstg.listener;

import com.safeloginstg.auth.AuthService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public final class AuthListener implements Listener {

    private final AuthService authService;

    public AuthListener(AuthService authService) {
        this.authService = authService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        authService.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authService.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (authService.isAuthenticated(event.getPlayer())) {
            return;
        }
        String message = event.getMessage().toLowerCase();
        // Allow nothing until authenticated — logout is a dialog button.
        if (!message.startsWith("/slstg")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!authService.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !authService.isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageOthers(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !authService.isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && !authService.isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

}
