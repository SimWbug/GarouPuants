package fr.cleangang.loupgarou.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class GamePlayer {
    private final UUID uuid;
    private final String name;
    Role role;
    boolean alive = true;
    boolean left = false;
    UUID lover;
    Location seat;
    PlayerSnapshot snapshot;
    /** Faux joueur de test (admin) : joue tout seul, représenté par un mannequin sur son spawn. */
    final boolean bot;
    org.bukkit.entity.Entity body;

    GamePlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.bot = false;
    }

    GamePlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.bot = true;
    }

    public boolean bot() { return bot; }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public Role role() { return role; }
    public boolean alive() { return alive; }
    public UUID lover() { return lover; }

    public boolean isWolf() {
        return role == Role.LOUP_GAROU;
    }

    public Player player() {
        return Bukkit.getPlayer(uuid);
    }
}
