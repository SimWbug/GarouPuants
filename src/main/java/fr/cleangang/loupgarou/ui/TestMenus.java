package fr.cleangang.loupgarou.ui;

import fr.cleangang.loupgarou.game.Role;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /lg test : ouvre chaque interface hors partie, avec de faux joueurs, pour vérifier l'affichage.
 * Les clics fonctionnent (sélection, votes) mais n'ont aucun effet sur une vraie partie.
 */
public final class TestMenus {
    public static final List<String> MENUS = List.of(
            "vote", "maire", "pouvoirs", "loups", "voyante", "sorciere", "cupidon", "chasseur", "salvateur", "fiche", "carte",
            "arenes", "admin", "fermer");
    private static final String[] FAKE = {"Lucas", "Emma", "Hugo", "Jade", "Nathan", "Lea", "Tom", "Ines", "Chloe", "Louis"};

    private final Plugin plugin;

    public TestMenus(Plugin plugin) {
        this.plugin = plugin;
    }

    private record Target(UUID id, String name) {}

    /** Joueurs connectés (pour voir de vraies têtes) complétés par de faux joueurs. */
    private List<Target> targets(Player viewer, int count) {
        List<Target> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(viewer) && out.size() < count) out.add(new Target(p.getUniqueId(), p.getName()));
        }
        for (int i = 0; out.size() < count && i < FAKE.length; i++) {
            out.add(new Target(UUID.nameUUIDFromBytes(("lgtest-" + FAKE[i]).getBytes()), FAKE[i]));
        }
        return out;
    }

    /** @return message d'erreur, ou null si c'est bon. */
    public String open(Player p, String menu, String roleArg, int count) {
        Role role = parseRole(roleArg);
        List<Target> targets = targets(p, Math.max(1, Math.min(21, count)));
        HoloMenu.ClickHandler direct = (m, slot, right) -> m.click(slot, right);
        viewerId = p.getUniqueId();
        switch (menu.toLowerCase(Locale.ROOT)) {
            case "vote" -> show(p, voteMenu(MenuTheme.VOTE, targets, "Qui est le loup ?", "Clique pour voter contre"), direct);
            case "maire" -> show(p, voteMenu(MenuTheme.MAYOR_VOTE, targets, "Qui sera le Maire ?", "Clique pour élire"), direct);
            case "pouvoirs" -> show(p, mayorPanel(p), direct);
            case "loups" -> show(p, voteMenu(MenuTheme.WOLVES, targets, "Choisissez une victime", "Clique pour dévorer"), direct);
            case "voyante" -> show(p, seer(p, targets), direct);
            case "sorciere" -> show(p, witch(targets), direct);
            case "cupidon" -> show(p, cupid(p, targets), direct);
            case "chasseur" -> show(p, single(p, MenuTheme.HUNTER, targets, Material.CROSSBOW, "Dernière balle",
                    "Clique sur le joueur à abattre.", "Clique pour tirer", "abat"), direct);
            case "salvateur" -> show(p, single(p, MenuTheme.GUARD, targets, Material.SHIELD, "Protection",
                    "Clique sur le joueur à protéger.", "Clique pour le protéger", "protège"), direct);
            case "fiche" -> HoloMenu.open(p, sheet(p, role), direct);
            case "carte" -> CardAnimator.reveal(plugin, p, role, Component.text(role.displayName(), role.color()));
            case "fermer" -> HoloMenu.close(p);
            default -> {
                return "Menus : " + String.join(", ", MENUS);
            }
        }
        return null;
    }

    private UUID viewerId;

    /** Ouvre un menu de test avec un sablier factice de 45 s (en boucle). */
    private void show(Player p, LgMenu menu, HoloMenu.ClickHandler handler) {
        long start = System.currentTimeMillis();
        menu.timer(() -> 45 - (int) ((System.currentTimeMillis() - start) / 1000 % 45), () -> 45);
        menu.render();
        HoloMenu.open(p, menu, handler);
    }

    private UUID idOf(String name) {
        return "Toi".equals(name) ? viewerId : UUID.nameUUIDFromBytes(("lgtest-" + name).getBytes());
    }

    private Role parseRole(String arg) {
        if (arg != null) {
            for (Role r : Role.values()) {
                if (r.id().equalsIgnoreCase(arg) || r.name().equalsIgnoreCase(arg)) return r;
            }
        }
        return Role.values()[(int) (Math.random() * Role.values().length)];
    }

    // ------------------------------------------------------------------ menus de test

    private LgMenu voteMenu(MenuTheme theme, List<Target> targets, String question, String hint) {
        Map<UUID, Set<String>> votes = new HashMap<>();
        // quelques votes pour voir les compteurs
        if (targets.size() > 2) {
            votes.computeIfAbsent(targets.get(1).id(), k -> new HashSet<>()).addAll(List.of("Emma", "Tom"));
            votes.computeIfAbsent(targets.get(2).id(), k -> new HashSet<>()).add("Hugo");
        }
        UUID[] mine = new UUID[1];
        return new LgMenu(theme, Component.text(theme.title()), m -> {
            int voted = votes.values().stream().mapToInt(Set::size).sum();
            m.set(LgMenu.INFO_SLOT, Items.button(Material.BELL, Items.plain(question, NamedTextColor.GOLD),
                    List.of(Items.plain(voted + "/" + (targets.size() + 1) + " ont voté", NamedTextColor.YELLOW),
                            Items.plain("(menu de test : rien n'est envoyé à une partie)", NamedTextColor.GRAY)), false), null);
            for (int i = 0; i < targets.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
                Target t = targets.get(i);
                Set<String> voters = votes.getOrDefault(t.id(), Set.of());
                boolean me = t.id().equals(mine[0]);
                List<Component> lore = new ArrayList<>();
                lore.add(Items.plain(voters.size() + " voix", NamedTextColor.GOLD));
                for (String v : voters) lore.add(Items.plain(" • " + v, NamedTextColor.GRAY));
                lore.add(Items.plain(me ? "▶ Ton vote" : hint, NamedTextColor.DARK_GRAY));
                List<Map.Entry<UUID, String>> who = new ArrayList<>();
                for (String v : voters) who.add(Map.entry(idOf(v), v));
                m.entry(LgMenu.HEAD_SLOTS[i], new LgMenu.Entry(null, voters.size(), (targets.size() + 2) / 2, who, me,
                        hint + " " + t.name()));
                m.set(LgMenu.HEAD_SLOTS[i], Items.head(t.id(), t.name(), lore, 1, me), () -> {
                    if (mine[0] != null) votes.getOrDefault(mine[0], new HashSet<>()).remove("Toi");
                    mine[0] = t.id();
                    votes.computeIfAbsent(t.id(), k -> new HashSet<>()).add("Toi");
                    m.render();
                });
            }
            if (theme == MenuTheme.VOTE) {
                m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER,
                        Items.plain(mine[0] == null ? "Je ne vote pas" : "Retirer mon vote", NamedTextColor.RED),
                        Items.lore("Abstention."), false), () -> {
                    if (mine[0] != null) votes.getOrDefault(mine[0], new HashSet<>()).remove("Toi");
                    mine[0] = null;
                    m.render();
                });
            }
        });
    }

    private LgMenu mayorPanel(Player p) {
        int[] extends_ = {0};
        return new LgMenu(MenuTheme.MAYOR, Component.text("Maire"), m -> {
            m.set(LgMenu.INFO_SLOT, Items.button(Material.GOLDEN_HELMET, Items.plain("Tu diriges le village", NamedTextColor.GOLD),
                    List.of(Items.plain("Phase : débat", NamedTextColor.YELLOW),
                            Items.plain("Ta voix compte double au vote.", NamedTextColor.GRAY)), false), null);
            m.set(LgMenu.LEFT_BUTTON, Items.button(Material.BELL, Items.plain("Lancer le vote maintenant", NamedTextColor.GOLD),
                    Items.lore("Coupe le débat et ouvre le vote."), false),
                    () -> p.sendActionBar(Component.text("(test) Le Maire lance le vote !", NamedTextColor.GOLD)));
            boolean can = extends_[0] < 2;
            m.set(LgMenu.RIGHT_BUTTON, Items.button(can ? Material.CLOCK : Material.GRAY_DYE,
                    Items.plain("+30 s de débat", can ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY),
                    Items.lore(can ? "Encore " + (2 - extends_[0]) + " fois possible." : "Plus de prolongation possible."), false), () -> {
                if (extends_[0] < 2) extends_[0]++;
                m.render();
            });
            m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER, Items.plain("Fermer", NamedTextColor.RED), null, false),
                    () -> Bukkit.getScheduler().runTask(plugin, () -> HoloMenu.close(p)));
        });
    }

    private LgMenu seer(Player p, List<Target> targets) {
        return new LgMenu(MenuTheme.SEER, Component.text("Voyante"), m -> {
            m.set(LgMenu.INFO_SLOT, Items.button(Material.ENDER_EYE, Items.plain("Vision", NamedTextColor.LIGHT_PURPLE),
                    Items.lore("Clique sur un joueur pour", "découvrir sa vraie carte."), false), null);
            for (int i = 0; i < targets.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
                Target t = targets.get(i);
                m.set(LgMenu.HEAD_SLOTS[i], Items.head(t.id(), t.name(), Items.lore("Clique pour voir sa carte"), 1, false), () ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            HoloMenu.close(p);
                            Role r = parseRole(null);
                            CardAnimator.reveal(plugin, p, r, Component.text(t.name() + " est " + r.displayName(), r.color()));
                        }));
            }
        });
    }

    private LgMenu witch(List<Target> targets) {
        Target victim = targets.get(0);
        boolean[] heal = {false};
        UUID[] poison = new UUID[1];
        return new LgMenu(MenuTheme.WITCH, Component.text("Sorcière"), m -> {
            m.set(LgMenu.INFO_SLOT, Items.head(victim.id(), victim.name(),
                    List.of(Items.plain("Victime des loups cette nuit", NamedTextColor.RED)), 1, false), null);
            m.set(LgMenu.LEFT_BUTTON, Items.potion(Color.fromRGB(0x3DD17E),
                    Items.plain(heal[0] ? "Potion de vie : UTILISÉE" : "Potion de vie", NamedTextColor.GREEN),
                    Items.lore("Sauve " + victim.name() + ".", "Clique pour (dé)sélectionner."), heal[0]), () -> {
                heal[0] = !heal[0];
                m.render();
            });
            Target target = targets.stream().filter(t -> t.id().equals(poison[0])).findFirst().orElse(null);
            m.set(LgMenu.MIDDLE_BUTTON, Items.potion(Color.fromRGB(0xC0392B), Items.plain("Potion de mort", NamedTextColor.RED),
                    Items.lore(target == null ? "Clique sur une tête pour empoisonner." : "Cible : " + target.name(),
                            "Clique ici pour annuler."), target != null), () -> {
                poison[0] = null;
                m.render();
            });
            m.set(LgMenu.RIGHT_BUTTON, Items.button(Material.LIME_DYE, Items.plain("Terminer mon tour", NamedTextColor.GREEN),
                    Items.lore("Valide tes choix et te rendort."), false), null);
            for (int i = 1; i < targets.size() && i <= LgMenu.HEAD_SLOTS.length; i++) {
                Target t = targets.get(i);
                boolean chosen = t.id().equals(poison[0]);
                m.set(LgMenu.HEAD_SLOTS[i - 1], Items.head(t.id(), t.name(),
                        Items.lore(chosen ? "☠ Sera empoisonné" : "Clique pour empoisonner"), 1, chosen), () -> {
                    poison[0] = chosen ? null : t.id();
                    m.render();
                });
            }
        });
    }

    private LgMenu cupid(Player p, List<Target> targets) {
        List<UUID> picks = new ArrayList<>();
        List<Target> all = new ArrayList<>(targets);
        all.add(0, new Target(p.getUniqueId(), p.getName()));
        return new LgMenu(MenuTheme.CUPID, Component.text("Cupidon"), m -> {
            m.set(LgMenu.INFO_SLOT, Items.button(Material.BOW, Items.plain("Choisis deux amoureux", NamedTextColor.LIGHT_PURPLE),
                    Items.lore("Clique sur deux joueurs (toi compris).", "Ils vivront et mourront ensemble."), false), null);
            for (int i = 0; i < all.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
                Target t = all.get(i);
                boolean picked = picks.contains(t.id());
                m.set(LgMenu.HEAD_SLOTS[i], Items.head(t.id(), t.name(),
                        Items.lore(picked ? "♥ Amoureux choisi" : "Clique pour le choisir"), 1, picked), () -> {
                    if (!picks.remove(t.id()) && picks.size() < 2) picks.add(t.id());
                    m.render();
                });
            }
        });
    }

    private LgMenu single(Player p, MenuTheme theme, List<Target> targets, Material icon, String title, String info,
                          String hint, String verb) {
        UUID[] chosen = new UUID[1];
        return new LgMenu(theme, Component.text(theme.title()), m -> {
            m.set(LgMenu.INFO_SLOT, Items.button(icon, Items.plain(title, theme.color()), Items.lore(info), false), null);
            for (int i = 0; i < targets.size() && i < LgMenu.HEAD_SLOTS.length; i++) {
                Target t = targets.get(i);
                boolean sel = t.id().equals(chosen[0]);
                m.set(LgMenu.HEAD_SLOTS[i], Items.head(t.id(), t.name(), Items.lore(sel ? "◆ Choisi" : hint), 1, sel), () -> {
                    chosen[0] = t.id();
                    p.sendActionBar(Component.text("(test) Tu " + verb + " " + t.name(), theme.color()));
                    m.render();
                });
            }
        });
    }

    private LgMenu sheet(Player p, Role role) {
        return new LgMenu(MenuTheme.SHEET, Component.text("Ta carte"), m -> {
            m.set(LgMenu.SHEET_CARD, Items.cardFront(role, List.of(Items.plain("Clique pour la retourner", NamedTextColor.GRAY))),
                    () -> Bukkit.getScheduler().runTask(plugin, () -> {
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
            lore.add(Component.empty());
            lore.add(Items.plain("Ne montre ta carte à personne !", NamedTextColor.GRAY));
            m.set(LgMenu.INFO_SLOT, Items.button(Material.PAPER, Component.text(role.displayName().toUpperCase(), role.color()),
                    lore, false), null);
            m.set(LgMenu.MIDDLE_BUTTON, Items.button(Material.BARRIER, Items.plain("Fermer", NamedTextColor.RED), null, false),
                    () -> Bukkit.getScheduler().runTask(plugin, () -> HoloMenu.close(p)));
        });
    }
}
