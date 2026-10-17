package fr.cleangang.loupgarou.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Une arène : ses points de spawn (16 max, un par joueur), son point d'attente et sa composition.
 * Les positions sont gardées par nom de monde : elles restent valides même si le monde est chargé
 * après le plugin (hub multimonde).
 */
public final class Arena {
    public static final int MAX_SPAWNS = 16;

    /** Position sauvegardée par nom de monde. */
    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
        public static Point of(Location l) {
            return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        /** null si le monde n'est pas chargé. */
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        void save(ConfigurationSection s) {
            s.set("world", world);
            s.set("x", x);
            s.set("y", y);
            s.set("z", z);
            s.set("yaw", (double) yaw);
            s.set("pitch", (double) pitch);
        }

        static Point load(ConfigurationSection s) {
            if (s == null || s.getString("world") == null) return null;
            return new Point(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        }
    }

    private final String id;
    private String displayName;
    private final List<Point> spawns = new ArrayList<>();
    private Point lobby;
    private boolean customRoles;
    private final Map<Role, Integer> custom = new EnumMap<>(Role.class);

    public Arena(String id) {
        this.id = id;
        this.displayName = id;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public void displayName(String name) { this.displayName = name; }
    public List<Point> spawns() { return spawns; }
    public Point lobby() { return lobby; }
    public void lobby(Point p) { this.lobby = p; }
    public boolean customRoles() { return customRoles; }
    public void customRoles(boolean on) { this.customRoles = on; }

    public int customCount(Role r) {
        return custom.getOrDefault(r, 0);
    }

    public void customCount(Role r, int n) {
        custom.put(r, Math.max(0, Math.min(Arena.MAX_SPAWNS, n)));
    }

    /** Nombre maximum de joueurs = nombre de spawns placés. */
    public int maxPlayers() {
        return spawns.size();
    }

    public boolean ready() {
        return !spawns.isEmpty();
    }

    public String worldName() {
        if (!spawns.isEmpty()) return spawns.get(0).world();
        return lobby == null ? null : lobby.world();
    }

    void save(ConfigurationSection s) {
        s.set("name", displayName);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Point p : spawns) {
            list.add(Map.of("world", p.world(), "x", p.x(), "y", p.y(), "z", p.z(),
                    "yaw", (double) p.yaw(), "pitch", (double) p.pitch()));
        }
        s.set("spawns", list);
        s.set("lobby", null);
        if (lobby != null) lobby.save(s.createSection("lobby"));
        s.set("roles.mode", customRoles ? "custom" : "auto");
        for (Role r : Role.values()) s.set("roles.custom." + r.id(), customCount(r));
    }

    static Arena load(String id, ConfigurationSection s) {
        Arena a = new Arena(id);
        a.displayName = s.getString("name", id);
        for (Map<?, ?> m : s.getMapList("spawns")) {
            Object w = m.get("world");
            if (w == null) continue;
            a.spawns.add(new Point(w.toString(), num(m.get("x")), num(m.get("y")), num(m.get("z")),
                    (float) num(m.get("yaw")), (float) num(m.get("pitch"))));
        }
        a.lobby = Point.load(s.getConfigurationSection("lobby"));
        a.customRoles = "custom".equalsIgnoreCase(s.getString("roles.mode", "auto"));
        for (Role r : Role.values()) a.custom.put(r, Math.max(0, s.getInt("roles.custom." + r.id(), 0)));
        return a;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
