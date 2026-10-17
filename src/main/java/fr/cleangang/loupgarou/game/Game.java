package fr.cleangang.loupgarou.game;

import fr.cleangang.loupgarou.LoupGarouPlugin;
import fr.cleangang.loupgarou.ui.CardAnimator;
import fr.cleangang.loupgarou.ui.HoloMenu;
import fr.cleangang.loupgarou.ui.Items;
import fr.cleangang.loupgarou.ui.LgMenu;
import fr.cleangang.loupgarou.ui.MenuTheme;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class Game {
    public enum State { LOBBY, STARTING, PLAYING, ENDING }

    public enum Phase {
        NONE, COUNTDOWN, DISTRIBUTION, NIGHTFALL, CUPID, LOVERS, SEER, GUARD, WOLVES, WITCH,
        DAWN, REVEAL, MAYOR_ELECTION, DISCUSSION, VOTE, HUNTER, SUCCESSION, END;

        boolean isNight() {
            return switch (this) {
                case NIGHTFALL, CUPID, LOVERS, SEER, GUARD, WOLVES, WITCH -> true;
                default -> false;
            };
        }
    }

    public enum Winner { VILLAGE, WOLVES, LOVERS, NOBODY }

    private static final Component PREFIX = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("Loup-Garou", NamedTextColor.RED))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY));

    private final LoupGarouPlugin plugin;
    private final ArenaManager manager;
    private final Arena arena;
    private final Map<UUID, GamePlayer> players = new ConcurrentHashMap<>();
    private final Map<UUID, LgMenu> menus = new HashMap<>();

    private volatile State state = State.LOBBY;
    private volatile Phase phase = Phase.NONE;
    private boolean forced;

    // minuterie de phase
    private BukkitTask ticker;
    private BossBar bossBar;
    private Component phaseName = Component.empty();
    private int timeLeft;
    private int phaseTotal;
    private Runnable phaseEnd;
    private int phaseToken;

    // état de la partie
    private int night;
    private int day;
    private Deque<Runnable> nightSteps = new ArrayDeque<>();
    private final Map<UUID, UUID> wolfVotes = new HashMap<>();
    private final Map<UUID, UUID> dayVotes = new LinkedHashMap<>();
    private final List<UUID> cupidPicks = new ArrayList<>();
    private UUID guardTarget;
    private UUID lastGuardTarget;
    private UUID wolfVictim;
    private UUID witchKill;
    private boolean witchHeal;
    private boolean healUsed;
    private boolean poisonUsed;
    private boolean seerDone;
    private UUID hunterShot;
    private String witchHealLog;

    // spectateurs (morts + extérieurs) : spectateur → joueur suivi
    private final Map<UUID, UUID> watching = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> outsiders = new HashMap<>();
    private final Map<UUID, Integer> specClick = new HashMap<>();

    // mise en scène, votes 3D, historique
    private final List<Component> history = new ArrayList<>();
    private final Map<UUID, org.bukkit.entity.TextDisplay> voteTags = new HashMap<>();
    private BukkitTask fxTask;
    private long fxTicks;
    private Location center;

    // Maire
    private UUID mayor;
    private boolean mayorElectionDone;
    private final Map<UUID, UUID> mayorVotes = new HashMap<>();
    private UUID successorPick;
    private int mayorExtends;

    public Game(LoupGarouPlugin plugin, ArenaManager manager, Arena arena) {
        this.plugin = plugin;
        this.manager = manager;
        this.arena = arena;
    }

    public Arena arena() {
        return arena;
    }

    public int timeLeft() {
        return Math.max(0, timeLeft);
    }

    /** Ligne d'état pour le menu de sélection. */
    public String statusLine() {
        long alive = alive().size();
        return (phase.isNight() ? "Nuit " + night : "Jour " + Math.max(1, day)) + " · " + alive + " survivant(s)";
    }

    // ------------------------------------------------------------------ accès

    public State state() { return state; }
    public Phase phase() { return phase; }

    public GamePlayer get(Player p) {
        return players.get(p.getUniqueId());
    }

    public boolean isInGame(Player p) {
        return players.containsKey(p.getUniqueId());
    }

    /** Partie en cours (le joueur est contrôlé par le plugin). */
    public boolean isActive(Player p) {
        GamePlayer gp = get(p);
        return gp != null && !gp.left && (state == State.PLAYING || state == State.ENDING);
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private int timer(String key) {
        return Math.max(3, cfg().getInt("timers." + key, 30));
    }

    // ------------------------------------------------------------------ lobby

    public void join(Player p) {
        if (state != State.LOBBY && state != State.STARTING) {
            msg(p, Component.text("Une partie est déjà en cours.", NamedTextColor.RED));
            return;
        }
        if (isInGame(p)) {
            msg(p, Component.text("Tu es déjà inscrit.", NamedTextColor.YELLOW));
            return;
        }
        if (players.size() >= arena.maxPlayers()) {
            msg(p, Component.text("Cette arène est complète (" + arena.maxPlayers() + " places).", NamedTextColor.RED));
            return;
        }
        players.put(p.getUniqueId(), new GamePlayer(p));
        plugin.sendResourcePack(p);
        Location lobby = arena.lobby() == null ? null : arena.lobby().toLocation();
        if (lobby != null) p.teleport(lobby);
        broadcast(Component.text(p.getName() + " rejoint « " + arena.displayName() + " » ", NamedTextColor.GREEN)
                .append(Component.text("(" + players.size() + "/" + arena.maxPlayers() + ", lancement à "
                        + cfg().getInt("min-players", 5) + ")", NamedTextColor.GRAY)));
        sound(p, "entity.wolf.howl", 0.7f, 1f);
        manager.refreshJoinItemLater(p);
        checkAutoStart();
    }

    /** Lancement automatique dès qu'il y a assez de joueurs ; accéléré quand l'arène est pleine. */
    private void checkAutoStart() {
        if (!cfg().getBoolean("auto-start", true)) return;
        int n = players.size();
        if (state == State.LOBBY && n >= cfg().getInt("min-players", 5) && compositionError(n) == null) {
            startCountdown(null, false);
        } else if (state == State.STARTING && n >= arena.maxPlayers() && timeLeft > 10) {
            timeLeft = 10;
            broadcast(Component.text("L'arène est pleine, la partie commence dans 10 s !", NamedTextColor.GOLD));
        }
    }

    public void leave(Player p) {
        GamePlayer gp = get(p);
        if (gp == null) {
            msg(p, Component.text("Tu n'es pas dans la partie.", NamedTextColor.RED));
            return;
        }
        if (state == State.PLAYING || state == State.ENDING) {
            handleQuit(p);
            msg(p, Component.text("Tu as quitté la partie.", NamedTextColor.YELLOW));
            return;
        }
        players.remove(p.getUniqueId());
        broadcast(Component.text(p.getName() + " quitte le village.", NamedTextColor.YELLOW));
        msg(p, Component.text("Tu as quitté la file d'attente.", NamedTextColor.YELLOW));
        hideBossBar(p);
        manager.refreshJoinItemLater(p);
        if (state == State.STARTING && (players.isEmpty() || (!forced && players.size() < cfg().getInt("min-players", 5)))) {
            broadcast(Component.text("Pas assez de joueurs, lancement annulé. En attente...", NamedTextColor.RED));
            cancelCountdown();
        }
    }

    public void startCountdown(Player requester) {
        startCountdown(requester, false);
    }

    /**
     * @param force ignore le nombre minimum de joueurs et les vérifications de composition
     *              (pour tester à 1 ou 2). La partie se termine normalement dès qu'un camp gagne.
     */
    public void startCountdown(Player requester, boolean force) {
        int min = cfg().getInt("min-players", 5);
        if (state == State.STARTING && requester != null) {
            // lancement demandé pendant le compte à rebours auto : on raccourcit
            if (timeLeft > timer("countdown")) timeLeft = timer("countdown");
            if (force) forced = true;
            return;
        }
        if (state != State.LOBBY) {
            if (requester != null) msg(requester, Component.text("Une partie est déjà lancée.", NamedTextColor.RED));
            return;
        }
        if (players.isEmpty()) {
            if (requester != null) msg(requester, Component.text("Personne n'est inscrit dans « " + arena.displayName()
                    + " ». Clic droit sur la tête de loup pour rejoindre avant de lancer.", NamedTextColor.RED));
            return;
        }
        if (!force) {
            if (players.size() < min) {
                if (requester != null) msg(requester, Component.text("Il faut au moins " + min + " joueurs (" + players.size()
                        + " inscrits). Pour tester quand même : /lg forcestart", NamedTextColor.RED));
                return;
            }
            String error = compositionError(players.size());
            if (error != null) {
                if (requester != null) msg(requester, Component.text(error
                        + " Ajuste la composition avec /lg admin, ou /lg forcestart pour tester.", NamedTextColor.RED));
                return;
            }
        }
        forced = force;
        if (force && requester != null) {
            broadcast(Component.text("Lancement forcé par " + requester.getName() + " (mode test).", NamedTextColor.GOLD));
        }
        state = State.STARTING;
        startTicker();
        int seconds = requester == null ? timer("lobby") : timer("countdown");
        if (requester == null) {
            broadcast(Component.text("Assez de joueurs ! La partie commence dans " + seconds + " s.", NamedTextColor.GOLD));
        }
        runPhase(Phase.COUNTDOWN, Component.text("La partie commence", NamedTextColor.GOLD),
                seconds, BossBar.Color.WHITE, this::begin);
        checkAutoStart();
    }

    // ------------------------------------------------------------------ démarrage

    private void begin() {
        players.values().removeIf(gp -> !gp.bot && gp.player() == null);
        int n = players.size();
        if (n == 0 || (!forced && n < cfg().getInt("min-players", 5))) {
            broadcast(Component.text("Pas assez de joueurs connectés, lancement annulé.", NamedTextColor.RED));
            cancelCountdown();
            return;
        }

        List<GamePlayer> list = new ArrayList<>(players.values());
        Collections.shuffle(list);
        String error = forced ? null : compositionError(n);
        if (error != null) {
            broadcast(Component.text("Lancement annulé : " + error, NamedTextColor.RED));
            reset();
            return;
        }
        List<Role> roles = buildRoles(n);
        Collections.shuffle(roles);
        for (int i = 0; i < n; i++) list.get(i).role = roles.get(i);

        // places : les spawns de l'arène, attribués au hasard
        List<Location> seats = new ArrayList<>();
        for (Arena.Point pt : arena.spawns()) {
            Location l = pt.toLocation();
            if (l != null) seats.add(l);
        }
        if (seats.size() < n) {
            broadcast(Component.text("Lancement annulé : l'arène n'a que " + seats.size() + " spawn(s) utilisables pour "
                    + n + " joueurs (monde chargé ?).", NamedTextColor.RED));
            reset();
            return;
        }
        Collections.shuffle(seats);

        for (int i = 0; i < n; i++) {
            GamePlayer gp = list.get(i);
            Player p = gp.player();
            Location seat = seats.get(i).clone();
            gp.seat = seat;
            if (gp.bot) {
                spawnBotBody(gp);
                continue;
            }
            gp.snapshot = new PlayerSnapshot(p);

            p.closeInventory();
            p.getInventory().clear();
            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            p.setGameMode(GameMode.ADVENTURE);
            p.setAllowFlight(false);
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.teleport(seat);
            giveGameItems(p, gp);
            p.getInventory().setHeldItemSlot(0);
        }

        state = State.PLAYING;
        night = 0;
        day = 0;
        history.clear();
        center = computeCenter();
        if (fxTask != null) fxTask.cancel();
        fxTask = Bukkit.getScheduler().runTaskTimer(plugin, this::fxTick, 4L, 4L);

        broadcast(Component.text("Les cartes sont distribuées... Regarde bien la tienne !", NamedTextColor.GOLD));
        broadcast(Component.text("Rôles en jeu : ", NamedTextColor.GRAY).append(roleSummary(roles)));
        for (GamePlayer gp : list) {
            Player p = gp.player();
            if (p == null) continue;
            CardAnimator.showBack(p, Component.text("Distribution des cartes...", NamedTextColor.GRAY));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player online = gp.player();
                if (online == null) return;
                CardAnimator.reveal(plugin, online, gp.role, Component.text(gp.role.displayName(), gp.role.color()));
                msg(online, Component.text("Tu es ", NamedTextColor.GRAY)
                        .append(Component.text(gp.role.displayName(), gp.role.color()).decorate(TextDecoration.BOLD))
                        .append(Component.text(" — " + gp.role.description(), NamedTextColor.GRAY)));
                if (gp.isWolf()) {
                    String pack = alive().stream().filter(GamePlayer::isWolf).filter(o -> o != gp)
                            .map(GamePlayer::name).collect(Collectors.joining(", "));
                    msg(online, Component.text(pack.isEmpty() ? "Tu es le seul loup. Bonne chance..." : "Ta meute : " + pack,
                            NamedTextColor.RED));
                }
            }, 40L);
        }
        runPhase(Phase.DISTRIBUTION, Component.text("Découvrez vos cartes", NamedTextColor.LIGHT_PURPLE),
                timer("distribution"), BossBar.Color.PURPLE, this::startNight);
    }

    // ------------------------------------------------------------------ composition

    public boolean customMode() {
        return arena.customRoles();
    }

    private int customCount(Role role) {
        return arena.customCount(role);
    }

    private void setCustomCount(Role role, int count) {
        arena.customCount(role, count);
    }

    /** Erreur bloquante pour n joueurs, ou null si la composition est jouable. */
    private String compositionError(int n) {
        List<Role> roles = buildRoles(n);
        long wolves = roles.stream().filter(r -> r == Role.LOUP_GAROU).count();
        if (roles.size() > n) return "Trop de cartes (" + roles.size() + ") pour " + n + " joueurs.";
        if (wolves == 0) return "Il faut au moins un Loup-Garou.";
        if (wolves >= n - wolves) return "Trop de loups : ils gagneraient dès le début.";
        return null;
    }

    private List<Role> buildRoles(int n) {
        if (customMode()) {
            List<Role> roles = new ArrayList<>();
            for (Role role : Role.values()) {
                for (int i = 0; i < customCount(role); i++) roles.add(role);
            }
            while (roles.size() < n) roles.add(Role.VILLAGEOIS);
            return roles;
        }
        List<Role> roles = new ArrayList<>();
        int wolves = Math.max(1, n / Math.max(2, cfg().getInt("roles.players-per-wolf", 4)));
        for (int i = 0; i < wolves; i++) roles.add(Role.LOUP_GAROU);
        addIf(roles, n, Role.VOYANTE, "voyante");
        addIf(roles, n, Role.SORCIERE, "sorciere");
        addIf(roles, n, Role.CHASSEUR, "chasseur");
        addIf(roles, n, Role.CUPIDON, "cupidon");
        addIf(roles, n, Role.SALVATEUR, "salvateur");
        while (roles.size() < n) roles.add(Role.VILLAGEOIS);
        return roles;
    }

    private void addIf(List<Role> roles, int n, Role role, String key) {
        int min = cfg().getInt("roles." + key, 0);
        if (min > 0 && n >= min && roles.size() < n) roles.add(role);
    }

    private Component roleSummary(List<Role> roles) {
        Map<Role, Long> counts = roles.stream().collect(Collectors.groupingBy(r -> r, () -> new EnumMap<>(Role.class), Collectors.counting()));
        Component out = Component.empty();
        boolean first = true;
        for (Map.Entry<Role, Long> e : counts.entrySet()) {
            if (!first) out = out.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            out = out.append(Component.text(e.getValue() + "× " + e.getKey().displayName(), e.getKey().color()));
            first = false;
        }
        return out;
    }

    // ------------------------------------------------------------------ nuit

    private void startNight() {
        if (state != State.PLAYING) return;
        night++;
        wolfVotes.clear();
        cupidPicks.clear();
        guardTarget = null;
        wolfVictim = null;
        witchKill = null;
        witchHeal = false;
        seerDone = false;
        closeMenus();

        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            if (!watching.containsKey(p.getUniqueId())) sound(p, "entity.wolf.howl", 0.5f, 0.7f);
            p.showTitle(Title.title(Component.text("Nuit " + night, NamedTextColor.DARK_AQUA),
                    Component.text("Le village s'endort...", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(50), Ticks.duration(10))));
            sound(p, "ambient.cave", 1f, 0.8f);
        }

        nightSteps = new ArrayDeque<>();
        if (night == 1 && dealt(Role.CUPIDON)) nightSteps.add(this::stepCupid);
        if (dealt(Role.VOYANTE)) nightSteps.add(this::stepSeer);
        if (dealt(Role.SALVATEUR)) nightSteps.add(this::stepGuard);
        nightSteps.add(this::stepWolves);
        if (dealt(Role.SORCIERE)) nightSteps.add(this::stepWitch);

        // le ciel s'assombrit doucement, puis le village s'endort
        animateSky(12500, 18000, 50, () -> {
            if (state != State.PLAYING || phase != Phase.NIGHTFALL) return;
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null && gp.alive && !gp.left) blind(p, true);
            }
        });
        runPhase(Phase.NIGHTFALL, Component.text("La nuit tombe", NamedTextColor.DARK_AQUA), 5,
                BossBar.Color.BLUE, this::nextNightStep);
    }

    private void nextNightStep() {
        if (state != State.PLAYING) return;
        Runnable step = nightSteps.poll();
        if (step == null) dawn();
        else step.run();
    }

    /** Étape de nuit : réveille les acteurs, ouvre leurs menus, les rendort à la fin. */
    private void roleStep(Phase p, Role role, String narration, String timerKey, BossBar.Color color,
                          List<GamePlayer> actors, MenuOpener opener, Runnable onEnd) {
        Component name = Component.text(narration, role.color());
        broadcast(Component.text("☾ ", NamedTextColor.DARK_AQUA).append(name.append(Component.text("...", role.color()))));
        if (actors.isEmpty()) {
            if (cfg().getBoolean("fake-steps-for-dead-roles", true)) {
                runPhase(p, name, ThreadLocalRandom.current().nextInt(6, 13), color, this::nextNightStep);
            } else {
                nextNightStep();
            }
            return;
        }
        // la phase démarre d'abord (le sablier des menus connaît ainsi sa durée), puis les menus s'ouvrent
        runPhase(p, name, timer(timerKey), color, () -> {
            for (GamePlayer actor : actors) {
                Player pl = actor.player();
                if (pl != null && actor.alive) blind(pl, true);
            }
            closeMenus();
            onEnd.run();
            nextNightStep();
        });
        for (GamePlayer actor : actors) {
            Player pl = actor.player();
            if (pl == null) continue;
            blind(pl, false);
            pl.showTitle(Title.title(Component.text("Réveille-toi", role.color()),
                    Component.text(role.displayName() + ", à toi de jouer !", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(5), Ticks.duration(30), Ticks.duration(5))));
            sound(pl, "block.note_block.chime", 1f, 1.2f);
            try {
                opener.open(actor, pl);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Menu impossible à ouvrir pour " + pl.getName(), ex);
            }
        }
    }

    @FunctionalInterface
    private interface MenuOpener {
        void open(GamePlayer actor, Player player);
    }

    private void stepCupid() {
        List<GamePlayer> actors = aliveWith(Role.CUPIDON);
        roleStep(Phase.CUPID, Role.CUPIDON, "Cupidon se réveille et décoche sa flèche", "cupid", BossBar.Color.PINK,
                actors, (actor, pl) -> open(pl, new LgMenu(MenuTheme.CUPID, m -> renderCupid(m))), () -> {
                    if (actors.isEmpty()) return;
                    List<GamePlayer> pool = alive();
                    Collections.shuffle(pool);
                    for (GamePlayer gp : pool) {
                        if (cupidPicks.size() >= 2) break;
                        if (!cupidPicks.contains(gp.uuid())) cupidPicks.add(gp.uuid());
                    }
                    if (cupidPicks.size() == 2) {
                        GamePlayer a = players.get(cupidPicks.get(0));
                        GamePlayer b = players.get(cupidPicks.get(1));
                        log("Cupidon unit " + a.name() + " ♥ " + b.name(), Role.CUPIDON.color());
                        a.lover = b.uuid();
                        b.lover = a.uuid();
                        nightSteps.addFirst(() -> stepLovers(a, b));
                    }
                });
    }

    private void renderCupid(LgMenu m) {
        m.set(LgMenu.INFO_SLOT, Items.button(Material.BOW, Items.plain("Choisis deux amoureux", NamedTextColor.LIGHT_PURPLE),
                Items.lore("Clique sur deux joueurs (toi compris).", "Ils vivront et mourront ensemble."), false), null);
        fillHeads(m, alive(), gp -> {
            boolean picked = cupidPicks.contains(gp.uuid());
            return Items.head(gp.uuid(), gp.name(), Items.lore(picked ? "♥ Amoureux choisi" : "Clique pour le choisir"), 1, picked);
        }, gp -> {
            if (cupidPicks.size() >= 2) return;
            if (!cupidPicks.remove(gp.uuid())) cupidPicks.add(gp.uuid());
            refreshMenus();
            if (cupidPicks.size() == 2) {
                closeMenusLater();
                endPhaseLater(20);
            }
        });
    }

    private void stepLovers(GamePlayer a, GamePlayer b) {
        List<GamePlayer> lovers = List.of(a, b);
        broadcast(Component.text("☾ ", NamedTextColor.DARK_AQUA)
                .append(Component.text("Les amoureux se réveillent et se reconnaissent...", Role.CUPIDON.color())));
        for (GamePlayer gp : lovers) {
            Player pl = gp.player();
            if (pl == null) continue;
            GamePlayer other = gp == a ? b : a;
            blind(pl, false);
            pl.showTitle(Title.title(Component.text("♥ Coup de foudre ♥", Role.CUPIDON.color()),
                    Component.text("Tu es amoureux de " + other.name(), NamedTextColor.WHITE),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(80), Ticks.duration(10))));
            sound(pl, "entity.player.levelup", 1f, 1.5f);
            msg(pl, Component.text("Tu es amoureux de " + other.name() + ". Si l'un de vous meurt, l'autre meurt de chagrin. "
                    + "Si vous êtes les deux derniers survivants, vous gagnez ensemble.", Role.CUPIDON.color()));
        }
        runPhase(Phase.LOVERS, Component.text("Les amoureux", Role.CUPIDON.color()), timer("lovers"), BossBar.Color.PINK, () -> {
            for (GamePlayer gp : lovers) {
                Player pl = gp.player();
                if (pl != null && gp.alive) blind(pl, true);
            }
            nextNightStep();
        });
    }

    private void stepSeer() {
        List<GamePlayer> actors = aliveWith(Role.VOYANTE);
        roleStep(Phase.SEER, Role.VOYANTE, "La Voyante se réveille et sonde une âme", "seer", BossBar.Color.PURPLE,
                actors, (actor, pl) -> open(pl, new LgMenu(MenuTheme.SEER, m -> renderSeer(m, actor))), () -> {});
    }

    private void renderSeer(LgMenu m, GamePlayer seer) {
        m.set(LgMenu.INFO_SLOT, Items.button(Material.ENDER_EYE, Items.plain("Vision", NamedTextColor.LIGHT_PURPLE),
                Items.lore("Clique sur un joueur pour", "découvrir sa vraie carte."), false), null);
        List<GamePlayer> targets = alive().stream().filter(gp -> gp != seer).toList();
        fillHeads(m, targets, gp -> Items.head(gp.uuid(), gp.name(), Items.lore("Clique pour voir sa carte"), 1, false), gp -> {
            if (seerDone) return;
            seerDone = true;
            log("La Voyante découvre que " + gp.name() + " est " + gp.role.displayName(), Role.VOYANTE.color());
            Player pl = seer.player();
            later(() -> {
                if (pl == null) return;
                HoloMenu.close(pl);
                CardAnimator.reveal(plugin, pl, gp.role, Component.text(gp.name() + " est " + gp.role.displayName(), gp.role.color()));
                msg(pl, Component.text("Ta vision : " + gp.name() + " est ", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text(gp.role.displayName(), gp.role.color())));
            });
            endPhaseLater(110);
        });
    }

    private void stepGuard() {
        List<GamePlayer> actors = aliveWith(Role.SALVATEUR);
        roleStep(Phase.GUARD, Role.SALVATEUR, "Le Salvateur se réveille et protège quelqu'un", "guard", BossBar.Color.BLUE,
                actors, (actor, pl) -> open(pl, new LgMenu(MenuTheme.GUARD, m -> renderGuard(m, actor))), () -> {
                    if (!actors.isEmpty() && guardTarget == null) lastGuardTarget = null;
                });
    }

    private void renderGuard(LgMenu m, GamePlayer guard) {
        m.set(LgMenu.INFO_SLOT, Items.button(Material.SHIELD, Items.plain("Protection", NamedTextColor.AQUA),
                Items.lore("Clique sur le joueur à protéger cette nuit.", "Tu peux te protéger toi-même,",
                        "mais pas deux nuits de suite la même personne."), false), null);
        List<GamePlayer> targets = alive().stream().filter(gp -> !gp.uuid().equals(lastGuardTarget)).toList();
        fillHeads(m, targets, gp -> Items.head(gp.uuid(), gp.name(),
                Items.lore(gp.uuid().equals(guardTarget) ? "◆ Protégé cette nuit" : "Clique pour le protéger"), 1,
                gp.uuid().equals(guardTarget)), gp -> {
            if (guardTarget != null) return;
            guardTarget = gp.uuid();
            log("Le Salvateur protège " + gp.name(), Role.SALVATEUR.color());
            Player pl = guard.player();
            if (pl != null) {
                msg(pl, Component.text("Tu protèges " + gp.name() + " cette nuit.", NamedTextColor.AQUA));
                sound(pl, "item.shield.block", 1f, 1f);
            }
            m.render();
            closeMenusLater();
            endPhaseLater(25);
        });
    }

    private void stepWolves() {
        List<GamePlayer> actors = alive().stream().filter(GamePlayer::isWolf).toList();
        roleStep(Phase.WOLVES, Role.LOUP_GAROU, "Les Loups-Garous se réveillent et choisissent leur victime", "wolves",
                BossBar.Color.RED, actors, (actor, pl) -> {
                    open(pl, new LgMenu(MenuTheme.WOLVES, m -> renderWolves(m, actor)));
                    msg(pl, Component.text("Le chat est privé entre loups pendant votre tour.", NamedTextColor.RED));
                }, () -> {
                    UUID victim = tally(wolfVotes, true);
                    for (GamePlayer w : actors) {
                        Player pl = w.player();
                        if (pl == null) continue;
                        GamePlayer v = victim == null ? null : players.get(victim);
                        msg(pl, Component.text(v == null ? "La meute n'a choisi personne cette nuit." : "La meute dévore " + v.name() + ".",
                                NamedTextColor.RED));
                    }
                    GamePlayer target = victim == null ? null : players.get(victim);
                    if (!actors.isEmpty()) {
                        log(target == null ? "Les loups n'attaquent personne"
                                : "Les loups attaquent " + target.name()
                                + (victim.equals(guardTarget) ? " (sauvé par le Salvateur)" : ""), Role.LOUP_GAROU.color());
                    }
                    if (victim != null && victim.equals(guardTarget)) victim = null; // protégé par le Salvateur
                    wolfVictim = victim;
                    if (!actors.isEmpty()) {
                        for (GamePlayer gp : players.values()) {
                            Player p = gp.player();
                            if (p != null && !gp.left) sound(p, "entity.wolf.howl", 0.8f, 0.9f);
                        }
                    }
                });
    }

    private void renderWolves(LgMenu m, GamePlayer viewer) {
        UUID myVote = wolfVotes.get(viewer.uuid());
        m.set(LgMenu.INFO_SLOT, Items.button(Material.BONE, Items.plain("Choisissez une victime", NamedTextColor.RED),
                Items.lore("Votez ensemble. En cas d'égalité,", "la victime est tirée au sort.",
                        "Tu peux changer ton vote."), false), null);
        List<GamePlayer> targets = alive().stream().filter(gp -> !gp.isWolf()).toList();
        fillHeads(m, targets, gp -> {
            List<String> voters = votersFor(wolfVotes, gp.uuid());
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(voters.size() + " vote(s)", NamedTextColor.RED));
            for (String v : voters) lore.add(Items.plain(" • " + v, NamedTextColor.GRAY));
            lore.add(Items.plain(gp.uuid().equals(myVote) ? "▶ Ton choix" : "Clique pour voter", NamedTextColor.DARK_GRAY));
            return Items.head(gp.uuid(), gp.name(), lore, voters.size(), gp.uuid().equals(myVote));
        }, gp -> new LgMenu.Entry(null, votersFor(wolfVotes, gp.uuid()).size(),
                (int) alive().stream().filter(GamePlayer::isWolf).count(), voterList(wolfVotes, gp.uuid()),
                gp.uuid().equals(myVote), "Clique pour dévorer " + gp.name()), gp -> {
            wolfVotes.put(viewer.uuid(), gp.uuid());
            for (GamePlayer w : alive()) {
                if (!w.isWolf()) continue;
                Player pl = w.player();
                if (pl == null) continue;
                sound(pl, "entity.wolf.growl", 0.6f, 1f);
                msg(pl, Component.text(viewer.name() + " veut dévorer " + gp.name(), NamedTextColor.RED));
            }
            refreshMenus();
            List<GamePlayer> wolves = alive().stream().filter(GamePlayer::isWolf).toList();
            boolean allVoted = wolves.stream().allMatch(w -> wolfVotes.containsKey(w.uuid()));
            boolean unanimous = new HashSet<>(wolfVotes.values()).size() == 1;
            if (allVoted && unanimous) {
                closeMenusLater();
                endPhaseLater(40);
            }
        });
    }

    private void stepWitch() {
        List<GamePlayer> actors = aliveWith(Role.SORCIERE);
        List<GamePlayer> active = (healUsed && poisonUsed) ? List.of() : actors;
        roleStep(Phase.WITCH, Role.SORCIERE, "La Sorcière se réveille et prépare ses potions", "witch", BossBar.Color.GREEN,
                active, (actor, pl) -> open(pl, new LgMenu(MenuTheme.WITCH, m -> renderWitch(m, actor))), () -> {});
    }

    private void renderWitch(LgMenu m, GamePlayer witch) {
        GamePlayer victim = wolfVictim == null ? null : players.get(wolfVictim);
        if (victim != null) {
            m.set(LgMenu.INFO_SLOT, Items.head(victim.uuid(), victim.name(),
                    List.of(Items.plain("Victime des loups cette nuit", NamedTextColor.RED)), 1, false), null);
        } else {
            m.set(LgMenu.INFO_SLOT, Items.button(Material.BONE, Items.plain("Personne n'a été attaqué", NamedTextColor.GRAY),
                    null, false), null);
        }
        if (!healUsed && victim != null) {
            m.set(LgMenu.LEFT_BUTTON, Items.potion(Color.fromRGB(0x3DD17E),
                    Items.plain(witchHeal ? "Potion de vie : UTILISÉE" : "Potion de vie", NamedTextColor.GREEN),
                    Items.lore("Sauve " + victim.name() + ".", "Clique pour (dé)sélectionner."), witchHeal), () -> {
                witchHeal = !witchHeal;
                m.render();
            });
        } else {
            m.set(LgMenu.LEFT_BUTTON, Items.button(Material.GLASS_BOTTLE, Items.plain("Potion de vie", NamedTextColor.DARK_GRAY),
                    Items.lore(healUsed ? "Déjà utilisée." : "Personne à sauver."), false), null);
        }
        m.set(LgMenu.RIGHT_BUTTON, Items.button(Material.LIME_DYE, Items.plain("Terminer mon tour", NamedTextColor.GREEN),
                Items.lore("Valide tes choix et te rendort."), false), () -> {
            closeMenusLater();
            endPhaseLater(10);
        });
        if (poisonUsed) {
            m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.GLASS_BOTTLE, Items.plain("Potion de mort", NamedTextColor.DARK_GRAY),
                    Items.lore("Déjà utilisée."), false), null);
            return;
        }
        GamePlayer target = witchKill == null ? null : players.get(witchKill);
        m.set(LgMenu.MIDDLE_BUTTON, Items.potion(Color.fromRGB(0xC0392B),
                Items.plain("Potion de mort", NamedTextColor.RED),
                Items.lore(target == null ? "Clique sur une tête pour empoisonner." : "Cible : " + target.name(),
                        "Clique ici pour annuler."), target != null), () -> {
            witchKill = null;
            m.render();
        });
        List<GamePlayer> targets = alive().stream().filter(gp -> gp != witch).toList();
        fillHeads(m, targets, gp -> {
            boolean chosen = gp.uuid().equals(witchKill);
            return Items.head(gp.uuid(), gp.name(), Items.lore(chosen ? "☠ Sera empoisonné" : "Clique pour empoisonner"), 1, chosen);
        }, gp -> {
            witchKill = gp.uuid().equals(witchKill) ? null : gp.uuid();
            m.render();
        });
    }

    // ------------------------------------------------------------------ jour

    private void dawn() {
        if (state != State.PLAYING) return;
        closeMenus();
        List<UUID> deaths = new ArrayList<>();
        if (wolfVictim != null && !witchHeal) deaths.add(wolfVictim);
        if (witchHeal) {
            healUsed = true;
            GamePlayer saved = wolfVictim == null ? null : players.get(wolfVictim);
            if (saved != null) witchHealLog = "La Sorcière sauve " + saved.name() + " avec sa potion de vie";
        }
        if (witchKill != null) {
            poisonUsed = true;
            if (!deaths.contains(witchKill)) deaths.add(witchKill);
        }
        lastGuardTarget = guardTarget;
        day++;

        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            blind(p, false);
            p.showTitle(Title.title(Component.text("Jour " + day, NamedTextColor.GOLD),
                    Component.text("Le village se réveille...", NamedTextColor.YELLOW),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(40), Ticks.duration(10))));
            sound(p, "entity.chicken.ambient", 1f, 0.9f);
        }

        animateSky(22600, 24900, 60, null);
        if (witchHealLog != null) log(witchHealLog, Role.SORCIERE.color());
        if (witchKill != null) {
            GamePlayer k = players.get(witchKill);
            if (k != null) log("La Sorcière empoisonne " + k.name(), Role.SORCIERE.color());
        }
        witchHealLog = null;
        runPhase(Phase.DAWN, Component.text("Le village se réveille", NamedTextColor.GOLD), 4, BossBar.Color.YELLOW, () -> {
            if (deaths.isEmpty()) {
                broadcast(Component.text("Miracle ! Personne n'est mort cette nuit.", NamedTextColor.GREEN));
                afterDawn();
            } else {
                resolveDeaths(deaths, "a été retrouvé(e) mort(e) ce matin", this::afterDawn);
            }
        });
    }

    /** Premier jour : élection du Maire avant le débat. */
    private void afterDawn() {
        if (state != State.PLAYING) return;
        if (mayorEnabled() && !mayorElectionDone && alive().size() >= 3) startMayorElection();
        else startDiscussion();
    }

    private void startDiscussion() {
        if (state != State.PLAYING) return;
        mayorExtends = 0;
        GamePlayer m = mayorPlayer();
        broadcast(Component.text("Débattez ! Qui sont les loups ? Le vote commencera dans "
                + timer("discussion") + " s" + (m != null ? ", ou plus tôt si le Maire le décide." : "."), NamedTextColor.YELLOW));
        if (m != null && m.player() != null) {
            m.player().sendActionBar(Component.text("♛ Clic droit sur la cloche pour lancer le vote ou prolonger le débat",
                    NamedTextColor.GOLD));
        }
        runPhase(Phase.DISCUSSION, Component.text("Débat du village", NamedTextColor.YELLOW), timer("discussion"),
                BossBar.Color.GREEN, this::startVote);
    }

    // ------------------------------------------------------------------ Maire

    private boolean mayorEnabled() {
        return cfg().getBoolean("mayor.enabled", true);
    }

    private GamePlayer mayorPlayer() {
        GamePlayer gp = mayor == null ? null : players.get(mayor);
        return gp != null && gp.alive && !gp.left ? gp : null;
    }

    public boolean isMayor(Player p) {
        GamePlayer m = mayorPlayer();
        return m != null && m.uuid().equals(p.getUniqueId());
    }

    private int voteWeight(UUID voter) {
        return voter.equals(mayor) && cfg().getBoolean("mayor.double-vote", true) ? 2 : 1;
    }

    private void startMayorElection() {
        mayorElectionDone = true;
        mayorVotes.clear();
        broadcast(Component.text("♛ Élection du Maire ! Votez pour la personne qui dirigera les débats.", NamedTextColor.GOLD));
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            sound(p, "block.bell.resonate", 1f, 1.2f);
            if (!gp.alive) continue;
            p.showTitle(Title.title(Component.text("♛ Élection du Maire", NamedTextColor.GOLD),
                    Component.text("Sa voix comptera double et il gérera le temps", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(5), Ticks.duration(50), Ticks.duration(10))));
            open(p, new LgMenu(MenuTheme.MAYOR_VOTE, m -> renderMayorVote(m, gp)));
        }
        runPhase(Phase.MAYOR_ELECTION, Component.text("Élection du Maire", NamedTextColor.GOLD), timer("mayor"),
                BossBar.Color.YELLOW, this::endMayorElection);
    }

    private void renderMayorVote(LgMenu m, GamePlayer voter) {
        UUID myVote = mayorVotes.get(voter.uuid());
        int voted = (int) alive().stream().filter(gp -> mayorVotes.containsKey(gp.uuid())).count();
        m.set(LgMenu.INFO_SLOT, Items.button(Material.GOLDEN_HELMET, Items.plain("Qui sera le Maire ?", NamedTextColor.GOLD),
                List.of(Items.plain(voted + "/" + alive().size() + " ont voté", NamedTextColor.YELLOW),
                        Items.plain("Sa voix compte double, il départage les égalités", NamedTextColor.GRAY),
                        Items.plain("et peut lancer ou clore les votes.", NamedTextColor.GRAY)), false), null);
        fillHeads(m, alive(), gp -> {
            List<String> voters = votersFor(mayorVotes, gp.uuid());
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(voters.size() + " voix", NamedTextColor.GOLD));
            for (String v : voters) lore.add(Items.plain(" • " + v, NamedTextColor.GRAY));
            lore.add(Items.plain(gp.uuid().equals(myVote) ? "▶ Ton vote" : gp == voter ? "Voter pour toi" : "Clique pour l'élire",
                    NamedTextColor.DARK_GRAY));
            return Items.head(gp.uuid(), gp == voter ? gp.name() + " (toi)" : gp.name(), lore, voters.size(), gp.uuid().equals(myVote));
        }, gp -> new LgMenu.Entry(null, votersFor(mayorVotes, gp.uuid()).size(), (alive().size() + 1) / 2,
                voterList(mayorVotes, gp.uuid()), gp.uuid().equals(myVote),
                gp == voter ? "Clique pour voter pour toi" : "Clique pour élire " + gp.name()), gp -> {
            if (gp.uuid().equals(mayorVotes.get(voter.uuid()))) return;
            mayorVotes.put(voter.uuid(), gp.uuid());
            broadcast(Component.text(voter.name() + " vote pour ", NamedTextColor.GRAY).append(Component.text(gp.name(), NamedTextColor.GOLD)));
            refreshMenus();
            if (alive().stream().allMatch(a -> mayorVotes.containsKey(a.uuid()))) {
                broadcast(Component.text("Tout le monde a voté !", NamedTextColor.YELLOW));
                endPhaseLater(40);
            }
        });
    }

    private void endMayorElection() {
        if (state != State.PLAYING) return;
        closeMenus();
        UUID winner = tally(mayorVotes, true);
        GamePlayer gp = winner == null ? null : players.get(winner);
        boolean random = gp == null || !gp.alive;
        if (random) {
            List<GamePlayer> pool = alive();
            if (pool.isEmpty()) {
                startDiscussion();
                return;
            }
            gp = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        setMayor(gp, random ? "tiré(e) au sort (personne n'a voté)" : "élu(e) Maire");
        runPhase(Phase.REVEAL, Component.text("♛ " + gp.name() + " est Maire", NamedTextColor.GOLD), 4, BossBar.Color.YELLOW,
                this::startDiscussion);
    }

    private void setMayor(GamePlayer gp, String how) {
        mayor = gp.uuid();
        log("♛ " + gp.name() + " est " + how, NamedTextColor.GOLD);
        broadcast(Component.text("♛ " + gp.name() + " est " + how + " !", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        for (GamePlayer viewer : players.values()) {
            Player p = viewer.player();
            if (p == null || viewer.left) continue;
            p.showTitle(Title.title(Component.text("♛ " + gp.name(), NamedTextColor.GOLD),
                    Component.text("est le nouveau Maire", NamedTextColor.YELLOW),
                    Title.Times.times(Ticks.duration(5), Ticks.duration(50), Ticks.duration(10))));
            sound(p, "ui.toast.challenge_complete", 0.6f, 1.3f);
        }
        Player p = gp.player();
        if (p != null) {
            p.getInventory().setHelmet(Items.mayorHat());
            p.getInventory().setItem(0, Items.mayorItem());
            p.getInventory().setHeldItemSlot(0);
            msg(p, Component.text("Tu es le Maire ! Ta voix compte double et départage les égalités. Clic droit sur la cloche "
                    + "pour lancer le vote plus tôt, prolonger le débat ou clore le vote.", NamedTextColor.GOLD));
        }
    }

    /** Clic droit sur la cloche du Maire. */
    private void openMayorPanel(Player p) {
        if (!isMayor(p)) {
            msg(p, Component.text("Tu n'es pas (ou plus) le Maire.", NamedTextColor.GRAY));
            return;
        }
        if (p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            msg(p, Component.text("Le Maire dort aussi la nuit...", NamedTextColor.GRAY));
            return;
        }
        HoloMenu.open(p, withTimer(new LgMenu(MenuTheme.MAYOR, Component.text("Maire"), m -> renderMayorPanel(m, p))), this::handleMenuClick);
    }

    private void renderMayorPanel(LgMenu m, Player p) {
        int maxExt = cfg().getInt("mayor.max-extends", 2);
        int ext = cfg().getInt("mayor.extend-seconds", 30);
        String phaseLabel = phase == Phase.DISCUSSION ? "débat" : phase == Phase.VOTE ? "vote" : "rien à gérer pour l'instant";
        m.set(LgMenu.INFO_SLOT, Items.button(Material.GOLDEN_HELMET, Items.plain("Tu diriges le village", NamedTextColor.GOLD),
                List.of(Items.plain("Phase : " + phaseLabel, NamedTextColor.YELLOW),
                        Items.plain("Temps restant : " + timeLeft() + " s", NamedTextColor.GRAY),
                        Items.plain("Ta voix compte double au vote.", NamedTextColor.GRAY)), false), null);
        if (phase == Phase.DISCUSSION) {
            m.set(LgMenu.LEFT_BUTTON, Items.button(Material.BELL, Items.plain("Lancer le vote maintenant", NamedTextColor.GOLD),
                    Items.lore("Coupe le débat et ouvre le vote."), false), () -> {
                broadcast(Component.text("♛ Le Maire lance le vote !", NamedTextColor.GOLD));
                later(() -> HoloMenu.close(p));
                endPhaseLater(5);
            });
            boolean canExtend = mayorExtends < maxExt;
            m.set(LgMenu.RIGHT_BUTTON, Items.button(canExtend ? Material.CLOCK : Material.GRAY_DYE,
                    Items.plain("+" + ext + " s de débat", canExtend ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY),
                    Items.lore(canExtend ? "Encore " + (maxExt - mayorExtends) + " fois possible." : "Plus de prolongation possible."),
                    false), () -> {
                if (mayorExtends >= maxExt || phase != Phase.DISCUSSION) return;
                mayorExtends++;
                timeLeft += ext;
                phaseTotal = Math.max(phaseTotal, timeLeft);
                updateBossBar();
                broadcast(Component.text("♛ Le Maire prolonge le débat de " + ext + " s.", NamedTextColor.GOLD));
                m.render();
            });
        } else if (phase == Phase.VOTE) {
            m.set(LgMenu.LEFT_BUTTON, Items.button(Material.LIGHTNING_ROD, Items.plain("Clore le vote", NamedTextColor.RED),
                    Items.lore("Compte les voix tout de suite."), false), () -> closeVoteByMayor(p));
        }
        m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER, Items.plain("Fermer", NamedTextColor.RED), null, false),
                () -> later(() -> HoloMenu.close(p)));
    }

    private void closeVoteByMayor(Player p) {
        if (phase != Phase.VOTE) return;
        broadcast(Component.text("♛ Le Maire clôt le vote !", NamedTextColor.GOLD));
        later(() -> HoloMenu.close(p));
        endPhaseLater(10);
    }

    /** Le Maire est mort : il désigne son successeur. */
    private void runSuccession(Runnable then) {
        GamePlayer old = players.get(mayor);
        List<GamePlayer> pool = alive();
        if (pool.isEmpty()) {
            mayor = null;
            then.run();
            return;
        }
        Player pl = old == null ? null : old.player();
        if (pl == null || old.left) {
            setMayor(pool.get(ThreadLocalRandom.current().nextInt(pool.size())), "tiré(e) au sort comme nouveau Maire");
            then.run();
            return;
        }
        successorPick = null;
        broadcast(Component.text("♛ Le Maire " + old.name() + " désigne son successeur...", NamedTextColor.GOLD));
        pl.showTitle(Title.title(Component.text("♛ Ta succession", NamedTextColor.GOLD),
                Component.text("Choisis le prochain Maire", NamedTextColor.GRAY),
                Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(5))));
        open(pl, new LgMenu(MenuTheme.SUCCESSION, mm -> {
            mm.set(LgMenu.INFO_SLOT, Items.button(Material.GOLDEN_HELMET, Items.plain("Désigne ton successeur", NamedTextColor.GOLD),
                    Items.lore("Il héritera de ta voix double", "et de tes pouvoirs."), false), null);
            fillHeads(mm, alive(), gp -> Items.head(gp.uuid(), gp.name(), Items.lore("Clique pour lui donner la couronne"), 1,
                    gp.uuid().equals(successorPick)), gp -> {
                if (successorPick != null) return;
                successorPick = gp.uuid();
                closeMenusLater();
                endPhaseLater(10);
            });
        }));
        runPhase(Phase.SUCCESSION, Component.text("Succession du Maire", NamedTextColor.GOLD), timer("succession"),
                BossBar.Color.YELLOW, () -> {
                    closeMenus();
                    Player online = old.player();
                    if (online != null) makeSpectator(online);
                    GamePlayer next = successorPick == null ? null : players.get(successorPick);
                    List<GamePlayer> alive = alive();
                    if (alive.isEmpty()) {
                        mayor = null;
                        then.run();
                        return;
                    }
                    if (next == null || !next.alive) {
                        setMayor(alive.get(ThreadLocalRandom.current().nextInt(alive.size())), "tiré(e) au sort comme nouveau Maire");
                    } else {
                        setMayor(next, "désigné(e) Maire par " + old.name());
                    }
                    then.run();
                });
    }

    /** Admin : saute le débat. */
    public void skipPhase() {
        if (phaseEnd != null) endPhaseLater(1);
    }

    private void startVote() {
        if (state != State.PLAYING) return;
        dayVotes.clear();
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            sound(p, "block.bell.use", 1f, 1f);
            if (!gp.alive) continue;
            p.showTitle(Title.title(Component.text("Aux votes !", NamedTextColor.GOLD),
                    Component.text("Choisis qui éliminer", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(5), Ticks.duration(30), Ticks.duration(5))));
            open(p, new LgMenu(MenuTheme.VOTE, m -> renderVote(m, gp)));
        }
        runPhase(Phase.VOTE, Component.text("Vote du village", NamedTextColor.GOLD), timer("vote"), BossBar.Color.YELLOW, this::endVote);
    }

    private void renderVote(LgMenu m, GamePlayer voter) {
        UUID myVote = dayVotes.get(voter.uuid());
        int voted = (int) alive().stream().filter(gp -> dayVotes.containsKey(gp.uuid())).count();
        m.set(LgMenu.INFO_SLOT, Items.button(Material.BELL, Items.plain("Qui est le loup ?", NamedTextColor.GOLD),
                List.of(Items.plain(voted + "/" + alive().size() + " ont voté", NamedTextColor.YELLOW),
                        Items.plain("Le joueur avec le plus de voix est éliminé.", NamedTextColor.GRAY),
                        Items.plain("En cas d'égalité, personne ne meurt.", NamedTextColor.GRAY)), false), null);
        List<GamePlayer> targets = alive().stream().filter(gp -> gp != voter).toList();
        fillHeads(m, targets, gp -> {
            List<String> voters = votersFor(dayVotes, gp.uuid());
            int weight = dayVotes.entrySet().stream().filter(e -> e.getValue().equals(gp.uuid()))
                    .mapToInt(e -> voteWeight(e.getKey())).sum();
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(weight + " voix", NamedTextColor.GOLD));
            for (String v : voters) lore.add(Items.plain(" • " + v, NamedTextColor.GRAY));
            lore.add(Items.plain(gp.uuid().equals(myVote) ? "▶ Ton vote" : "Clique pour voter contre", NamedTextColor.DARK_GRAY));
            String name = gp.uuid().equals(mayor) ? "♛ " + gp.name() : gp.name();
            return Items.head(gp.uuid(), name, lore, weight, gp.uuid().equals(myVote));
        }, gp -> {
            int weight = dayVotes.entrySet().stream().filter(e -> e.getValue().equals(gp.uuid()))
                    .mapToInt(e -> voteWeight(e.getKey())).sum();
            // la jauge est pleine à la majorité des voix
            int max = (alive().stream().mapToInt(a -> voteWeight(a.uuid())).sum() + 1) / 2;
            return new LgMenu.Entry(null, weight, max, voterList(dayVotes, gp.uuid()), gp.uuid().equals(myVote),
                    "Clique pour voter contre " + gp.name());
        }, gp -> {
            if (gp.uuid().equals(dayVotes.get(voter.uuid()))) return;
            dayVotes.put(voter.uuid(), gp.uuid());
            broadcast(Component.text(voter.name() + " vote contre ", NamedTextColor.GRAY)
                    .append(Component.text(gp.name(), NamedTextColor.GOLD)));
            onVoteChanged();
        });
        m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER,
                Items.plain(myVote == null ? "Je ne vote pas" : "Retirer mon vote", NamedTextColor.RED),
                Items.lore("Abstention."), false), () -> {
            if (dayVotes.remove(voter.uuid()) != null) {
                broadcast(Component.text(voter.name() + " retire son vote.", NamedTextColor.GRAY));
                onVoteChanged();
            }
        });
        if (voter.uuid().equals(mayor)) {
            m.set(LgMenu.RIGHT_BUTTON, Items.button(Material.LIGHTNING_ROD, Items.plain("♛ Clore le vote", NamedTextColor.GOLD),
                    Items.lore("Pouvoir du Maire :", "compte les voix maintenant."), false), () -> {
                Player p = voter.player();
                if (p != null) closeVoteByMayor(p);
            });
        }
    }

    private void onVoteChanged() {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null && !gp.left) sound(p, "ui.button.click", 0.5f, 1.4f);
        }
        refreshMenus();
        if (alive().stream().allMatch(gp -> dayVotes.containsKey(gp.uuid()))) {
            broadcast(Component.text("Tout le monde a voté !", NamedTextColor.YELLOW));
            endPhaseLater(60);
        }
    }

    private void endVote() {
        if (state != State.PLAYING) return;
        closeMenus();
        UUID eliminated = tallyDay();
        GamePlayer out = eliminated == null ? null : players.get(eliminated);
        log(out == null ? "Le village ne parvient pas à éliminer quelqu'un"
                : "Le village élimine " + out.name(), NamedTextColor.YELLOW);
        if (eliminated == null) {
            broadcast(Component.text(dayVotes.isEmpty() ? "Personne n'a voté. Le village reste indécis..."
                    : "Égalité ! Le village n'arrive pas à se décider.", NamedTextColor.YELLOW));
            runPhase(Phase.REVEAL, Component.text("Le soleil se couche", NamedTextColor.GOLD), 3, BossBar.Color.YELLOW, this::startNight);
            return;
        }
        resolveDeaths(List.of(eliminated), "a été éliminé(e) par le village", this::startNight);
    }

    // ------------------------------------------------------------------ morts

    private void resolveDeaths(List<UUID> initial, String how, Runnable then) {
        List<GamePlayer> dead = new ArrayList<>();
        Set<GamePlayer> sorrow = new HashSet<>();
        for (UUID id : initial) collectDeath(id, dead, sorrow, false);
        if (dead.isEmpty()) {
            continueIfAlive(then);
            return;
        }
        for (GamePlayer gp : dead) {
            gp.alive = false;
            dayVotes.remove(gp.uuid());
        }

        for (int i = 0; i < dead.size(); i++) {
            GamePlayer gp = dead.get(i);
            String reason = sorrow.contains(gp) ? "est mort(e) de chagrin" : how;
            Bukkit.getScheduler().runTaskLater(plugin, () -> revealDeath(gp, reason), i * 80L);
        }
        Deque<GamePlayer> hunters = new ArrayDeque<>();
        for (GamePlayer gp : dead) if (gp.role == Role.CHASSEUR && !gp.left) hunters.add(gp);

        runPhase(Phase.REVEAL, Component.text("Révélations", NamedTextColor.RED), dead.size() * 4 + 1, BossBar.Color.RED,
                () -> runHunters(hunters, () -> continueIfAlive(then)));
    }

    private void collectDeath(UUID id, List<GamePlayer> dead, Set<GamePlayer> sorrow, boolean grief) {
        GamePlayer gp = players.get(id);
        if (gp == null || !gp.alive || dead.contains(gp)) return;
        dead.add(gp);
        if (grief) sorrow.add(gp);
        if (gp.lover != null) collectDeath(gp.lover, dead, sorrow, true);
    }

    private void revealDeath(GamePlayer gp, String reason) {
        if (state != State.PLAYING) return;
        history.add(Component.text("   ☠ " + gp.name() + " (" + gp.role.displayName() + ") " + reason, NamedTextColor.GRAY));
        Player victim = gp.player();
        Location at = victim != null ? victim.getLocation() : gp.seat;
        if (at != null && at.getWorld() != null) at.getWorld().strikeLightningEffect(at);
        broadcast(Component.text("☠ " + gp.name() + " " + reason + ". C'était ", NamedTextColor.RED)
                .append(Component.text(gp.role.displayName(), gp.role.color()).decorate(TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.RED)));
        for (GamePlayer viewer : players.values()) {
            Player p = viewer.player();
            if (p == null || viewer.left) continue;
            sound(p, "entity.lightning_bolt.thunder", 0.6f, 1f);
            CardAnimator.reveal(plugin, p, gp.role, Component.text(gp.name() + " — " + gp.role.displayName(), gp.role.color()));
        }
        if (gp.bot) killBotBody(gp);
        Player dead = gp.player();
        boolean mustAct = gp.role == Role.CHASSEUR || gp.uuid().equals(mayor);
        if (dead != null && !mustAct) makeSpectator(dead);
        if (dead != null && mustAct) blind(dead, false);
    }

    private void makeSpectator(Player p) {
        blind(p, false);
        HoloMenu.close(p);
        p.closeInventory();
        p.setGameMode(GameMode.SPECTATOR);
        msg(p, Component.text("Tu es mort. Tu regardes la partie à travers les yeux des survivants : clic gauche = joueur suivant, "
                + "accroupi (shift) = joueur précédent. Ton chat n'est visible que des morts.", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR && state == State.PLAYING) startWatching(p);
        }, 60L);
    }

    // ------------------------------------------------------------------ spectateurs (vue à la 1re personne)

    private List<GamePlayer> watchable() {
        List<GamePlayer> list = new ArrayList<>(alive());
        list.removeIf(gp -> gp.player() == null && (gp.body == null || !gp.body.isValid()));
        list.sort(Comparator.comparing(GamePlayer::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void startWatching(Player p) {
        List<GamePlayer> list = watchable();
        if (list.isEmpty()) return;
        watch(p, list.get(0));
    }

    private void watch(Player p, GamePlayer target) {
        Player t = target.player();
        org.bukkit.entity.Entity cam = t != null ? t : target.body;
        if (cam == null || !cam.isValid() || cam.equals(p)) return;
        watching.put(p.getUniqueId(), target.uuid());
        if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(cam);
        if (t != null) HoloMenu.watch(p, t);
        else HoloMenu.unwatch(p);
        Component sub = Component.text(target.role.displayName(), target.role.color())
                .append(Component.text(target.role.team() == Role.Team.WOLVES ? " · Camp des loups" : " · Camp du village",
                        NamedTextColor.GRAY));
        p.showTitle(Title.title(Component.text("◉ " + target.name(), NamedTextColor.WHITE), sub,
                Title.Times.times(Ticks.duration(0), Ticks.duration(35), Ticks.duration(10))));
        sound(p, "item.spyglass.use", 0.6f, 1.2f);
        sendSpectatorBar(p, target);
    }

    private void stopWatching(Player p) {
        if (watching.remove(p.getUniqueId()) == null) return;
        HoloMenu.unwatch(p);
        if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
    }

    /** Clic gauche (+1) / shift (-1) d'un spectateur. */
    public void spectatorCycle(Player p, int dir) {
        if (!watching.containsKey(p.getUniqueId()) && !isSpectatorCandidate(p)) return;
        int now = Bukkit.getCurrentTick();
        Integer last = specClick.get(p.getUniqueId());
        if (last != null && now - last < 4) return;
        specClick.put(p.getUniqueId(), now);
        List<GamePlayer> list = watchable();
        if (list.isEmpty()) return;
        UUID current = watching.get(p.getUniqueId());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid().equals(current)) idx = i;
        int next = Math.floorMod(idx + dir, list.size());
        watch(p, list.get(next));
    }

    private boolean isSpectatorCandidate(Player p) {
        GamePlayer gp = get(p);
        return state == State.PLAYING && ((gp != null && !gp.alive && !gp.left) || outsiders.containsKey(p.getUniqueId()))
                && p.getGameMode() == GameMode.SPECTATOR;
    }

    public boolean isWatcher(Player p) {
        return watching.containsKey(p.getUniqueId());
    }

    /** Le spectateur a appuyé sur shift (le jeu veut le sortir de la vue) : on passe au précédent. */
    public boolean handleStopSpectating(Player p) {
        if (!watching.containsKey(p.getUniqueId())) return false;
        Bukkit.getScheduler().runTask(plugin, () -> spectatorCycle(p, -1));
        return true;
    }

    /** Clic sur une entité en mode spectateur : accepté seulement si c'est un survivant de la partie. */
    public boolean allowSpectate(Player p, org.bukkit.entity.Entity target) {
        if (!watching.containsKey(p.getUniqueId())) return true;
        if (target instanceof Player t) {
            GamePlayer gp = get(t);
            if (gp != null && gp.alive && !gp.left) {
                if (!gp.uuid().equals(watching.get(p.getUniqueId()))) {
                    specClick.put(p.getUniqueId(), Bukkit.getCurrentTick());
                    Bukkit.getScheduler().runTask(plugin, () -> watch(p, gp));
                }
                return true;
            }
        }
        for (GamePlayer gp : players.values()) {
            if (gp.bot && target.equals(gp.body) && gp.alive) {
                if (!gp.uuid().equals(watching.get(p.getUniqueId()))) {
                    specClick.put(p.getUniqueId(), Bukkit.getCurrentTick());
                    Bukkit.getScheduler().runTask(plugin, () -> watch(p, gp));
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ bots de test (admin)

    private static final String[] BOT_NAMES = {"Bot_Albert", "Bot_Berthe", "Bot_Camille", "Bot_Dede", "Bot_Eloise",
            "Bot_Fifi", "Bot_Gaston", "Bot_Huguette", "Bot_Igor", "Bot_Josette", "Bot_Kiki", "Bot_Lulu", "Bot_Marcel",
            "Bot_Nini", "Bot_Odette", "Bot_Pepe"};

    public int botCount() {
        return (int) players.values().stream().filter(gp -> gp.bot).count();
    }

    /** Ajoute des faux joueurs dans la file : ils jouent tout seuls (votes et pouvoirs au hasard). */
    public int addBots(int count) {
        if (state != State.LOBBY && state != State.STARTING) return -1;
        int added = 0;
        for (String name : BOT_NAMES) {
            if (added >= count || players.size() >= arena.maxPlayers()) break;
            UUID id = UUID.nameUUIDFromBytes(("lgbot-" + arena.id() + "-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (players.containsKey(id)) continue;
            players.put(id, new GamePlayer(id, name));
            added++;
        }
        if (added > 0) {
            broadcast(Component.text(added + " bot(s) de test rejoignent « " + arena.displayName() + " » ", NamedTextColor.AQUA)
                    .append(Component.text("(" + players.size() + "/" + arena.maxPlayers() + ")", NamedTextColor.GRAY)));
            checkAutoStart();
        }
        return added;
    }

    /** Retire les bots de la file (avant la partie). */
    public int removeBots() {
        if (state != State.LOBBY && state != State.STARTING) return -1;
        int before = players.size();
        players.values().removeIf(gp -> gp.bot);
        int removed = before - players.size();
        if (state == State.STARTING && (players.isEmpty() || (!forced && players.size() < cfg().getInt("min-players", 5)))) {
            broadcast(Component.text("Pas assez de joueurs, lancement annulé. En attente...", NamedTextColor.RED));
            cancelCountdown();
        }
        return removed;
    }

    private void spawnBotBody(GamePlayer gp) {
        Location at = gp.seat;
        if (at == null || at.getWorld() == null) return;
        gp.body = at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, m -> {
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setImmovable(true);
            m.setGravity(false);
            m.customName(Component.text(gp.name(), NamedTextColor.AQUA));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text("bot de test", NamedTextColor.GRAY));
        });
    }

    private void killBotBody(GamePlayer gp) {
        if (gp.body == null) return;
        gp.body.getWorld().spawnParticle(org.bukkit.Particle.SOUL, gp.body.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        gp.body.remove();
        gp.body = null;
    }

    /** Position d'un joueur ou du mannequin d'un bot (pour les rayons de vote). */
    private Location locOf(GamePlayer gp) {
        Player p = gp.player();
        if (p != null) return p.getLocation();
        if (gp.body != null && gp.body.isValid()) return gp.body.getLocation();
        return null;
    }

    private GamePlayer randomOf(List<GamePlayer> pool) {
        return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** À chaque phase, les bots vivants jouent après un petit délai (comme un vrai joueur qui réfléchit). */
    private void scheduleBots(Phase p) {
        if (state != State.PLAYING) return;
        int token = phaseToken;
        for (GamePlayer bot : players.values()) {
            if (!bot.bot || !bot.alive) continue;
            long delay = 40 + ThreadLocalRandom.current().nextInt(100);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (token == phaseToken && state == State.PLAYING && bot.alive) botAct(bot, p);
            }, delay);
        }
    }

    private void botAct(GamePlayer bot, Phase p) {
        List<GamePlayer> others = alive().stream().filter(gp -> gp != bot).toList();
        switch (p) {
            case CUPID -> {
                if (bot.role != Role.CUPIDON || cupidPicks.size() >= 2) return;
                List<GamePlayer> pool = new ArrayList<>(alive());
                Collections.shuffle(pool);
                for (GamePlayer gp : pool) {
                    if (cupidPicks.size() >= 2) break;
                    if (!cupidPicks.contains(gp.uuid())) cupidPicks.add(gp.uuid());
                }
                endPhaseLater(20);
            }
            case SEER -> {
                if (bot.role != Role.VOYANTE || seerDone) return;
                seerDone = true;
                GamePlayer gp = randomOf(others);
                if (gp != null) log("La Voyante découvre que " + gp.name() + " est " + gp.role.displayName(), Role.VOYANTE.color());
                endPhaseLater(20);
            }
            case GUARD -> {
                if (bot.role != Role.SALVATEUR || guardTarget != null) return;
                GamePlayer gp = randomOf(alive().stream().filter(o -> !o.uuid().equals(lastGuardTarget)).toList());
                if (gp == null) return;
                guardTarget = gp.uuid();
                log("Le Salvateur protège " + gp.name(), Role.SALVATEUR.color());
                endPhaseLater(20);
            }
            case WOLVES -> {
                if (!bot.isWolf() || wolfVotes.containsKey(bot.uuid())) return;
                // suit la meute si un loup a déjà choisi, sinon choisit au hasard
                UUID pick = wolfVotes.values().stream().findFirst().orElse(null);
                GamePlayer gp = pick != null ? players.get(pick) : randomOf(alive().stream().filter(o -> !o.isWolf()).toList());
                if (gp == null) return;
                wolfVotes.put(bot.uuid(), gp.uuid());
                refreshMenus();
                List<GamePlayer> wolves = alive().stream().filter(GamePlayer::isWolf).toList();
                if (wolves.stream().allMatch(w -> wolfVotes.containsKey(w.uuid())) && new HashSet<>(wolfVotes.values()).size() == 1) {
                    closeMenusLater();
                    endPhaseLater(40);
                }
            }
            case WITCH -> {
                if (bot.role != Role.SORCIERE || (healUsed && poisonUsed)) return;
                if (!healUsed && wolfVictim != null && ThreadLocalRandom.current().nextInt(3) == 0) witchHeal = true;
                endPhaseLater(20);
            }
            case MAYOR_ELECTION -> {
                if (mayorVotes.containsKey(bot.uuid())) return;
                GamePlayer gp = randomOf(alive());
                if (gp == null) return;
                mayorVotes.put(bot.uuid(), gp.uuid());
                refreshMenus();
                if (alive().stream().allMatch(a -> mayorVotes.containsKey(a.uuid()))) endPhaseLater(40);
            }
            case VOTE -> {
                if (dayVotes.containsKey(bot.uuid())) return;
                GamePlayer gp = randomOf(others);
                if (gp == null) return;
                dayVotes.put(bot.uuid(), gp.uuid());
                broadcast(Component.text(bot.name() + " vote contre ", NamedTextColor.GRAY)
                        .append(Component.text(gp.name(), NamedTextColor.GOLD)));
                onVoteChanged();
            }
            case SUCCESSION -> {
                if (!bot.uuid().equals(mayor) || successorPick != null) return;
                GamePlayer gp = randomOf(others);
                if (gp == null) return;
                successorPick = gp.uuid();
                endPhaseLater(10);
            }
            default -> { }
        }
    }

    private void sendSpectatorBar(Player p, GamePlayer target) {
        Component bar = Component.text("◉ " + target.name() + " — ", NamedTextColor.WHITE)
                .append(Component.text(target.role.displayName(), target.role.color()));
        if (target.uuid().equals(mayor)) bar = bar.append(Component.text(" ♛", NamedTextColor.GOLD));
        if (target.lover != null) {
            GamePlayer l = players.get(target.lover);
            if (l != null) bar = bar.append(Component.text(" ♥ " + l.name(), Role.CUPIDON.color()));
        }
        bar = bar.append(Component.text("   ◀ shift · clic gauche ▶", NamedTextColor.DARK_GRAY));
        p.sendActionBar(bar);
    }

    /** Toutes les secondes : recolle la caméra, change de cible si le joueur suivi est mort. */
    private void maintainSpectators() {
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(watching.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                watching.remove(e.getKey());
                continue;
            }
            GamePlayer target = players.get(e.getValue());
            org.bukkit.entity.Entity cam = target == null ? null : target.player() != null ? target.player() : target.body;
            if (target == null || !target.alive || target.left || cam == null || !cam.isValid()) {
                List<GamePlayer> list = watchable();
                if (list.isEmpty()) continue;
                watch(p, list.get(0));
                continue;
            }
            if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
            if (!cam.equals(p.getSpectatorTarget())) p.setSpectatorTarget(cam);
            sendSpectatorBar(p, target);
        }
    }

    // ------------------------------------------------------------------ spectateurs extérieurs (/lg spectate)

    public boolean isOutsider(Player p) {
        return outsiders.containsKey(p.getUniqueId());
    }

    public void spectate(Player p) {
        if (state != State.PLAYING) {
            msg(p, Component.text("Aucune partie en cours dans « " + arena.displayName() + " ».", NamedTextColor.RED));
            return;
        }
        if (isInGame(p) || isOutsider(p)) {
            msg(p, Component.text("Tu regardes déjà cette partie.", NamedTextColor.YELLOW));
            return;
        }
        outsiders.put(p.getUniqueId(), new PlayerSnapshot(p));
        p.closeInventory();
        HoloMenu.close(p);
        p.setGameMode(GameMode.SPECTATOR);
        Location first = center != null ? center.clone().add(0, 3, 0) : null;
        if (first != null) p.teleport(first);
        if (bossBar != null) p.showBossBar(bossBar);
        msg(p, Component.text("Tu regardes « " + arena.displayName() + " » : clic gauche = joueur suivant, shift = précédent. "
                + "/lg leave pour partir.", NamedTextColor.GRAY));
        startWatching(p);
    }

    public void stopSpectate(Player p) {
        PlayerSnapshot snap = outsiders.remove(p.getUniqueId());
        if (snap == null) return;
        stopWatching(p);
        HoloMenu.close(p);
        hideBossBar(p);
        snap.restore(p);
        manager.refreshJoinItemLater(p);
    }

    /** Chat des spectateurs extérieurs : visible seulement des morts et des autres spectateurs. */
    public void handleOutsiderChat(AsyncChatEvent e) {
        e.viewers().removeIf(a -> a instanceof Player v && !isDeadSpectator(v) && !outsiders.containsKey(v.getUniqueId()));
        e.renderer((source, name, message, viewer) -> Component.text("[Spectateur] ", NamedTextColor.DARK_GRAY)
                .append(name.color(NamedTextColor.GRAY)).append(Component.text(": ", NamedTextColor.DARK_GRAY))
                .append(message.color(NamedTextColor.GRAY)));
    }

    private void runHunters(Deque<GamePlayer> hunters, Runnable then) {
        if (state != State.PLAYING) return;
        GamePlayer hunter = hunters.poll();
        if (hunter == null) {
            then.run();
            return;
        }
        Player pl = hunter.player();
        if (hunter.bot && !alive().isEmpty()) {
            List<GamePlayer> pool = alive();
            GamePlayer shot = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            broadcast(Component.text("Le Chasseur " + hunter.name() + " (bot) tire sur " + shot.name() + " !", Role.CHASSEUR.color()));
            log("Le Chasseur " + hunter.name() + " abat " + shot.name(), Role.CHASSEUR.color());
            runPhase(Phase.HUNTER, Component.text("Le Chasseur vise", Role.CHASSEUR.color()), 3, BossBar.Color.RED,
                    () -> resolveDeaths(List.of(shot.uuid()), "a été abattu(e) par le Chasseur", () -> runHunters(hunters, then)));
            return;
        }
        if (pl == null || alive().isEmpty()) {
            if (pl != null && !hunter.uuid().equals(mayor)) makeSpectator(pl);
            runHunters(hunters, then);
            return;
        }
        hunterShot = null;
        broadcast(Component.text("Le Chasseur " + hunter.name() + " épaule son fusil pour un dernier tir...", Role.CHASSEUR.color()));
        pl.showTitle(Title.title(Component.text("Dernier tir !", Role.CHASSEUR.color()),
                Component.text("Choisis qui emporter avec toi", NamedTextColor.GRAY),
                Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(5))));
        open(pl, new LgMenu(MenuTheme.HUNTER, m -> renderHunter(m, hunter)));
        runPhase(Phase.HUNTER, Component.text("Le Chasseur vise", Role.CHASSEUR.color()), timer("hunter"), BossBar.Color.RED, () -> {
            closeMenus();
            Player online = hunter.player();
            if (online != null && !hunter.uuid().equals(mayor)) makeSpectator(online);
            if (hunterShot != null) {
                GamePlayer shot = players.get(hunterShot);
                if (shot != null) log("Le Chasseur " + hunter.name() + " abat " + shot.name(), Role.CHASSEUR.color());
                for (GamePlayer gp : players.values()) {
                    Player p = gp.player();
                    if (p != null && !gp.left) sound(p, "entity.generic.explode", 0.7f, 1.6f);
                }
                resolveDeaths(List.of(hunterShot), "a été abattu(e) par le Chasseur", () -> runHunters(hunters, then));
            } else {
                broadcast(Component.text("Le Chasseur n'a pas tiré.", NamedTextColor.GRAY));
                runHunters(hunters, then);
            }
        });
    }

    private void renderHunter(LgMenu m, GamePlayer hunter) {
        m.set(LgMenu.INFO_SLOT, Items.button(Material.CROSSBOW, Items.plain("Dernière balle", Role.CHASSEUR.color()),
                Items.lore("Clique sur le joueur à abattre."), false), null);
        fillHeads(m, alive(), gp -> Items.head(gp.uuid(), gp.name(), Items.lore("Clique pour tirer"), 1, false), gp -> {
            if (hunterShot != null) return;
            hunterShot = gp.uuid();
            closeMenusLater();
            endPhaseLater(10);
        });
    }

    private void continueIfAlive(Runnable then) {
        if (state != State.PLAYING) return;
        Winner winner = checkWinner();
        if (winner != null) {
            endGame(winner);
            return;
        }
        GamePlayer m = mayor == null ? null : players.get(mayor);
        if (m != null && !m.alive) runSuccession(then);
        else then.run();
    }

    private Winner checkWinner() {
        List<GamePlayer> alive = alive();
        if (alive.isEmpty()) return Winner.NOBODY;
        if (alive.size() == 2) {
            GamePlayer a = alive.get(0), b = alive.get(1);
            if (b.uuid().equals(a.lover)) return Winner.LOVERS;
        }
        long wolves = alive.stream().filter(GamePlayer::isWolf).count();
        if (wolves == 0) return Winner.VILLAGE;
        if (wolves >= alive.size() - wolves) return Winner.WOLVES;
        return null;
    }

    // ------------------------------------------------------------------ fin

    private void endGame(Winner winner) {
        state = State.ENDING;
        closeMenus();
        Component title = switch (winner) {
            case VILLAGE -> Component.text("Victoire du Village !", NamedTextColor.GREEN);
            case WOLVES -> Component.text("Victoire des Loups !", NamedTextColor.RED);
            case LOVERS -> Component.text("Victoire des Amoureux !", Role.CUPIDON.color());
            case NOBODY -> Component.text("Personne ne survit...", NamedTextColor.GRAY);
        };
        broadcast(title.decorate(TextDecoration.BOLD));
        broadcast(Component.text("Les cartes étaient :", NamedTextColor.GRAY));
        for (GamePlayer gp : players.values()) {
            broadcast(Component.text((gp.alive ? " ✔ " : " ✘ ") + (gp.uuid().equals(mayor) ? "♛ " : "") + gp.name() + " — ",
                    gp.alive ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                    .append(Component.text(gp.role.displayName() + (gp.lover != null ? " ♥" : ""), gp.role.color())));
        }
        clearVoteTags();
        for (UUID id : new ArrayList<>(watching.keySet())) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) stopWatching(sp);
        }
        animateSky(23000, 25000, 40, null);
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            blind(p, false);
            // tout le monde revient à sa place pour voir le résumé
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
            if (gp.seat != null) p.teleport(gp.seat);
            p.showTitle(Title.title(title, Component.text("Merci d'avoir joué !", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(60), Ticks.duration(20))));
            sound(p, "ui.toast.challenge_complete", 1f, 1f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.ENDING) return;
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null && !gp.left) HoloMenu.open(p, summaryMenu(winner, title), this::handleMenuClick);
            }
            for (UUID id : outsiders.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) HoloMenu.open(p, summaryMenu(winner, title), this::handleMenuClick);
            }
        }, 50L);
        runPhase(Phase.END, Component.text("Fin de partie", NamedTextColor.GOLD), timer("summary"), BossBar.Color.WHITE, this::reset);
    }

    private boolean isWinner(GamePlayer gp, Winner winner) {
        return switch (winner) {
            case VILLAGE -> gp.role.team() == Role.Team.VILLAGE;
            case WOLVES -> gp.isWolf();
            case LOVERS -> gp.lover != null;
            case NOBODY -> false;
        };
    }

    /** Résumé 3D : les cartes de tout le monde à gauche, le déroulé de la partie à droite. */
    private LgMenu summaryMenu(Winner winner, Component title) {
        return new LgMenu(MenuTheme.SUMMARY, Component.text("Résumé"), m -> {
            m.set(LgMenu.INFO_SLOT, Items.button(Material.PAPER, title.decorate(TextDecoration.BOLD),
                    List.of(Items.plain(players.size() + " joueurs · " + night + " nuit(s) · " + day + " jour(s)", NamedTextColor.GRAY)),
                    false), null);
            List<GamePlayer> list = new ArrayList<>(players.values());
            list.sort(Comparator.comparing((GamePlayer gp) -> !isWinner(gp, winner)).thenComparing(GamePlayer::name));
            for (int i = 0; i < list.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
                GamePlayer gp = list.get(i);
                String roleShort = gp.role == Role.VILLAGEOIS ? "Villageois" : gp.role.displayName();
                List<Component> lore = new ArrayList<>();
                lore.add(Items.plain(roleShort, gp.role.color()));
                lore.add(Items.plain(gp.alive ? "✔ Survivant" : "✘ Mort", gp.alive ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
                if (isWinner(gp, winner)) lore.add(Items.plain("★ Gagnant", NamedTextColor.GOLD));
                if (gp.uuid().equals(mayor)) lore.add(Items.plain("♛ Maire", NamedTextColor.GOLD));
                if (gp.lover != null) {
                    GamePlayer l = players.get(gp.lover);
                    if (l != null) lore.add(Items.plain("♥ Amoureux de " + l.name(), Role.CUPIDON.color()));
                }
                m.set(LgMenu.HEAD_SLOTS[i], Items.head(gp.uuid(), gp.name(), lore, 1, isWinner(gp, winner)), () -> { });
            }
            List<Component> lines = new ArrayList<>(history);
            int max = 18;
            if (lines.size() > max) {
                lines = new ArrayList<>(lines.subList(lines.size() - max + 1, lines.size()));
                lines.add(0, Component.text("…", NamedTextColor.DARK_GRAY));
            }
            m.set(LgMenu.HISTORY_SLOT, Items.button(Material.BOOK, Component.text("Déroulé"), lines, false), null);
        });
    }

    /** Retour à l'attente sans vider la file (quelqu'un est parti pendant le compte à rebours). */
    private void cancelCountdown() {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) hideBossBar(p);
        }
        if (ticker != null) ticker.cancel();
        ticker = null;
        bossBar = null;
        phaseEnd = null;
        phaseToken++;
        state = State.LOBBY;
        phase = Phase.NONE;
        forced = false;
    }

    /** Arrêt forcé (/lg stop ou arrêt du serveur). */
    public void forceStop() {
        if (state == State.PLAYING || state == State.ENDING) {
            broadcast(Component.text("La partie a été arrêtée.", NamedTextColor.RED));
        }
        reset();
    }

    private void reset() {
        closeMenus();
        if (fxTask != null) fxTask.cancel();
        fxTask = null;
        clearVoteTags();
        for (UUID id : new ArrayList<>(watching.keySet())) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) stopWatching(sp);
        }
        watching.clear();
        for (UUID id : new ArrayList<>(outsiders.keySet())) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) stopSpectate(sp);
        }
        outsiders.clear();
        specClick.clear();
        for (GamePlayer gp : players.values()) {
            if (gp.body != null) gp.body.remove();
            Player p = gp.player();
            if (p == null) continue;
            hideBossBar(p);
            if (gp.snapshot != null && !gp.left) gp.snapshot.restore(p);
        }
        players.clear();
        if (ticker != null) ticker.cancel();
        ticker = null;
        bossBar = null;
        phaseEnd = null;
        phaseToken++;
        state = State.LOBBY;
        phase = Phase.NONE;
        night = 0;
        day = 0;
        healUsed = false;
        poisonUsed = false;
        lastGuardTarget = null;
        nightSteps.clear();
        wolfVotes.clear();
        dayVotes.clear();
        cupidPicks.clear();
        forced = false;
        mayor = null;
        mayorElectionDone = false;
        mayorVotes.clear();
        successorPick = null;
        mayorExtends = 0;
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin, manager::refreshAllJoinItems, 2L);
    }

    // ------------------------------------------------------------------ événements

    public void handleQuit(Player p) {
        GamePlayer gp = get(p);
        if (gp == null) return;
        if (state == State.LOBBY || state == State.STARTING) {
            players.remove(p.getUniqueId());
            hideBossBar(p);
            if (state == State.STARTING && (players.isEmpty() || (!forced && players.size() < cfg().getInt("min-players", 5)))) {
                broadcast(Component.text("Pas assez de joueurs, lancement annulé. En attente...", NamedTextColor.RED));
                cancelCountdown();
            }
            return;
        }
        if (gp.left) return;
        gp.left = true;
        stopWatching(p);
        menus.remove(p.getUniqueId());
        HoloMenu.close(p);
        hideBossBar(p);
        if (gp.snapshot != null) gp.snapshot.restore(p);
        if (state == State.PLAYING && gp.alive) {
            gp.alive = false;
            dayVotes.remove(gp.uuid());
            wolfVotes.remove(gp.uuid());
            broadcast(Component.text(gp.name() + " a fui le village. C'était ", NamedTextColor.GRAY)
                    .append(Component.text(gp.role.displayName(), gp.role.color())));
            boolean wasMayor = gp.uuid().equals(mayor);
            later(() -> {
                if (state != State.PLAYING) return;
                Winner w = checkWinner();
                if (w != null) {
                    endGame(w);
                    return;
                }
                if (wasMayor && phase != Phase.SUCCESSION) {
                    List<GamePlayer> pool = alive();
                    if (!pool.isEmpty()) setMayor(pool.get(ThreadLocalRandom.current().nextInt(pool.size())),
                            "tiré(e) au sort comme nouveau Maire");
                }
                refreshMenus();
            });
        }
    }

    public void handleMove(PlayerMoveEvent e) {
        if (state != State.PLAYING || !cfg().getBoolean("freeze-players", true)) return;
        GamePlayer gp = get(e.getPlayer());
        if (gp == null || !gp.alive || gp.left || gp.seat == null) return;
        Location to = e.getTo();
        if (!Objects.equals(to.getWorld(), gp.seat.getWorld())) return;
        double dx = to.getX() - gp.seat.getX(), dz = to.getZ() - gp.seat.getZ(), dy = to.getY() - gp.seat.getY();
        if (dx * dx + dz * dz > 0.01 || Math.abs(dy) > 0.6) {
            Location fixed = gp.seat.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    public void handleChat(AsyncChatEvent e) {
        if (state != State.PLAYING) return;
        GamePlayer gp = players.get(e.getPlayer().getUniqueId());
        if (gp == null || gp.left) return;
        if (!gp.alive) {
            e.viewers().removeIf(a -> a instanceof Player v && !isDeadSpectator(v));
            e.renderer((source, name, message, viewer) -> Component.text("[Mort] ", NamedTextColor.DARK_GRAY)
                    .append(name.color(NamedTextColor.GRAY)).append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(message.color(NamedTextColor.GRAY)));
            return;
        }
        Phase current = phase;
        if (current.isNight()) {
            if (current == Phase.WOLVES && gp.isWolf()) {
                e.viewers().removeIf(a -> a instanceof Player v && !isAliveWolf(v));
                e.renderer((source, name, message, viewer) -> Component.text("[Meute] ", NamedTextColor.DARK_RED)
                        .append(name.color(NamedTextColor.RED)).append(Component.text(": ", NamedTextColor.DARK_GRAY))
                        .append(message.color(NamedTextColor.WHITE)));
                return;
            }
            e.setCancelled(true);
            e.getPlayer().sendMessage(PREFIX.append(Component.text("Chut... le village dort.", NamedTextColor.GRAY)));
        }
    }

    private boolean isDeadSpectator(Player p) {
        GamePlayer gp = players.get(p.getUniqueId());
        return gp != null && !gp.alive && !gp.left;
    }

    private boolean isAliveWolf(Player p) {
        GamePlayer gp = players.get(p.getUniqueId());
        return gp != null && gp.alive && gp.isWolf() && !gp.left;
    }

    public void useItem(Player p, String tag) {
        GamePlayer gp = get(p);
        if (gp == null || gp.role == null) return;
        if (Items.TAG_CARD.equals(tag)) {
            openRoleSheet(p, gp);
        } else if (Items.TAG_MENU.equals(tag)) {
            reopenMenu(p);
        } else if (Items.TAG_MAYOR.equals(tag)) {
            openMayorPanel(p);
        }
    }

    /** Fiche du rôle en 3D : grande carte à gauche, explications à droite. Viser la carte et cliquer la retourne. */
    private void openRoleSheet(Player p, GamePlayer gp) {
        if (p.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            // la nuit, l'aveuglement masque les menus 3D : on montre juste la carte à l'écran
            CardAnimator.reveal(plugin, p, gp.role, Component.text(gp.role.displayName(), gp.role.color()));
            return;
        }
        Role role = gp.role;
        LgMenu sheet = new LgMenu(MenuTheme.SHEET, Component.text("Ta carte"), m -> {
            m.set(LgMenu.SHEET_CARD, Items.cardFront(role, List.of(Items.plain("Clique pour la retourner", NamedTextColor.GRAY))),
                    () -> later(() -> {
                        HoloMenu.close(p);
                        CardAnimator.reveal(plugin, p, role, Component.text(role.displayName(), role.color()));
                    }));
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(role.team() == Role.Team.WOLVES ? "CAMP DES LOUPS" : "CAMP DU VILLAGE",
                    role.team() == Role.Team.WOLVES ? NamedTextColor.RED : NamedTextColor.GOLD));
            lore.add(Component.empty());
            lore.add(Items.plain("POUVOIR", NamedTextColor.GOLD));
            lore.add(Items.plain(role.description(), NamedTextColor.WHITE));
            lore.add(Component.empty());
            lore.add(Items.plain("OBJECTIF", NamedTextColor.GOLD));
            lore.add(Items.plain(role.goal(), NamedTextColor.WHITE));
            if (gp.lover != null) {
                GamePlayer lover = players.get(gp.lover);
                if (lover != null) {
                    lore.add(Component.empty());
                    lore.add(Items.plain("♥ Amoureux de " + lover.name(), Role.CUPIDON.color()));
                }
            }
            lore.add(Component.empty());
            lore.add(Items.plain("Ne montre ta carte à personne !", NamedTextColor.GRAY));
            m.set(LgMenu.INFO_SLOT, Items.button(Material.PAPER, Component.text(role.displayName().toUpperCase(), role.color()),
                    lore, false), null);
            m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER, Items.plain("Fermer", NamedTextColor.RED), null, false),
                    () -> later(() -> HoloMenu.close(p)));
        });
        sound(p, "item.book.page_turn", 1f, 1f);
        HoloMenu.open(p, sheet, this::handleMenuClick);
    }

    public void reopenMenu(Player p) {
        LgMenu menu = menus.get(p.getUniqueId());
        if (menu == null) {
            msg(p, Component.text("Tu n'as aucune action à faire pour le moment.", NamedTextColor.GRAY));
            return;
        }
        menu.render();
        HoloMenu.open(p, menu, this::handleMenuClick);
    }

    public void showOwnCard(Player p) {
        GamePlayer gp = get(p);
        if (gp == null || gp.role == null || state != State.PLAYING) {
            msg(p, Component.text("Tu n'as pas de carte pour le moment.", NamedTextColor.GRAY));
            return;
        }
        useItem(p, Items.TAG_CARD);
    }

    public void handleMenuClick(LgMenu menu, int slot, boolean rightClick) {
        if (state != State.PLAYING && menu.theme() != MenuTheme.ADMIN) return;
        menu.click(slot, rightClick);
    }

    // ------------------------------------------------------------------ menu admin

    private static final int[] ADMIN_ROLE_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    /** Menu admin : composition des rôles (clic gauche +1, clic droit -1), mode auto/manuel, lancement. */
    public void openAdmin(Player admin) {
        LgMenu menu = new LgMenu(MenuTheme.ADMIN, m -> renderAdmin(m, admin));
        sound(admin, "block.chest.open", 0.6f, 1.2f);
        admin.openInventory(menu.getInventory());
    }

    private void renderAdmin(LgMenu m, Player admin) {
        int n = players.size();
        int previewCount = Math.max(n, Math.min(arena.maxPlayers(), cfg().getInt("min-players", 5)));
        final int preview = previewCount == 0 ? cfg().getInt("min-players", 5) : previewCount;
        boolean custom = customMode();
        List<Role> roles = buildRoles(preview);
        String error = compositionError(preview);

        List<Component> info = new ArrayList<>();
        info.add(Items.plain("Mode : " + (custom ? "manuel" : "automatique"), NamedTextColor.YELLOW));
        info.add(Items.plain("Arène : " + arena.displayName() + " (" + arena.maxPlayers() + " places)", NamedTextColor.GOLD));
        info.add(Items.plain("Joueurs inscrits : " + n, NamedTextColor.GRAY));
        if (custom) {
            int cards = 0;
            for (Role r : Role.values()) cards += customCount(r);
            info.add(Items.plain("Cartes choisies : " + cards, NamedTextColor.GRAY));
        }
        info.add(Items.plain("Pour " + preview + " joueurs :", NamedTextColor.GRAY));
        for (Role r : Role.values()) {
            long c = roles.stream().filter(x -> x == r).count();
            if (c > 0) info.add(Items.plain(" • " + c + "× " + r.displayName(), NamedTextColor.WHITE));
        }
        info.add(error == null ? Items.plain("✔ Composition jouable", NamedTextColor.GREEN)
                : Items.plain("✘ " + error, NamedTextColor.RED));
        m.set(LgMenu.INFO_SLOT, Items.button(Material.WRITABLE_BOOK, Items.plain("Composition", NamedTextColor.GOLD), info, error == null), null);

        Role[] all = Role.values();
        for (int i = 0; i < all.length; i++) {
            Role role = all[i];
            int count = custom ? customCount(role) : (int) roles.stream().filter(x -> x == role).count();
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(count + " carte" + (count > 1 ? "s" : ""), count > 0 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
            if (role == Role.VILLAGEOIS) lore.add(Items.plain("+ complété automatiquement", NamedTextColor.GRAY));
            lore.add(Items.plain("Clic gauche : +1", NamedTextColor.GREEN));
            lore.add(Items.plain("Clic droit : -1", NamedTextColor.RED));
            if (!custom) lore.add(Items.plain("(passe en mode manuel)", NamedTextColor.DARK_GRAY));
            m.set(ADMIN_ROLE_SLOTS[i], Items.roleCount(role, count, lore), () -> {
                if (!customMode()) copyAutoToCustom(preview);
                setCustomCount(role, customCount(role) + (m.lastClickRight() ? -1 : 1));
                arena.customRoles(true);
                manager.save();
                sound(admin, "ui.button.click", 0.6f, m.lastClickRight() ? 0.8f : 1.3f);
                m.render();
            });
        }

        m.set(39, Items.button(custom ? Material.LEVER : Material.REDSTONE_TORCH,
                Items.plain(custom ? "Mode manuel" : "Mode automatique", NamedTextColor.GOLD),
                Items.lore(custom ? "Tu choisis le nombre de chaque carte." : "Composition calculée selon le nombre de joueurs.",
                        "Clique pour basculer."), false), () -> {
            if (!customMode()) copyAutoToCustom(preview);
            arena.customRoles(!customMode());
            manager.save();
            sound(admin, "block.lever.click", 0.8f, 1f);
            m.render();
        });
        m.set(41, Items.button(Material.WATER_BUCKET, Items.plain("Remettre à zéro", NamedTextColor.AQUA),
                Items.lore("Recopie la composition automatique", "pour " + preview + " joueurs."), false), () -> {
            copyAutoToCustom(preview);
            manager.save();
            sound(admin, "item.bucket.empty", 0.8f, 1f);
            m.render();
        });

        boolean running = state != State.LOBBY;
        boolean needForce = !running && (n < cfg().getInt("min-players", 5) || compositionError(Math.max(1, n)) != null);
        List<Component> startLore = running ? Items.lore("Une partie est déjà en cours.")
                : n == 0 ? Items.lore("Personne n'est inscrit.", "Fais /lg join d'abord.")
                : needForce ? Items.lore(n + " joueur(s) inscrit(s).", "Pas assez de joueurs ou composition", "invalide : lancement en mode test.")
                : Items.lore(n + " joueur(s) inscrit(s).");
        m.set(LgMenu.LEFT_BUTTON, Items.button(running || n == 0 ? Material.GRAY_DYE : needForce ? Material.ORANGE_DYE : Material.LIME_DYE,
                Items.plain(needForce && n > 0 ? "Forcer le lancement (test)" : "Lancer la partie",
                        running || n == 0 ? NamedTextColor.DARK_GRAY : needForce ? NamedTextColor.GOLD : NamedTextColor.GREEN),
                startLore, false), () -> {
            if (state != State.LOBBY) return;
            later(() -> {
                admin.closeInventory();
                startCountdown(admin, needForce);
            });
        });
        m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER, Items.plain("Fermer", NamedTextColor.RED), null, false),
                () -> later(admin::closeInventory));
        m.set(LgMenu.RIGHT_BUTTON, Items.button(running ? Material.RED_DYE : Material.GRAY_DYE,
                Items.plain("Arrêter la partie", running ? NamedTextColor.RED : NamedTextColor.DARK_GRAY),
                Items.lore(running ? "Rend leurs affaires à tous les joueurs." : "Aucune partie en cours."), false), () -> {
            if (state == State.LOBBY) return;
            later(() -> {
                admin.closeInventory();
                forceStop();
            });
        });
    }

    private void copyAutoToCustom(int n) {
        boolean mode = arena.customRoles();
        arena.customRoles(false);
        List<Role> auto = buildRoles(n);
        arena.customRoles(mode);
        for (Role r : Role.values()) {
            // les villageois restants sont ajoutés automatiquement
            setCustomCount(r, r == Role.VILLAGEOIS ? 0 : (int) auto.stream().filter(x -> x == r).count());
        }
    }

    // ------------------------------------------------------------------ menus

    private void open(Player p, LgMenu menu) {
        menus.put(p.getUniqueId(), menu);
        withTimer(menu);
        HoloMenu.open(p, menu, this::handleMenuClick);
    }

    /** Sablier des menus 3D : temps restant de la phase en cours. */
    private LgMenu withTimer(LgMenu menu) {
        menu.timer(() -> phaseEnd == null ? -1 : timeLeft, () -> phaseTotal);
        menu.render();
        return menu;
    }

    /** Votants d'une cible (uuid, pseudo) pour les têtes affichées sur sa bannière. */
    private List<java.util.Map.Entry<UUID, String>> voterList(Map<UUID, UUID> votes, UUID target) {
        List<java.util.Map.Entry<UUID, String>> out = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
            if (!e.getValue().equals(target)) continue;
            GamePlayer v = players.get(e.getKey());
            out.add(java.util.Map.entry(e.getKey(), v == null ? "?" : v.name()));
        }
        return out;
    }

    /** S'accroupir ferme le menu 3D (l'étoile ou /lg menu le rouvre). */
    public void handleSneak(Player p) {
        if (!HoloMenu.isOpen(p)) return;
        HoloMenu.close(p);
        sound(p, "block.amethyst_block.step", 0.6f, 0.8f);
        if (menus.containsKey(p.getUniqueId())) {
            p.sendActionBar(Component.text("Menu fermé — clic droit sur l'étoile pour le rouvrir", NamedTextColor.GRAY));
        }
    }

    private void giveGameItems(Player p, GamePlayer gp) {
        p.getInventory().clear();
        p.getInventory().setItem(4, Items.menuItem());
        p.getInventory().setItem(8, Items.cardItem(gp.role));
    }

    private void refreshMenus() {
        for (LgMenu menu : menus.values()) menu.render();
    }

    private void closeMenus() {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) HoloMenu.close(p);
        }
        for (UUID id : outsiders.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) HoloMenu.close(p);
        }
        menus.clear();
    }

    /** Fermer depuis un clic doit se faire au tick suivant. */
    private void closeMenusLater() {
        later(() -> {
            for (UUID id : menus.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) HoloMenu.close(p);
            }
        });
    }

    private void fillHeads(LgMenu m, List<GamePlayer> targets,
                           java.util.function.Function<GamePlayer, org.bukkit.inventory.ItemStack> item,
                           java.util.function.Consumer<GamePlayer> onClick) {
        fillHeads(m, targets, item, null, onClick);
    }

    private void fillHeads(LgMenu m, List<GamePlayer> targets,
                           java.util.function.Function<GamePlayer, org.bukkit.inventory.ItemStack> item,
                           java.util.function.Function<GamePlayer, LgMenu.Entry> entry,
                           java.util.function.Consumer<GamePlayer> onClick) {
        for (int i = 0; i < targets.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
            GamePlayer gp = targets.get(i);
            int slot = LgMenu.HEAD_SLOTS[i];
            m.set(slot, item.apply(gp), () -> {
                if (!gp.alive) return;
                onClick.accept(gp);
            });
            if (entry != null) m.entry(slot, entry.apply(gp));
        }
    }

    // ------------------------------------------------------------------ minuterie

    private void startTicker() {
        if (ticker != null) ticker.cancel();
        bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_10);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void runPhase(Phase p, Component name, int seconds, BossBar.Color color, Runnable onEnd) {
        phaseToken++;
        phase = p;
        phaseName = name;
        timeLeft = seconds;
        phaseTotal = Math.max(1, seconds);
        phaseEnd = onEnd;
        if (bossBar != null) bossBar.color(color);
        updateBossBar();
        scheduleBots(p);
    }

    private void tick() {
        if (phaseEnd == null) return;
        timeLeft--;
        if (timeLeft <= 3 && timeLeft > 0 && (phase == Phase.COUNTDOWN || phase == Phase.VOTE)) {
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null && !gp.left) sound(p, "block.note_block.hat", 1f, 1f);
            }
        }
        if (timeLeft <= 0) {
            endPhase();
        } else {
            updateBossBar();
        }
    }

    private void endPhase() {
        Runnable r = phaseEnd;
        phaseEnd = null;
        if (r == null) return;
        try {
            r.run();
        } catch (RuntimeException ex) {
            // un bug ne doit jamais bloquer la partie : on l'écrit dans la console et on passe à la suite
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Erreur pendant la phase " + phase + " — la partie continue", ex);
            if (state != State.PLAYING || phaseEnd != null) return;
            try {
                if (phase.isNight() || phase == Phase.NIGHTFALL) nextNightStep();
                else startNight();
            } catch (RuntimeException again) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Impossible de reprendre la partie, arrêt", again);
                broadcast(Component.text("Erreur interne : la partie est arrêtée (voir la console).", NamedTextColor.RED));
                forceStop();
            }
        }
    }

    private void endPhaseLater(long ticks) {
        int token = phaseToken;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (token == phaseToken) endPhase();
        }, ticks);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        bossBar.name(phaseName.append(Component.text("  " + String.format("%d:%02d", Math.max(0, timeLeft) / 60, Math.max(0, timeLeft) % 60),
                NamedTextColor.WHITE)));
        bossBar.progress(Math.max(0f, Math.min(1f, timeLeft / (float) phaseTotal)));
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null && !gp.left) p.showBossBar(bossBar);
        }
        for (UUID id : outsiders.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showBossBar(bossBar);
        }
    }

    private void hideBossBar(Player p) {
        if (bossBar != null) p.hideBossBar(bossBar);
    }

    // ------------------------------------------------------------------ mise en scène & votes 3D

    private void log(String text, net.kyori.adventure.text.format.TextColor color) {
        String when = phase.isNight() || phase == Phase.NIGHTFALL ? "Nuit " + Math.max(1, night) : "Jour " + Math.max(1, day);
        history.add(Component.text(when + " · ", NamedTextColor.DARK_GRAY).append(Component.text(text, color)));
    }

    private Location computeCenter() {
        double x = 0, y = 0, z = 0;
        int n = 0;
        org.bukkit.World w = null;
        for (GamePlayer gp : players.values()) {
            if (gp.seat == null) continue;
            x += gp.seat.getX();
            y += gp.seat.getY();
            z += gp.seat.getZ();
            w = gp.seat.getWorld();
            n++;
        }
        return n == 0 ? null : new Location(w, x / n, y / n, z / n);
    }

    /** Fait défiler l'heure (vue par les joueurs de la partie) de « from » à « to ». */
    private void animateSky(long from, long to, int ticks, Runnable after) {
        int steps = Math.max(1, ticks / 2);
        int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            i[0]++;
            long t = from + (to - from) * i[0] / steps;
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null && !gp.left) p.setPlayerTime(t, false);
            }
            for (UUID id : outsiders.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.setPlayerTime(t, false);
            }
            if (i[0] >= steps) {
                task.cancel();
                if (after != null) after.run();
            }
        }, 0L, 2L);
    }

    /** Toutes les 4 ticks : rayons et compteurs de votes, ambiance de nuit, caméras des spectateurs. */
    private void fxTick() {
        if (state != State.PLAYING) return;
        fxTicks++;
        switch (phase) {
            case VOTE -> drawVotes(dayVotes, viewersAll(), Color.fromRGB(0xFFC83C), true);
            case MAYOR_ELECTION -> drawVotes(mayorVotes, viewersAll(), Color.fromRGB(0xFFE680), false);
            case WOLVES -> drawVotes(wolfVotes, viewersWolves(), Color.fromRGB(0xE03030), false);
            default -> clearVoteTags();
        }
        if (phase.isNight() || phase == Phase.NIGHTFALL) nightAmbience();
        if (fxTicks % 5 == 0) maintainSpectators();
    }

    private List<Player> viewersAll() {
        List<Player> out = new ArrayList<>();
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null && !gp.left) out.add(p);
        }
        for (UUID id : outsiders.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Les votes des loups ne sont visibles que des loups (et des morts / spectateurs, qui savent déjà tout). */
    private List<Player> viewersWolves() {
        List<Player> out = new ArrayList<>();
        for (Player p : viewersAll()) {
            GamePlayer gp = get(p);
            if (gp == null || !gp.alive || gp.isWolf()) out.add(p);
        }
        return out;
    }

    private void drawVotes(Map<UUID, UUID> votes, List<Player> viewers, Color color, boolean weighted) {
        Map<UUID, Integer> counts = new HashMap<>();
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.9f);
        for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
            GamePlayer voter = players.get(e.getKey());
            GamePlayer target = players.get(e.getValue());
            if (voter == null || target == null || !voter.alive || !target.alive) continue;
            Location vl = locOf(voter), tl = locOf(target);
            if (vl == null || tl == null || !vl.getWorld().equals(tl.getWorld())) continue;
            counts.merge(target.uuid(), weighted ? voteWeight(voter.uuid()) : 1, Integer::sum);
            // rayon du votant vers sa cible
            Location from = vl.clone().add(0, 1.35, 0);
            Location to = tl.clone().add(0, 1.15, 0);
            org.bukkit.util.Vector step = to.toVector().subtract(from.toVector());
            double len = step.length();
            if (len < 0.1) continue;
            int points = (int) Math.min(60, len / 0.3);
            step.multiply(1.0 / points);
            int offset = (int) (fxTicks % 3);
            for (int i = offset; i < points; i += 2) {
                Location at = from.clone().add(step.clone().multiply(i));
                for (Player v : viewers) v.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
            }
        }
        // compteurs au-dessus des têtes
        for (Map.Entry<UUID, org.bukkit.entity.TextDisplay> e : new ArrayList<>(voteTags.entrySet())) {
            if (!counts.containsKey(e.getKey())) {
                e.getValue().remove();
                voteTags.remove(e.getKey());
            }
        }
        for (Map.Entry<UUID, Integer> e : counts.entrySet()) {
            GamePlayer target = players.get(e.getKey());
            Location tl = target == null ? null : locOf(target);
            if (tl == null) continue;
            Location at = tl.clone().add(0, 2.35, 0);
            org.bukkit.entity.TextDisplay tag = voteTags.get(e.getKey());
            Component text = Component.text("▼ " + e.getValue() + " voix", net.kyori.adventure.text.format.TextColor.color(color.asRGB()))
                    .decorate(TextDecoration.BOLD);
            if (tag == null || !tag.isValid()) {
                tag = at.getWorld().spawn(at, org.bukkit.entity.TextDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    d.setShadowed(true);
                    d.setDefaultBackground(false);
                    d.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                });
                voteTags.put(e.getKey(), tag);
            }
            tag.text(text);
            if (fxTicks % 5 == 0 || tag.getTicksLived() < 6) {
                for (Player v : viewers) v.showEntity(plugin, tag);
            }
        }
    }

    private void clearVoteTags() {
        for (org.bukkit.entity.TextDisplay t : voteTags.values()) t.remove();
        voteTags.clear();
    }

    private void nightAmbience() {
        if (center == null || center.getWorld() == null) return;
        org.bukkit.World w = center.getWorld();
        // braises et fumée au centre du village, cendres qui tombent
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center.clone().add(0, 0.3, 0), 1, 0.15, 0.1, 0.15, 0.01);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(0, 0.4, 0), 2, 0.3, 0.2, 0.3, 0.005);
        w.spawnParticle(Particle.WHITE_ASH, center.clone().add(0, 4, 0), 12, 6, 2, 6, 0);
        // sons d'ambiance de temps en temps
        if (fxTicks % 30 == 0) {
            String[] sounds = {"entity.wolf.howl", "ambient.cave", "block.campfire.crackle", "entity.fox.screech", "entity.phantom.ambient"};
            String key = sounds[ThreadLocalRandom.current().nextInt(sounds.length)];
            float pitch = 0.6f + ThreadLocalRandom.current().nextFloat() * 0.4f;
            for (Player p : viewersAll()) sound(p, key, 0.35f, pitch);
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private List<GamePlayer> alive() {
        List<GamePlayer> out = new ArrayList<>();
        for (GamePlayer gp : players.values()) if (gp.alive && !gp.left && gp.role != null) out.add(gp);
        return out;
    }

    private List<GamePlayer> aliveWith(Role role) {
        return alive().stream().filter(gp -> gp.role == role).toList();
    }

    private boolean dealt(Role role) {
        return players.values().stream().anyMatch(gp -> gp.role == role);
    }

    private List<String> votersFor(Map<UUID, UUID> votes, UUID target) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
            if (!e.getValue().equals(target)) continue;
            GamePlayer gp = players.get(e.getKey());
            if (gp != null) out.add(gp.name());
        }
        return out;
    }

    /** Vote du village : voix du Maire doublée ; en cas d'égalité, le choix du Maire l'emporte, sinon personne. */
    private UUID tallyDay() {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Map.Entry<UUID, UUID> e : dayVotes.entrySet()) {
            GamePlayer voter = players.get(e.getKey());
            GamePlayer target = players.get(e.getValue());
            if (voter == null || !voter.alive || target == null || !target.alive) continue;
            counts.merge(e.getValue(), voteWeight(e.getKey()), Integer::sum);
        }
        if (counts.isEmpty()) return null;
        int max = Collections.max(counts.values());
        List<UUID> top = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        if (top.size() == 1) return top.get(0);
        UUID mayorChoice = mayorPlayer() == null ? null : dayVotes.get(mayor);
        if (mayorChoice != null && top.contains(mayorChoice)) {
            GamePlayer t = players.get(mayorChoice);
            broadcast(Component.text("Égalité ! Le Maire départage : " + (t == null ? "?" : t.name()) + ".", NamedTextColor.GOLD));
            return mayorChoice;
        }
        return null;
    }

    /** Joueur le plus voté. Égalité : tirage au sort si randomTie, sinon personne. */
    private UUID tally(Map<UUID, UUID> votes, boolean randomTie) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
            GamePlayer voter = players.get(e.getKey());
            GamePlayer target = players.get(e.getValue());
            if (voter == null || !voter.alive || target == null || !target.alive) continue;
            counts.merge(e.getValue(), 1, Integer::sum);
        }
        if (counts.isEmpty()) return null;
        int max = Collections.max(counts.values());
        List<UUID> top = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        if (top.size() == 1) return top.get(0);
        return randomTie ? top.get(ThreadLocalRandom.current().nextInt(top.size())) : null;
    }

    private void blind(Player p, boolean on) {
        if (on) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }

    private void msg(Player p, Component c) {
        p.sendMessage(PREFIX.append(c));
    }

    public void broadcast(Component c) {
        Component full = PREFIX.append(c);
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null && !gp.left) p.sendMessage(full);
        }
        Bukkit.getConsoleSender().sendMessage(full);
    }

    public int playerCount() {
        return players.size();
    }

    /** Joueurs inscrits ou en jeu (pour le menu des arènes). */
    public List<GamePlayer> roster() {
        List<GamePlayer> list = new ArrayList<>(players.values());
        list.removeIf(gp -> gp.left);
        list.sort(Comparator.comparing(GamePlayer::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public int aliveCount() {
        return alive().size();
    }

    public String rolePreview(int n) {
        return buildRoles(n).stream().map(Role::displayName).collect(Collectors.joining(", "));
    }
}
