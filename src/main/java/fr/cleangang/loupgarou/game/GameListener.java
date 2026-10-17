package fr.cleangang.loupgarou.game;

import fr.cleangang.loupgarou.ui.HoloMenu;
import fr.cleangang.loupgarou.ui.Items;
import fr.cleangang.loupgarou.ui.LgMenu;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

public final class GameListener implements Listener {
    private final ArenaManager arenas;

    public GameListener(ArenaManager arenas) {
        this.arenas = arenas;
    }

    private boolean active(Player p) {
        return arenas.isActive(p);
    }

    /** Joueur en partie ou en train d'éditer une arène : on protège tout. */
    private boolean locked(Player p) {
        return active(p) || arenas.isEditing(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        Game g = arenas.gameOf(e.getPlayer());
        if (g != null) g.handleMove(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && active(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer()) || Items.TAG_JOIN.equals(Items.tagOf(e.getItemDrop().getItemStack()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer()) || Items.TAG_JOIN.equals(Items.tagOf(e.getOffHandItem()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        HoloMenu holo = HoloMenu.get(p);
        if (holo != null && e.getAction() != Action.PHYSICAL && holo.click(right)) {
            e.setCancelled(true);
            return;
        }
        Game spec = holo == null ? arenas.spectatorGame(p) : null;
        if (spec != null && (left || right)) {
            e.setCancelled(true);
            spec.spectatorCycle(p, right ? -1 : 1);
            return;
        }
        String tag = Items.tagOf(e.getItem());

        if (Items.TAG_JOIN.equals(tag)) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND && right) arenas.openSelector(p);
            return;
        }
        if (tag != null && tag.startsWith("edit_")) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND && (left || right)) arenas.handleEditTool(p, tag, right);
            return;
        }
        if (arenas.isEditing(p)) {
            e.setCancelled(true);
            return;
        }
        if (!active(p)) return;
        if (tag == null) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) e.setCancelled(true); // pas de portes, coffres...
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (right) {
            Game g = arenas.gameOf(p);
            if (g != null) g.useItem(p, tag);
        }
    }

    /** Clic gauche dans le vide : le joueur balance le bras. */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        HoloMenu holo = HoloMenu.get(e.getPlayer());
        if (holo != null) {
            holo.click(false);
            return;
        }
        Game g = arenas.spectatorGame(e.getPlayer());
        if (g != null) g.spectatorCycle(e.getPlayer(), 1);
    }

    /** Spectateur : shift le ferait sortir de la vue du joueur → on passe au joueur précédent. */
    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        Game g = arenas.spectatorGame(e.getPlayer());
        if (g != null && g.handleStopSpectating(e.getPlayer())) e.setCancelled(true);
    }

    /** Spectateur : il ne peut suivre que les survivants de sa partie. */
    @EventHandler
    public void onStartSpectating(PlayerStartSpectatingEntityEvent e) {
        Game g = arenas.spectatorGame(e.getPlayer());
        if (g != null && !g.allowSpectate(e.getPlayer(), e.getNewSpectatorTarget())) e.setCancelled(true);
    }

    /** Clic droit sur un joueur placé derrière le menu. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        HoloMenu holo = HoloMenu.get(e.getPlayer());
        if (holo != null && holo.click(true)) {
            e.setCancelled(true);
            return;
        }
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Game g = arenas.gameOf(e.getPlayer());
        if (g != null) g.handleSneak(e.getPlayer());
        else if (HoloMenu.isOpen(e.getPlayer())) HoloMenu.close(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof LgMenu menu) {
            e.setCancelled(true);
            if (e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize()) {
                menu.click(e.getRawSlot(), e.isRightClick());
            }
            return;
        }
        if (e.getWhoClicked() instanceof Player p && locked(p)) {
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
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Game g = arenas.gameOf(e.getPlayer());
        if (g != null) {
            g.handleChat(e);
            return;
        }
        Game o = arenas.outsiderGame(e.getPlayer());
        if (o != null) o.handleOutsiderChat(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        arenas.handleQuit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        arenas.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        arenas.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        arenas.refreshJoinItemLater(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(item -> Items.TAG_JOIN.equals(Items.tagOf(item)));
    }
}
