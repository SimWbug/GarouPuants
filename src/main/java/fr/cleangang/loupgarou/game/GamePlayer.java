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

    GamePlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

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
