package fr.cleangang.loupgarou.game;

import fr.cleangang.loupgarou.LoupGarouPlugin;
import fr.cleangang.loupgarou.ui.Glyphs;
import fr.cleangang.loupgarou.ui.HoloMenu;
import fr.cleangang.loupgarou.ui.Items;
import fr.cleangang.loupgarou.ui.LgMenu;
import fr.cleangang.loupgarou.ui.MenuTheme;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Gère toutes les arènes, leurs parties, la tête de loup, le menu de sélection et l'éditeur de spawns. */
public final class ArenaManager {
    private static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("Loup-Garou", NamedTextColor.RED))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

    private final LoupGarouPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new TreeMap<>();
    private final Map<String, Game> games = new HashMap<>();
    private final Map<UUID, EditSession> editors = new HashMap<>();

    public ArenaManager(LoupGarouPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    // ------------------------------------------------------------------ stockage

    public void load() {
        arenas.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("arenas");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s != null) arenas.put(id, Arena.load(id, s));
            }
        }
        for (Arena a : arenas.values()) games.computeIfAbsent(a.id(), k -> new Game(plugin, this, a));
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Arena a : arenas.values()) a.save(yml.createSection("arenas." + a.id()));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'enregistrer arenas.yml : " + e.getMessage());
        }
    }

    public Collection<Arena> arenas() {
        return arenas.values();
    }

    public Arena arena(String id) {
        if (id == null) return null;
        Arena a = arenas.get(id.toLowerCase(Locale.ROOT));
        if (a != null) return a;
        for (Arena x : arenas.values()) if (x.displayName().equalsIgnoreCase(id)) return x;
        return null;
    }

    public Game game(Arena a) {
        return a == null ? null : games.get(a.id());
    }

    public Arena create(String id, String display) {
        String key = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (key.isEmpty() || arenas.containsKey(key)) return null;
        Arena a = new Arena(key);
        if (display != null) a.displayName(display);
        arenas.put(key, a);
        games.put(key, new Game(plugin, this, a));
        save();
        return a;
    }

    public boolean delete(Arena a) {
        Game g = games.remove(a.id());
        if (g != null) g.forceStop();
        for (EditSession s : new ArrayList<>(editors.values())) {
            if (s.arena == a) stopEdit(s.player);
        }
        arenas.remove(a.id());
        save();
        return true;
    }

    // ------------------------------------------------------------------ joueurs ↔ parties

    /** La partie où le joueur est inscrit ou en train de jouer, sinon null. */
    public Game gameOf(Player p) {
        for (Game g : games.values()) if (g.isInGame(p)) return g;
        return null;
    }

    /** Partie que le joueur regarde (mort en spectateur ou spectateur extérieur). */
    public Game spectatorGame(Player p) {
        for (Game g : games.values()) if (g.isWatcher(p) || g.isOutsider(p)) return g;
        Game g = gameOf(p);
        return g != null && g.isWatcher(p) ? g : null;
    }

    public Game outsiderGame(Player p) {
        for (Game g : games.values()) if (g.isOutsider(p)) return g;
        return null;
    }

    public void spectate(Player p, Arena arena) {
        if (gameOf(p) != null) {
            msg(p, Component.text("Quitte d'abord ta file ou ta partie.", NamedTextColor.RED));
            return;
        }
        Game current = outsiderGame(p);
        if (current != null) current.stopSpectate(p);
        Game g = game(arena);
        if (g != null) g.spectate(p);
    }

    public boolean isActive(Player p) {
        Game g = gameOf(p);
        return g != null && g.isActive(p);
    }

    /** L'arène « par défaut » d'une commande : celle du joueur, sinon la seule arène existante. */
    public Arena contextArena(Player p, String arg) {
        if (arg != null) return arena(arg);
        if (p != null) {
            Game g = gameOf(p);
            if (g != null) return g.arena();
            EditSession s = editors.get(p.getUniqueId());
            if (s != null) return s.arena;
        }
        return arenas.size() == 1 ? arenas.values().iterator().next() : null;
    }

    public void join(Player p, Arena arena) {
        if (editors.containsKey(p.getUniqueId())) {
            msg(p, Component.text("Termine d'abord l'édition de l'arène.", NamedTextColor.RED));
            return;
        }
        Game current = gameOf(p);
        Game target = game(arena);
        if (target == null) return;
        if (current == target) {
            msg(p, Component.text("Tu es déjà dans cette arène.", NamedTextColor.YELLOW));
            return;
        }
        if (current != null) {
            if (current.isActive(p)) {
                msg(p, Component.text("Tu es en pleine partie !", NamedTextColor.RED));
                return;
            }
            current.leave(p);
        }
        if (!arena.ready()) {
            msg(p, Component.text("Cette arène n'a encore aucun point de spawn.", NamedTextColor.RED));
            return;
        }
        target.join(p);
    }

    public void leave(Player p) {
        Game o = outsiderGame(p);
        if (o != null) {
            o.stopSpectate(p);
            msg(p, Component.text("Tu ne regardes plus la partie.", NamedTextColor.YELLOW));
            return;
        }
        Game g = gameOf(p);
        if (g == null) {
            msg(p, Component.text("Tu n'es dans aucune arène.", NamedTextColor.RED));
            return;
        }
        g.leave(p);
    }

    public void handleQuit(Player p) {
        Game o = outsiderGame(p);
        if (o != null) o.stopSpectate(p);
        Game g = gameOf(p);
        if (g != null) g.handleQuit(p);
        stopEdit(p);
        HoloMenu.close(p);
    }

    public void forceStopAll() {
        for (Game g : games.values()) g.forceStop();
        for (EditSession s : new ArrayList<>(editors.values())) stopEdit(s.player);
    }

    // ------------------------------------------------------------------ menu de sélection

    // Menu 3D « choix de l'arène » : 3 arènes par page, bannière loup / canard, têtes des joueurs, bouton d'action
    private static final int PER_PAGE = 3;
    private static final double BOARD_DEPTH = 4.0;
    private static final double BANNER_W = 6.0, BANNER_H = 6.0 * 244 / 1128;
    private static final double[] ROW_Y = {1.36, 0.0, -1.36};
    private final Map<UUID, Integer> selectorPage = new HashMap<>();

    /** Texte du menu des arènes en Archivo Black (police du pack). */
    private static Component board(String text) {
        return Items.menuText(text, NamedTextColor.WHITE);
    }

    public void openSelector(Player p) {
        if (isActive(p)) {
            msg(p, Component.text("Tu es en pleine partie !", NamedTextColor.RED));
            return;
        }
        p.closeInventory();
        selectorPage.putIfAbsent(p.getUniqueId(), 0);
        LgMenu menu = new LgMenu(MenuTheme.ARENAS, Component.text("Arènes"), m -> renderSelector(m, p));
        HoloMenu.open(p, menu, (mm, slot, right) -> mm.click(slot, right));
        // rafraîchit joueurs et comptes à rebours tant que le menu est ouvert
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            HoloMenu holo = p.isOnline() ? HoloMenu.get(p) : null;
            if (holo == null || holo.menu() != menu) {
                task.cancel();
                return;
            }
            menu.render();
        }, 20L, 20L);
    }

    private static ItemStack flatItem(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(new org.bukkit.NamespacedKey("lgt", model));
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private void renderSelector(LgMenu m, Player p) {
        m.panel(6.3, 2.2, -2.55, BOARD_DEPTH);
        List<Arena> list = new ArrayList<>(arenas.values());
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(pages - 1, selectorPage.getOrDefault(p.getUniqueId(), 0)));
        selectorPage.put(p.getUniqueId(), page);
        Game mine = gameOf(p);

        if (list.isEmpty()) {
            m.text(1, board("AUCUNE ARÈNE POUR LE MOMENT"),
                    0, 0.2, 0.9f, 400, 0);
            if (p.hasPermission("loupgarou.admin")) {
                m.text(2, Component.text("/lg arena create <nom>", NamedTextColor.AQUA), 0, -0.25, 0.6f, 400, 0);
            }
        }
        for (int row = 0; row < PER_PAGE; row++) {
            int index = page * PER_PAGE + row;
            if (index >= list.size()) break;
            Arena a = list.get(index);
            Game g = game(a);
            double y = ROW_Y[row];
            int base = 100 + row * 40;

            // bannière (loup à gauche, canard à droite, cadres du titre et du compteur)
            m.place(base, flatItem("arena_banner"), null,
                    new LgMenu.Layout(0, y, (float) BANNER_W, (float) BANNER_H, 0.06, 0, true));
            // nom de l'arène dans le cadre du haut
            String title = a.displayName().toUpperCase();
            float titleScale = (float) Math.min(0.75, 0.75 * 10.0 / Math.max(1, title.length()));
            m.text(base, board(title),
                    0.04, y + 0.41, titleScale, 300, -0.02);
            // compteur dans le cadre cyan
            int count = g.playerCount();
            String counter;
            if (g.state() == Game.State.PLAYING || g.state() == Game.State.ENDING) counter = g.aliveCount() + " EN VIE";
            else if (g.state() == Game.State.STARTING) counter = "DÉPART " + g.timeLeft() + " S";
            else counter = count + "/" + a.maxPlayers() + " JOUEURS";
            m.text(base + 1, board(counter),
                    1.55, y - 0.10, (float) Math.min(0.42, 0.42 * 12.0 / Math.max(1, counter.length())), 300, -0.02);
            // têtes des joueurs (2 lignes de 8)
            List<GamePlayer> roster = g.roster();
            for (int i = 0; i < roster.size() && i < 16; i++) {
                GamePlayer gp = roster.get(i);
                int col = i % 8, line = i / 8;
                m.place(base + 2 + i, Items.head(gp.uuid(), gp.name(), null, 1, false), null,
                        new LgMenu.Layout(0.04 + (col - 3.5) * 0.24, y + 0.10 - line * 0.24, 0.2f, 0.2f, -0.02, 0, false));
            }
            // bouton d'action
            boolean here = mine == g;
            boolean playing = g.state() == Game.State.PLAYING || g.state() == Game.State.ENDING;
            String button;
            Runnable action;
            if (here && !playing) {
                button = "btn_quitter";
                action = () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    g.leave(p);
                    m.render();
                });
            } else if (!a.ready()) {
                button = "btn_indispo";
                action = null;
            } else if (playing) {
                button = "btn_en_cours";
                action = () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    HoloMenu.close(p);
                    spectate(p, a);
                });
            } else if (count >= a.maxPlayers()) {
                button = "btn_complet";
                action = null;
            } else {
                button = "btn_jouer";
                action = () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    join(p, a);
                    m.render();
                });
            }
            m.place(base + 30, flatItem(button), action,
                    new LgMenu.Layout(0.04, y - 0.40, 0.95f, 0.277f, -0.02, 0.32, true));
        }
        // pages
        if (pages > 1) {
            if (page > 0) {
                m.place(10, flatItem("arrow_left"), () -> {
                    selectorPage.put(p.getUniqueId(), page - 1);
                    m.render();
                }, new LgMenu.Layout(-2.65, -2.27, 0.34f, 0.34f, -0.02, 0.22, true));
            }
            if (page < pages - 1) {
                m.place(11, flatItem("arrow_right"), () -> {
                    selectorPage.put(p.getUniqueId(), page + 1);
                    m.render();
                }, new LgMenu.Layout(2.65, -2.27, 0.34f, 0.34f, -0.02, 0.22, true));
            }
            m.text(3, board("PAGE " + (page + 1) + " / " + pages),
                    0, -2.27, 0.5f, 300, -0.02);
        }
    }

    // ------------------------------------------------------------------ tête de loup

    public void refreshJoinItemLater(Player p) {
        for (long delay : new long[]{2L, 20L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) refreshJoinItem(p);
            }, delay);
        }
    }

    public int joinSlot() {
        return Math.max(0, Math.min(8, plugin.getConfig().getInt("join-item.slot", 3)));
    }

    /**
     * Donne / retire la tête de loup. Elle n'apparaît que dans la zone du hub (mondes autorisés et,
     * si join-item.radius > 0, à moins de ce rayon du point /lg setlobby). Elle ne déplace jamais
     * l'objet d'un autre plugin : si sa case est prise, elle va dans une case libre de la barre, sinon nulle part.
     */
    public void refreshJoinItem(Player p) {
        if (isActive(p) || editors.containsKey(p.getUniqueId()) || outsiderGame(p) != null) return;
        PlayerInventory inv = p.getInventory();
        boolean want = plugin.getConfig().getBoolean("join-item.enabled", true) && inJoinWorld(p);
        int found = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (!Items.TAG_JOIN.equals(Items.tagOf(inv.getItem(i)))) continue;
            if (!want || found >= 0) inv.setItem(i, null); // hors zone, ou doublon
            else found = i;
        }
        if (!want) return;
        Game g = gameOf(p);
        ItemStack expected = Items.joinItem(g == null ? null : g.arena().displayName());
        if (found >= 0) {
            if (!expected.isSimilar(inv.getItem(found))) inv.setItem(found, expected);
            return;
        }
        int slot = joinSlot();
        if (isEmpty(inv.getItem(slot))) {
            inv.setItem(slot, expected);
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (isEmpty(inv.getItem(i))) {
                inv.setItem(i, expected);
                return;
            }
        }
        // barre pleine (on est sûrement dans un autre jeu) : pas de tête
    }

    /** Retire la tête de tout le monde puis la redonne (après /lg slot pour la changer de case). */
    public void resetJoinItems() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isActive(p) || editors.containsKey(p.getUniqueId())) continue;
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (Items.TAG_JOIN.equals(Items.tagOf(inv.getItem(i)))) inv.setItem(i, null);
            }
            refreshJoinItem(p);
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    public boolean inJoinWorld(Player p) {
        String world = p.getWorld().getName();
        if (joinWorlds().stream().noneMatch(w -> w.equalsIgnoreCase(world))) return false;
        double radius = plugin.getConfig().getDouble("join-item.radius", 0);
        if (radius <= 0) return true;
        Location hub = hubPoint();
        if (hub == null || !hub.getWorld().equals(p.getWorld())) return true;
        return hub.distanceSquared(p.getLocation()) <= radius * radius;
    }

    /** Mondes où la tête est donnée : join-item.worlds, sinon le monde du hub (/lg setlobby), sinon le monde principal. */
    public List<String> joinWorlds() {
        List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("join-item.worlds"));
        if (worlds.isEmpty()) {
            String lobbyWorld = plugin.getConfig().getString("lobby-world", "");
            if (lobbyWorld != null && !lobbyWorld.isBlank()) worlds.add(lobbyWorld);
            else if (!Bukkit.getWorlds().isEmpty()) worlds.add(Bukkit.getWorlds().get(0).getName());
        }
        return worlds;
    }

    /** Point du hub (/lg setlobby), enregistré par nom de monde. */
    public Location hubPoint() {
        String w = plugin.getConfig().getString("lobby-world", "");
        if (w == null || w.isBlank() || !plugin.getConfig().contains("lobby-x")) return null;
        org.bukkit.World world = Bukkit.getWorld(w);
        if (world == null) return null;
        return new Location(world, plugin.getConfig().getDouble("lobby-x"), plugin.getConfig().getDouble("lobby-y"),
                plugin.getConfig().getDouble("lobby-z"));
    }

    public void startWatchers() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllJoinItems, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEditors, 10L, 10L);
    }

    public void refreshAllJoinItems() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshJoinItem(p);
    }

    // ------------------------------------------------------------------ éditeur d'arène

    private final class EditSession {
        final Player player;
        final Arena arena;
        final ItemStack[] saved;
        final GameMode savedMode;
        final List<TextDisplay> markers = new ArrayList<>();
        int tpIndex;

        EditSession(Player player, Arena arena) {
            this.player = player;
            this.arena = arena;
            ItemStack[] raw = player.getInventory().getContents();
            this.saved = new ItemStack[raw.length];
            for (int i = 0; i < raw.length; i++) saved[i] = raw[i] == null ? null : raw[i].clone();
            this.savedMode = player.getGameMode();
        }
    }

    public boolean isEditing(Player p) {
        return editors.containsKey(p.getUniqueId());
    }

    public void startEdit(Player p, Arena arena) {
        Game g = game(arena);
        if (g != null && g.state() != Game.State.LOBBY) {
            msg(p, Component.text("Une partie est en cours dans cette arène.", NamedTextColor.RED));
            return;
        }
        if (gameOf(p) != null) gameOf(p).leave(p);
        stopEdit(p);
        EditSession s = new EditSession(p, arena);
        editors.put(p.getUniqueId(), s);
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setItem(0, Items.editTool("edit_spawn", Material.ARMOR_STAND, "Placer un spawn",
                "Clic droit : ajoute un spawn à tes pieds,", "orienté dans la direction où tu regardes.",
                "Clic gauche : retire le spawn le plus proche.", "Maximum " + Arena.MAX_SPAWNS + " (= joueurs max)."));
        inv.setItem(1, Items.editTool("edit_lobby", Material.COMPASS, "Point d'attente",
                "Clic droit : les joueurs qui rejoignent", "l'arène attendent ici avant la partie."));
        inv.setItem(2, Items.editTool("edit_tp", Material.ENDER_EYE, "Visiter les spawns",
                "Clic droit : te téléporte au spawn suivant", "pour vérifier la vue de chaque joueur."));
        inv.setItem(3, Items.editTool("edit_roles", Material.WRITABLE_BOOK, "Composition des rôles",
                "Clic droit : choisir le nombre de", "loups, voyante, sorcière..."));
        inv.setItem(8, Items.editTool("edit_done", Material.BARRIER, "Terminer l'édition",
                "Clic droit : enregistre et te rend", "ton inventaire."));
        inv.setHeldItemSlot(0);
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) p.setGameMode(GameMode.CREATIVE);
        refreshMarkers(s);
        msg(p, Component.text("Édition de « " + arena.displayName() + " » : place jusqu'à " + Arena.MAX_SPAWNS
                + " spawns avec l'armor stand (clic droit), en regardant vers le centre de la table.", NamedTextColor.GREEN));
    }

    public void stopEdit(Player p) {
        EditSession s = editors.remove(p.getUniqueId());
        if (s == null) return;
        for (TextDisplay td : s.markers) td.remove();
        s.markers.clear();
        p.getInventory().clear();
        p.getInventory().setContents(s.saved);
        p.setGameMode(s.savedMode);
        save();
        if (p.isOnline()) {
            msg(p, Component.text("Arène « " + s.arena.displayName() + " » enregistrée : " + s.arena.spawns().size()
                    + " spawn(s)" + (s.arena.lobby() == null ? ", pas de point d'attente." : ", point d'attente défini."),
                    NamedTextColor.GREEN));
            refreshJoinItemLater(p);
        }
    }

    /** Clic avec un outil d'édition. */
    public void handleEditTool(Player p, String tag, boolean rightClick) {
        EditSession s = editors.get(p.getUniqueId());
        if (s == null) return;
        Arena a = s.arena;
        switch (tag) {
            case "edit_spawn" -> {
                if (rightClick) {
                    if (a.spawns().size() >= Arena.MAX_SPAWNS) {
                        msg(p, Component.text("Maximum " + Arena.MAX_SPAWNS + " spawns. Clic gauche près d'un spawn pour le retirer.",
                                NamedTextColor.RED));
                        return;
                    }
                    Location l = p.getLocation().toBlockLocation().add(0.5, 0, 0.5);
                    l.setYaw(Math.round(p.getLocation().getYaw() / 15f) * 15f);
                    l.setPitch(0);
                    a.spawns().add(Arena.Point.of(l));
                    sound(p, "block.note_block.pling", 1f, 1.2f + a.spawns().size() * 0.05f);
                    msg(p, Component.text("Spawn #" + a.spawns().size() + " ajouté (" + a.spawns().size() + "/" + Arena.MAX_SPAWNS + ").",
                            NamedTextColor.GREEN));
                } else {
                    int best = -1;
                    double bestDist = 9;
                    for (int i = 0; i < a.spawns().size(); i++) {
                        Location l = a.spawns().get(i).toLocation();
                        if (l == null || !l.getWorld().equals(p.getWorld())) continue;
                        double d = l.distanceSquared(p.getLocation());
                        if (d < bestDist) {
                            bestDist = d;
                            best = i;
                        }
                    }
                    if (best < 0) {
                        msg(p, Component.text("Aucun spawn à moins de 3 blocs.", NamedTextColor.RED));
                        return;
                    }
                    a.spawns().remove(best);
                    sound(p, "entity.item.break", 1f, 1f);
                    msg(p, Component.text("Spawn #" + (best + 1) + " retiré. Les suivants sont renumérotés.", NamedTextColor.YELLOW));
                }
                save();
                refreshMarkers(s);
            }
            case "edit_lobby" -> {
                if (!rightClick) return;
                a.lobby(Arena.Point.of(p.getLocation()));
                save();
                refreshMarkers(s);
                sound(p, "block.note_block.bell", 1f, 1f);
                msg(p, Component.text("Point d'attente défini.", NamedTextColor.GREEN));
            }
            case "edit_tp" -> {
                if (!rightClick || a.spawns().isEmpty()) return;
                s.tpIndex = s.tpIndex % a.spawns().size();
                Location l = a.spawns().get(s.tpIndex).toLocation();
                if (l != null) {
                    p.teleport(l);
                    msg(p, Component.text("Spawn #" + (s.tpIndex + 1) + " — c'est la vue qu'aura ce joueur.", NamedTextColor.AQUA));
                }
                s.tpIndex++;
            }
            case "edit_roles" -> {
                if (rightClick) Bukkit.getScheduler().runTask(plugin, () -> game(a).openAdmin(p));
            }
            case "edit_done" -> {
                if (rightClick) Bukkit.getScheduler().runTask(plugin, () -> stopEdit(p));
            }
            default -> { }
        }
    }

    private void refreshMarkers(EditSession s) {
        for (TextDisplay td : s.markers) td.remove();
        s.markers.clear();
        List<Arena.Point> spawns = s.arena.spawns();
        for (int i = 0; i < spawns.size(); i++) {
            Location l = spawns.get(i).toLocation();
            if (l == null) continue;
            s.markers.add(marker(s.player, l.clone().add(0, 2.3, 0),
                    Component.text("Spawn #" + (i + 1), NamedTextColor.GOLD)));
        }
        if (s.arena.lobby() != null) {
            Location l = s.arena.lobby().toLocation();
            if (l != null) s.markers.add(marker(s.player, l.clone().add(0, 2.3, 0),
                    Component.text("Point d'attente", NamedTextColor.AQUA)));
        }
    }

    private TextDisplay marker(Player viewer, Location at, Component text) {
        TextDisplay td = at.getWorld().spawn(at, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
            e.text(text);
            e.setShadowed(true);
        });
        viewer.showEntity(plugin, td);
        return td;
    }

    /** Particules : position et direction de chaque spawn, visibles uniquement par l'éditeur. */
    private void tickEditors() {
        for (EditSession s : editors.values()) {
            Player p = s.player;
            if (!p.isOnline()) continue;
            for (Arena.Point pt : s.arena.spawns()) {
                Location l = pt.toLocation();
                if (l == null || !l.getWorld().equals(p.getWorld()) || l.distanceSquared(p.getLocation()) > 64 * 64) continue;
                p.spawnParticle(Particle.FLAME, l.clone().add(0, 0.1, 0), 4, 0.2, 0, 0.2, 0);
                Vector dir = l.getDirection().setY(0).normalize();
                for (double d = 0.4; d <= 1.6; d += 0.4) {
                    p.spawnParticle(Particle.END_ROD, l.clone().add(0, 1.6, 0).add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
                }
            }
            p.sendActionBar(Component.text("Édition « " + s.arena.displayName() + " » · spawns "
                    + s.arena.spawns().size() + "/" + Arena.MAX_SPAWNS + " · point d'attente "
                    + (s.arena.lobby() == null ? "✘" : "✔"), NamedTextColor.YELLOW));
        }
    }

    // ------------------------------------------------------------------ outils

    private void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }

    public void msg(Player p, Component c) {
        p.sendMessage(PREFIX.append(c));
    }

    public LoupGarouPlugin plugin() {
        return plugin;
    }
}
