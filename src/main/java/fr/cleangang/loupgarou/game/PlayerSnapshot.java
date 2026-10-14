package fr.cleangang.loupgarou.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;

/** Sauvegarde l'état d'un joueur avant la partie pour tout lui rendre à la fin (inventaire survie, etc.). */
public final class PlayerSnapshot {
    private final Location location;
    private final GameMode gameMode;
    private final ItemStack[] contents;
    private final double health;
    private final int food;
    private final float saturation;
    private final int level;
    private final float exp;
    private final Collection<PotionEffect> effects;
    private final boolean allowFlight;

    public PlayerSnapshot(Player p) {
        this.location = p.getLocation().clone();
        this.gameMode = p.getGameMode();
        ItemStack[] raw = p.getInventory().getContents();
        this.contents = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            contents[i] = raw[i] == null ? null : raw[i].clone();
        }
        this.health = p.getHealth();
        this.food = p.getFoodLevel();
        this.saturation = p.getSaturation();
        this.level = p.getLevel();
        this.exp = p.getExp();
        this.effects = new ArrayList<>(p.getActivePotionEffects());
        this.allowFlight = p.getAllowFlight();
    }

    public void restore(Player p) {
        p.closeInventory();
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.resetPlayerTime();
        p.setGameMode(gameMode);
        p.teleport(location);
        p.getInventory().clear();
        p.getInventory().setContents(contents);
        try {
            p.setHealth(health);
        } catch (IllegalArgumentException ignored) {
            // vie max modifiée entre-temps
        }
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setLevel(level);
        p.setExp(exp);
        p.addPotionEffects(effects);
        p.setAllowFlight(allowFlight);
    }
}
