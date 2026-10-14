package fr.cleangang.loupgarou.game;

import fr.cleangang.loupgarou.ui.Items;
import fr.cleangang.loupgarou.ui.LgMenu;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

public final class GameListener implements Listener {
    private final Game game;

    public GameListener(Game game) {
        this.game = game;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        game.handleMove(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && game.isActive(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && game.isActive(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (game.isActive(e.getPlayer()) || Items.TAG_JOIN.equals(Items.tagOf(e.getItemDrop().getItemStack()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (game.isActive(e.getPlayer()) || Items.TAG_JOIN.equals(Items.tagOf(e.getOffHandItem()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (game.isActive(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (game.isActive(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        String tag = Items.tagOf(e.getItem());
        if (Items.TAG_JOIN.equals(tag)) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND
                    && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                game.toggleJoin(p);
            }
            return;
        }
        if (!game.isActive(p)) return;
        if (tag == null) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) e.setCancelled(true); // pas de portes, coffres...
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            game.useItem(p, tag);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof LgMenu menu) {
            e.setCancelled(true);
            if (e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize()) {
                game.handleMenuClick(menu, e.getRawSlot(), e.isRightClick());
            }
            return;
        }
        if (e.getWhoClicked() instanceof Player p && game.isActive(p)) {
            e.setCancelled(true);
            return;
        }
        // la tête de loup reste dans la barre d'action
        if (Items.TAG_JOIN.equals(Items.tagOf(e.getCurrentItem())) || Items.TAG_JOIN.equals(Items.tagOf(e.getCursor()))
                || (e.getHotbarButton() >= 0 && Items.TAG_JOIN.equals(Items.tagOf(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof LgMenu) {
            e.setCancelled(true);
            return;
        }
        if (e.getWhoClicked() instanceof Player p && game.isActive(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof LgMenu && e.getPlayer() instanceof Player p) {
            game.handleMenuClosed(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        game.handleChat(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        game.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        game.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        game.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        game.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(item -> Items.TAG_JOIN.equals(Items.tagOf(item)));
    }
}
