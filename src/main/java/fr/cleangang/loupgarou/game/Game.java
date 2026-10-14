package fr.cleangang.loupgarou.game;

import fr.cleangang.loupgarou.LoupGarouPlugin;
import fr.cleangang.loupgarou.ui.CardAnimator;
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
        DAWN, REVEAL, DISCUSSION, VOTE, HUNTER, END;

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
    private final Map<UUID, GamePlayer> players = new ConcurrentHashMap<>();
    private final Map<UUID, LgMenu> menus = new HashMap<>();

    private volatile State state = State.LOBBY;
    private volatile Phase phase = Phase.NONE;

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

    public Game(LoupGarouPlugin plugin) {
        this.plugin = plugin;
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
        if (players.size() >= cfg().getInt("max-players", 21)) {
            msg(p, Component.text("La partie est complète.", NamedTextColor.RED));
            return;
        }
        players.put(p.getUniqueId(), new GamePlayer(p));
        plugin.sendResourcePack(p);
        Location lobby = cfg().getLocation("lobby");
        if (lobby != null) p.teleport(lobby);
        broadcast(Component.text(p.getName() + " rejoint le village ", NamedTextColor.GREEN)
                .append(Component.text("(" + players.size() + "/" + cfg().getInt("min-players", 5) + " min)", NamedTextColor.GRAY)));
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
        if (state == State.STARTING && players.size() < cfg().getInt("min-players", 5)) {
            broadcast(Component.text("Pas assez de joueurs, lancement annulé.", NamedTextColor.RED));
            hideBossBar(p);
            reset();
        }
    }

    public void startCountdown(Player requester) {
        int min = cfg().getInt("min-players", 5);
        if (state != State.LOBBY) {
            msg(requester, Component.text("Une partie est déjà lancée.", NamedTextColor.RED));
            return;
        }
        if (players.size() < min) {
            msg(requester, Component.text("Il faut au moins " + min + " joueurs (" + players.size() + " inscrits).", NamedTextColor.RED));
            return;
        }
        String error = compositionError(players.size());
        if (error != null) {
            msg(requester, Component.text(error + " Ajuste la composition avec /lg admin.", NamedTextColor.RED));
            return;
        }
        state = State.STARTING;
        startTicker();
        runPhase(Phase.COUNTDOWN, Component.text("La partie commence", NamedTextColor.GOLD),
                timer("countdown"), BossBar.Color.WHITE, this::begin);
    }

    // ------------------------------------------------------------------ démarrage

    private void begin() {
        players.values().removeIf(gp -> gp.player() == null);
        int n = players.size();
        if (n < cfg().getInt("min-players", 5)) {
            broadcast(Component.text("Pas assez de joueurs connectés, lancement annulé.", NamedTextColor.RED));
            reset();
            return;
        }

        List<GamePlayer> list = new ArrayList<>(players.values());
        Collections.shuffle(list);
        String error = compositionError(n);
        if (error != null) {
            broadcast(Component.text("Lancement annulé : " + error, NamedTextColor.RED));
            reset();
            return;
        }
        List<Role> roles = buildRoles(n);
        Collections.shuffle(roles);
        for (int i = 0; i < n; i++) list.get(i).role = roles.get(i);

        Location center = cfg().getLocation("table");
        if (center == null) center = list.get(0).player().getLocation();
        double radius = Math.max(cfg().getDouble("table-radius", 4.0), n * 0.85);

        for (int i = 0; i < n; i++) {
            GamePlayer gp = list.get(i);
            Player p = gp.player();
            gp.snapshot = new PlayerSnapshot(p);
            double angle = 2 * Math.PI * i / n;
            Location seat = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Vector look = center.toVector().subtract(seat.toVector());
            seat.setDirection(look);
            seat.setPitch(10f);
            gp.seat = seat;

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

        broadcast(Component.text("Les cartes sont distribuées... Regarde bien la tienne !", NamedTextColor.GOLD));
        broadcast(Component.text("Rôles en jeu : ", NamedTextColor.GRAY).append(roleSummary(roles)));
        for (GamePlayer gp : list) {
            Player p = gp.player();
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
        return "custom".equalsIgnoreCase(cfg().getString("roles.mode", "auto"));
    }

    private int customCount(Role role) {
        return Math.max(0, cfg().getInt("roles.custom." + role.id(), 0));
    }

    private void setCustomCount(Role role, int count) {
        cfg().set("roles.custom." + role.id(), Math.max(0, Math.min(20, count)));
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
            p.setPlayerTime(18000, false);
            if (gp.alive) blind(p, true);
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
        for (GamePlayer actor : actors) {
            Player pl = actor.player();
            if (pl == null) continue;
            blind(pl, false);
            pl.showTitle(Title.title(Component.text("Réveille-toi", role.color()),
                    Component.text(role.displayName() + ", à toi de jouer !", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(5), Ticks.duration(30), Ticks.duration(5))));
            sound(pl, "block.note_block.chime", 1f, 1.2f);
            opener.open(actor, pl);
        }
        runPhase(p, name, timer(timerKey), color, () -> {
            for (GamePlayer actor : actors) {
                Player pl = actor.player();
                if (pl != null && actor.alive) blind(pl, true);
            }
            closeMenus();
            onEnd.run();
            nextNightStep();
        });
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
            Player pl = seer.player();
            later(() -> {
                if (pl == null) return;
                pl.closeInventory();
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
        }, gp -> {
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
        if (witchHeal) healUsed = true;
        if (witchKill != null) {
            poisonUsed = true;
            if (!deaths.contains(witchKill)) deaths.add(witchKill);
        }
        lastGuardTarget = guardTarget;
        day++;

        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            p.setPlayerTime(1000, false);
            blind(p, false);
            p.showTitle(Title.title(Component.text("Jour " + day, NamedTextColor.GOLD),
                    Component.text("Le village se réveille...", NamedTextColor.YELLOW),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(40), Ticks.duration(10))));
            sound(p, "entity.chicken.ambient", 1f, 0.9f);
        }

        runPhase(Phase.DAWN, Component.text("Le village se réveille", NamedTextColor.GOLD), 4, BossBar.Color.YELLOW, () -> {
            if (deaths.isEmpty()) {
                broadcast(Component.text("Miracle ! Personne n'est mort cette nuit.", NamedTextColor.GREEN));
                startDiscussion();
            } else {
                resolveDeaths(deaths, "a été retrouvé(e) mort(e) ce matin", this::startDiscussion);
            }
        });
    }

    private void startDiscussion() {
        if (state != State.PLAYING) return;
        broadcast(Component.text("Débattez ! Qui sont les loups ? Le vote commencera dans "
                + timer("discussion") + " s.", NamedTextColor.YELLOW));
        runPhase(Phase.DISCUSSION, Component.text("Débat du village", NamedTextColor.YELLOW), timer("discussion"),
                BossBar.Color.GREEN, this::startVote);
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
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(voters.size() + " voix", NamedTextColor.GOLD));
            for (String v : voters) lore.add(Items.plain(" • " + v, NamedTextColor.GRAY));
            lore.add(Items.plain(gp.uuid().equals(myVote) ? "▶ Ton vote" : "Clique pour voter contre", NamedTextColor.DARK_GRAY));
            return Items.head(gp.uuid(), gp.name(), lore, voters.size(), gp.uuid().equals(myVote));
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
        UUID eliminated = tally(dayVotes, false);
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
        broadcast(Component.text("☠ " + gp.name() + " " + reason + ". C'était ", NamedTextColor.RED)
                .append(Component.text(gp.role.displayName(), gp.role.color()).decorate(TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.RED)));
        for (GamePlayer viewer : players.values()) {
            Player p = viewer.player();
            if (p == null || viewer.left) continue;
            sound(p, "entity.lightning_bolt.thunder", 0.6f, 1f);
            CardAnimator.reveal(plugin, p, gp.role, Component.text(gp.name() + " — " + gp.role.displayName(), gp.role.color()));
        }
        Player dead = gp.player();
        if (dead != null && gp.role != Role.CHASSEUR) makeSpectator(dead);
    }

    private void makeSpectator(Player p) {
        blind(p, false);
        p.closeInventory();
        p.setGameMode(GameMode.SPECTATOR);
        msg(p, Component.text("Tu es mort. Tu peux observer la partie ; ton chat n'est visible que des morts.", NamedTextColor.GRAY));
    }

    private void runHunters(Deque<GamePlayer> hunters, Runnable then) {
        if (state != State.PLAYING) return;
        GamePlayer hunter = hunters.poll();
        if (hunter == null) {
            then.run();
            return;
        }
        Player pl = hunter.player();
        if (pl == null || alive().isEmpty()) {
            if (pl != null) makeSpectator(pl);
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
            if (online != null) makeSpectator(online);
            if (hunterShot != null) {
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
        if (winner != null) endGame(winner);
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
            broadcast(Component.text((gp.alive ? " ✔ " : " ✘ ") + gp.name() + " — ", gp.alive ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                    .append(Component.text(gp.role.displayName() + (gp.lover != null ? " ♥" : ""), gp.role.color())));
        }
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p == null || gp.left) continue;
            blind(p, false);
            p.setPlayerTime(1000, false);
            p.showTitle(Title.title(title, Component.text("Merci d'avoir joué !", NamedTextColor.GRAY),
                    Title.Times.times(Ticks.duration(10), Ticks.duration(100), Ticks.duration(20))));
            sound(p, "ui.toast.challenge_complete", 1f, 1f);
        }
        runPhase(Phase.END, Component.text("Fin de partie", NamedTextColor.GOLD), 10, BossBar.Color.WHITE, this::reset);
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
        for (GamePlayer gp : players.values()) {
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
    }

    // ------------------------------------------------------------------ événements

    public void handleQuit(Player p) {
        GamePlayer gp = get(p);
        if (gp == null) return;
        if (state == State.LOBBY || state == State.STARTING) {
            players.remove(p.getUniqueId());
            hideBossBar(p);
            if (state == State.STARTING && players.size() < cfg().getInt("min-players", 5)) {
                broadcast(Component.text("Pas assez de joueurs, lancement annulé.", NamedTextColor.RED));
                reset();
            }
            return;
        }
        if (gp.left) return;
        gp.left = true;
        menus.remove(p.getUniqueId());
        hideBossBar(p);
        if (gp.snapshot != null) gp.snapshot.restore(p);
        if (state == State.PLAYING && gp.alive) {
            gp.alive = false;
            dayVotes.remove(gp.uuid());
            wolfVotes.remove(gp.uuid());
            broadcast(Component.text(gp.name() + " a fui le village. C'était ", NamedTextColor.GRAY)
                    .append(Component.text(gp.role.displayName(), gp.role.color())));
            later(() -> {
                if (state != State.PLAYING) return;
                Winner w = checkWinner();
                if (w != null) endGame(w);
                else refreshMenus();
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
        }
    }

    /** Fiche custom du rôle. Cliquer sur la carte (colonnes 0-3) la retourne en grand à l'écran. */
    private void openRoleSheet(Player p, GamePlayer gp) {
        LgMenu sheet = new LgMenu(MenuTheme.VOTE, fr.cleangang.loupgarou.ui.Glyphs.roleSheetTitle(gp.role), m -> {
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 4; col++) {
                    m.set(row * 9 + col, null, () -> later(() -> {
                        p.closeInventory();
                        CardAnimator.reveal(plugin, p, gp.role, Component.text(gp.role.displayName(), gp.role.color()));
                    }));
                }
            }
        });
        sound(p, "item.book.page_turn", 1f, 1f);
        p.getInventory().clear();
        p.openInventory(sheet.getInventory());
    }

    public void reopenMenu(Player p) {
        LgMenu menu = menus.get(p.getUniqueId());
        if (menu == null) {
            msg(p, Component.text("Tu n'as aucune action à faire pour le moment.", NamedTextColor.GRAY));
            return;
        }
        menu.render();
        p.getInventory().clear();
        p.openInventory(menu.getInventory());
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
        int preview = Math.max(n, cfg().getInt("min-players", 5));
        boolean custom = customMode();
        List<Role> roles = buildRoles(preview);
        String error = compositionError(preview);

        List<Component> info = new ArrayList<>();
        info.add(Items.plain("Mode : " + (custom ? "manuel" : "automatique"), NamedTextColor.YELLOW));
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
                cfg().set("roles.mode", "custom");
                plugin.saveConfig();
                sound(admin, "ui.button.click", 0.6f, m.lastClickRight() ? 0.8f : 1.3f);
                m.render();
            });
        }

        m.set(39, Items.button(custom ? Material.LEVER : Material.REDSTONE_TORCH,
                Items.plain(custom ? "Mode manuel" : "Mode automatique", NamedTextColor.GOLD),
                Items.lore(custom ? "Tu choisis le nombre de chaque carte." : "Composition calculée selon le nombre de joueurs.",
                        "Clique pour basculer."), false), () -> {
            if (!customMode()) copyAutoToCustom(preview);
            cfg().set("roles.mode", customMode() ? "auto" : "custom");
            plugin.saveConfig();
            sound(admin, "block.lever.click", 0.8f, 1f);
            m.render();
        });
        m.set(41, Items.button(Material.WATER_BUCKET, Items.plain("Remettre à zéro", NamedTextColor.AQUA),
                Items.lore("Recopie la composition automatique", "pour " + preview + " joueurs."), false), () -> {
            copyAutoToCustom(preview);
            plugin.saveConfig();
            sound(admin, "item.bucket.empty", 0.8f, 1f);
            m.render();
        });

        boolean running = state != State.LOBBY;
        m.set(LgMenu.LEFT_BUTTON, Items.button(running ? Material.GRAY_DYE : Material.LIME_DYE,
                Items.plain("Lancer la partie", running ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN),
                Items.lore(running ? "Une partie est déjà en cours." : n + " joueur(s) inscrit(s)."), false), () -> {
            if (state != State.LOBBY) return;
            later(() -> {
                admin.closeInventory();
                startCountdown(admin);
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
        String mode = cfg().getString("roles.mode", "auto");
        cfg().set("roles.mode", "auto");
        List<Role> auto = buildRoles(n);
        cfg().set("roles.mode", mode);
        for (Role r : Role.values()) {
            // les villageois restants sont ajoutés automatiquement
            setCustomCount(r, r == Role.VILLAGEOIS ? 0 : (int) auto.stream().filter(x -> x == r).count());
        }
    }

    // ------------------------------------------------------------------ menus

    private void open(Player p, LgMenu menu) {
        menus.put(p.getUniqueId(), menu);
        // Inventaire vidé pendant le menu : le fond custom recouvre cette zone
        p.getInventory().clear();
        p.openInventory(menu.getInventory());
    }

    /** Appelé à la fermeture d'un menu : rend l'étoile et la carte au joueur. */
    public void handleMenuClosed(Player p) {
        later(() -> {
            GamePlayer gp = get(p);
            if (gp == null || gp.left || gp.role == null || !p.isOnline()) return;
            if (state != State.PLAYING && state != State.ENDING) return;
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof LgMenu) return;
            giveGameItems(p, gp);
        });
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
        for (UUID id : new ArrayList<>(menus.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                InventoryHolder holder = p.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof LgMenu) p.closeInventory();
            }
        }
        menus.clear();
    }

    /** Fermer depuis un clic doit se faire au tick suivant. */
    private void closeMenusLater() {
        later(() -> {
            for (UUID id : menus.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof LgMenu) p.closeInventory();
            }
        });
    }

    private void fillHeads(LgMenu m, List<GamePlayer> targets,
                           java.util.function.Function<GamePlayer, org.bukkit.inventory.ItemStack> item,
                           java.util.function.Consumer<GamePlayer> onClick) {
        for (int i = 0; i < targets.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
            GamePlayer gp = targets.get(i);
            m.set(LgMenu.HEAD_SLOTS[i], item.apply(gp), () -> {
                if (!gp.alive) return;
                onClick.accept(gp);
            });
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
        if (r != null) r.run();
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
    }

    private void hideBossBar(Player p) {
        if (bossBar != null) p.hideBossBar(bossBar);
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

    public String rolePreview(int n) {
        return buildRoles(n).stream().map(Role::displayName).collect(Collectors.joining(", "));
    }
}
