package fr.cleangang.loupgarou;

import fr.cleangang.loupgarou.game.Arena;
import fr.cleangang.loupgarou.game.ArenaManager;
import fr.cleangang.loupgarou.game.Game;
import fr.cleangang.loupgarou.game.Role;
import fr.cleangang.loupgarou.ui.TestMenus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LgCommand implements TabExecutor {
    private static final List<String> PLAYER_SUBS = List.of("join", "leave", "arenes", "spectate", "menu", "carte", "roles");
    private static final List<String> ADMIN_SUBS = List.of("arena", "admin", "test", "slot", "start", "forcestart", "stop",
            "skip", "setlobby", "reload", "bot");
    private static final List<String> ARENA_SUBS = List.of("create", "edit", "done", "delete", "rename", "list", "info");

    private final LoupGarouPlugin plugin;
    private final ArenaManager arenas;
    private final TestMenus tests;

    public LgCommand(LoupGarouPlugin plugin, ArenaManager arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.tests = new TestMenus(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        boolean admin = sender.hasPermission("loupgarou.admin");
        if (ADMIN_SUBS.contains(sub) && !admin) {
            sender.sendMessage(Component.text("Tu n'as pas la permission.", NamedTextColor.RED));
            return true;
        }
        Player player = sender instanceof Player p ? p : null;
        String arg1 = args.length > 1 ? args[1] : null;

        switch (sub) {
            case "join" -> {
                if (!requirePlayer(sender)) return true;
                if (arg1 == null) {
                    arenas.openSelector(player);
                } else {
                    Arena a = arenas.arena(arg1);
                    if (a == null) err(sender, "Arène inconnue : " + arg1);
                    else arenas.join(player, a);
                }
            }
            case "arenes", "arenas" -> { if (requirePlayer(sender)) arenas.openSelector(player); }
            case "spectate", "spec", "regarder" -> {
                if (!requirePlayer(sender)) return true;
                Arena a = arenas.contextArena(null, arg1);
                if (a == null) {
                    err(sender, "/lg spectate <arène> (" + String.join(", ", arenas.arenas().stream().map(Arena::id).toList()) + ")");
                    return true;
                }
                arenas.spectate(player, a);
            }
            case "leave" -> { if (requirePlayer(sender)) arenas.leave(player); }
            case "menu" -> {
                if (!requirePlayer(sender)) return true;
                Game g = arenas.gameOf(player);
                if (g == null) err(sender, "Tu n'es dans aucune partie.");
                else g.reopenMenu(player);
            }
            case "carte", "card" -> {
                if (!requirePlayer(sender)) return true;
                Game g = arenas.gameOf(player);
                if (g == null) err(sender, "Tu n'es dans aucune partie.");
                else g.showOwnCard(player);
            }
            case "arena" -> arenaCommand(sender, player, Arrays.copyOfRange(args, 1, args.length));
            case "admin" -> {
                if (!requirePlayer(sender)) return true;
                Game g = gameFor(sender, player, arg1);
                if (g != null) g.openAdmin(player);
            }
            case "start", "forcestart" -> {
                if (!requirePlayer(sender)) return true;
                boolean force = sub.equals("forcestart") || Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("force"));
                String name = arg1 != null && !arg1.equalsIgnoreCase("force") ? arg1 : null;
                Game g = gameFor(sender, player, name);
                if (g != null) g.startCountdown(player, force);
            }
            case "stop" -> {
                Game g = gameFor(sender, player, arg1);
                if (g == null) return true;
                g.forceStop();
                sender.sendMessage(Component.text("Partie arrêtée dans « " + g.arena().displayName() + " ».", NamedTextColor.YELLOW));
            }
            case "skip" -> {
                Game g = gameFor(sender, player, arg1);
                if (g == null) return true;
                g.skipPhase();
                sender.sendMessage(Component.text("Phase suivante.", NamedTextColor.YELLOW));
            }
            case "bot", "bots" -> {
                // /lg bot <nombre> [arène] · /lg bot retirer [arène]
                boolean remove = arg1 != null && List.of("retirer", "remove", "clear", "0").contains(arg1.toLowerCase());
                int n = 1;
                String arenaArg = args.length > 2 ? args[2] : null;
                if (arg1 != null && !remove) {
                    try {
                        n = Math.max(1, Math.min(16, Integer.parseInt(arg1)));
                    } catch (NumberFormatException e) {
                        arenaArg = arg1; // /lg bot <arène>
                    }
                }
                Game g = gameFor(sender, player, arenaArg);
                if (g == null) return true;
                int done = remove ? g.removeBots() : g.addBots(n);
                if (done < 0) err(sender, "Les bots s'ajoutent ou se retirent avant le lancement de la partie.");
                else if (remove) sender.sendMessage(Component.text(done + " bot(s) retiré(s).", NamedTextColor.YELLOW));
                else if (done == 0) err(sender, "Arène pleine : ajoute des spawns ou retire des joueurs.");
                else sender.sendMessage(Component.text(done + " bot(s) ajouté(s) dans « " + g.arena().displayName()
                        + " » (" + g.botCount() + " au total). Ils votent et utilisent leurs pouvoirs au hasard. "
                        + "/lg forcestart pour lancer, /lg skip pour accélérer.", NamedTextColor.AQUA));
            }
            case "slot" -> slotCommand(sender, player, arg1, args.length > 2 ? args[2] : null);
            case "setlobby" -> {
                if (!requirePlayer(sender)) return true;
                plugin.getConfig().set("lobby", null);
                plugin.getConfig().set("lobby-world", player.getWorld().getName());
                plugin.getConfig().set("lobby-x", player.getLocation().getX());
                plugin.getConfig().set("lobby-y", player.getLocation().getY());
                plugin.getConfig().set("lobby-z", player.getLocation().getZ());
                plugin.saveConfig();
                arenas.refreshAllJoinItems();
                double r = plugin.getConfig().getDouble("join-item.radius", 0);
                sender.sendMessage(Component.text("Hub enregistré (monde " + player.getWorld().getName() + ") : la tête de loup y est donnée"
                        + (r > 0 ? " dans un rayon de " + (int) r + " blocs autour d'ici." : ". Pour la limiter autour d'ici : /lg slot zone <rayon>"),
                        NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                fr.cleangang.loupgarou.ui.Items.menuFont(plugin.getConfig().getString("menu-font", "lgt:archivo"));
                arenas.refreshAllJoinItems();
                sender.sendMessage(Component.text("config.yml rechargé (les arènes sont enregistrées automatiquement).",
                        NamedTextColor.GREEN));
            }
            case "test" -> testCommand(sender, player, args);
            case "roles" -> {
                Game g = player == null ? null : arenas.gameOf(player);
                if (g == null) {
                    Arena a = arenas.contextArena(player, null);
                    g = a == null ? null : arenas.game(a);
                }
                if (g == null) {
                    err(sender, "Rejoins une arène pour voir sa composition.");
                    return true;
                }
                int n = arg1 != null ? parse(arg1, g.playerCount()) : Math.max(g.playerCount(), plugin.getConfig().getInt("min-players", 5));
                sender.sendMessage(Component.text("« " + g.arena().displayName() + " » pour " + n + " joueurs : ", NamedTextColor.GOLD)
                        .append(Component.text(g.rolePreview(n), NamedTextColor.WHITE)));
            }
            default -> {
                sender.sendMessage(Component.text("— Loup-Garou —", NamedTextColor.RED));
                sender.sendMessage(Component.text("/lg arenes | join [arène] | spectate <arène> | leave | carte | menu | roles [n]", NamedTextColor.GRAY));
                if (admin) {
                    sender.sendMessage(Component.text("/lg arena create|edit|done|delete|rename|list|info", NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("/lg admin [arène] | start|forcestart|stop|skip [arène] | bot <n>|retirer [arène] | test <menu> | slot | setlobby | reload",
                            NamedTextColor.GRAY));
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ /lg arena

    private void arenaCommand(CommandSender sender, Player player, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        String id = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "create" -> {
                if (id == null) {
                    err(sender, "/lg arena create <id> [nom affiché]");
                    return;
                }
                String display = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : id;
                Arena arena = arenas.create(id, display);
                if (arena == null) {
                    err(sender, "Identifiant invalide ou déjà utilisé (lettres, chiffres, - et _).");
                    return;
                }
                sender.sendMessage(Component.text("Arène « " + arena.displayName() + " » créée.", NamedTextColor.GREEN));
                if (player != null) arenas.startEdit(player, arena);
            }
            case "edit" -> {
                if (!requirePlayer(sender)) return;
                Arena arena = arenas.contextArena(player, id);
                if (arena == null) {
                    err(sender, "/lg arena edit <id>");
                    return;
                }
                arenas.startEdit(player, arena);
            }
            case "done" -> {
                if (!requirePlayer(sender)) return;
                if (!arenas.isEditing(player)) err(sender, "Tu n'édites aucune arène.");
                else arenas.stopEdit(player);
            }
            case "delete" -> {
                Arena arena = arenas.arena(id);
                if (arena == null) {
                    err(sender, "/lg arena delete <id>");
                    return;
                }
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(Component.text("Supprimer « " + arena.displayName() + " » et tous ses spawns ? Tape /lg arena delete "
                            + arena.id() + " confirm", NamedTextColor.GOLD));
                    return;
                }
                arenas.delete(arena);
                sender.sendMessage(Component.text("Arène supprimée.", NamedTextColor.YELLOW));
            }
            case "rename" -> {
                Arena arena = arenas.arena(id);
                if (arena == null || a.length < 3) {
                    err(sender, "/lg arena rename <id> <nouveau nom affiché>");
                    return;
                }
                arena.displayName(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                arenas.save();
                sender.sendMessage(Component.text("Arène renommée en « " + arena.displayName() + " ».", NamedTextColor.GREEN));
            }
            case "info" -> {
                Arena arena = arenas.contextArena(player, id);
                if (arena == null) {
                    err(sender, "/lg arena info <id>");
                    return;
                }
                Game g = arenas.game(arena);
                sender.sendMessage(Component.text("— " + arena.displayName() + " (" + arena.id() + ") —", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Spawns : " + arena.spawns().size() + "/" + Arena.MAX_SPAWNS
                        + " · point d'attente : " + (arena.lobby() == null ? "non" : "oui")
                        + " · monde : " + arena.worldName(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Composition : " + (arena.customRoles() ? "manuelle" : "automatique")
                        + " · état : " + g.state() + " · joueurs : " + g.playerCount(), NamedTextColor.GRAY));
            }
            default -> {
                if (arenas.arenas().isEmpty()) {
                    sender.sendMessage(Component.text("Aucune arène. Crée-en une : /lg arena create <id>", NamedTextColor.GRAY));
                    return;
                }
                sender.sendMessage(Component.text("Arènes :", NamedTextColor.GOLD));
                for (Arena arena : arenas.arenas()) {
                    Game g = arenas.game(arena);
                    sender.sendMessage(Component.text(" • " + arena.id() + " — " + arena.displayName() + " (" + arena.spawns().size()
                            + " spawns, " + g.playerCount() + " joueur(s), " + g.state() + ")", NamedTextColor.GRAY));
                }
            }
        }
    }

    // ------------------------------------------------------------------ /lg slot

    private void slotCommand(CommandSender sender, Player player, String arg, String arg2) {
        if (arg == null) {
            sender.sendMessage(Component.text("Tête de loup : case " + (arenas.joinSlot() + 1)
                    + " de la barre. Change avec /lg slot <1-9>.", NamedTextColor.YELLOW));
            List<String> worlds = arenas.joinWorlds();
            double radius = plugin.getConfig().getDouble("join-item.radius", 0);
            sender.sendMessage(Component.text("Zone : " + (radius > 0 ? (int) radius + " blocs autour du point /lg setlobby" : "tout le monde")
                    + (radius > 0 && arenas.hubPoint() == null ? " (point non défini : fais /lg setlobby)" : ""), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Mondes : " + String.join(", ", worlds)
                    + (plugin.getConfig().getBoolean("join-item.enabled", true) ? "" : " (désactivée !)"), NamedTextColor.GRAY));
            if (player != null) {
                boolean here = arenas.inJoinWorld(player);
                sender.sendMessage(Component.text("Ton monde : " + player.getWorld().getName() + " → "
                        + (here ? "tête donnée ici" : "pas de tête ici"),
                        here ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            return;
        }
        if (arg.equalsIgnoreCase("zone")) {
            if (arg2 == null) {
                err(sender, "/lg slot zone <rayon en blocs> (0 = tout le monde du hub)");
                return;
            }
            int r;
            try {
                r = Math.max(0, Integer.parseInt(arg2));
            } catch (NumberFormatException e) {
                err(sender, "Le rayon doit être un nombre.");
                return;
            }
            plugin.getConfig().set("join-item.radius", r);
            plugin.saveConfig();
            arenas.refreshAllJoinItems();
            sender.sendMessage(Component.text(r > 0 ? "La tête n'est donnée qu'à moins de " + r + " blocs du point /lg setlobby."
                    : "La tête est donnée dans tout le monde du hub.", NamedTextColor.GREEN));
            if (r > 0 && arenas.hubPoint() == null) err(sender, "Fais /lg setlobby au centre du hub pour définir ce point.");
            return;
        }
        if (arg.equalsIgnoreCase("monde")) {
            if (!requirePlayer(sender)) return;
            List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("join-item.worlds"));
            String w = player.getWorld().getName();
            if (worlds.stream().noneMatch(x -> x.equalsIgnoreCase(w))) worlds.add(w);
            plugin.getConfig().set("join-item.worlds", worlds);
            plugin.getConfig().set("join-item.enabled", true);
            plugin.saveConfig();
            arenas.refreshAllJoinItems();
            sender.sendMessage(Component.text("La tête de loup est maintenant donnée dans le monde " + w + ".", NamedTextColor.GREEN));
            return;
        }
        int n = parse(arg, -1);
        if (n < 1 || n > 9) {
            err(sender, "Donne un numéro de case entre 1 et 9.");
            return;
        }
        plugin.getConfig().set("join-item.slot", n - 1);
        plugin.getConfig().set("join-item.enabled", true);
        plugin.saveConfig();
        arenas.resetJoinItems();
        sender.sendMessage(Component.text("La tête de loup est maintenant dans la case " + n + ".", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ /lg test

    private void testCommand(CommandSender sender, Player player, String[] args) {
        if (!requirePlayer(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(Component.text("/lg test <menu> [rôle | nombre de joueurs]", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Menus : " + String.join(", ", TestMenus.MENUS), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Ex. : /lg test vote 12 · /lg test fiche sorciere · /lg test maire", NamedTextColor.GRAY));
            return;
        }
        if (arenas.isActive(player)) {
            err(sender, "Impossible pendant une partie.");
            return;
        }
        if (args[1].equalsIgnoreCase("admin")) {
            Game g = gameFor(sender, player, args.length > 2 ? args[2] : null);
            if (g != null) g.openAdmin(player);
            return;
        }
        if (args[1].equalsIgnoreCase("arenes")) {
            arenas.openSelector(player);
            return;
        }
        String extra = args.length > 2 ? args[2] : null;
        int count = 8;
        String role = null;
        if (extra != null) {
            try {
                count = Integer.parseInt(extra);
            } catch (NumberFormatException e) {
                role = extra;
            }
        }
        String error = tests.open(player, args[1], role, count);
        if (error != null) err(sender, error);
        else if (!args[1].equalsIgnoreCase("fermer") && !args[1].equalsIgnoreCase("carte")) {
            sender.sendMessage(Component.text("Menu de test ouvert : vise et clique · accroupis-toi pour fermer.", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ outils

    /** Partie ciblée par une commande admin (arène donnée, sinon celle du joueur, sinon la seule). */
    private Game gameFor(CommandSender sender, Player player, String arenaArg) {
        Arena a = arenas.contextArena(player, arenaArg);
        if (a == null) {
            err(sender, arenaArg != null ? "Arène inconnue : " + arenaArg
                    : "Précise l'arène (" + String.join(", ", arenas.arenas().stream().map(Arena::id).toList()) + ").");
            return null;
        }
        return arenas.game(a);
    }

    private void err(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        sender.sendMessage("Commande réservée aux joueurs.");
        return false;
    }

    private int parse(String s, int fallback) {
        try {
            return Math.max(1, Math.min(40, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("loupgarou.admin");
        List<String> arenaIds = arenas.arenas().stream().map(Arena::id).toList();
        if (args.length == 1) {
            List<String> all = new ArrayList<>(PLAYER_SUBS);
            if (admin) all.addAll(ADMIN_SUBS);
            for (String s : all) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && (sub.equals("join") || sub.equals("spectate") || (admin && List.of("admin", "start", "forcestart", "stop", "skip").contains(sub)))) {
            for (String s : arenaIds) if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (admin && sub.equals("bot")) {
            if (args.length == 2) {
                for (String s : List.of("1", "4", "8", "15", "retirer")) if (s.startsWith(args[1].toLowerCase())) out.add(s);
            } else if (args.length == 3) {
                for (String s : arenaIds) if (s.startsWith(args[2].toLowerCase())) out.add(s);
            }
        } else if (admin && sub.equals("arena")) {
            if (args.length == 2) {
                for (String s : ARENA_SUBS) if (s.startsWith(args[1].toLowerCase())) out.add(s);
            } else if (args.length == 3 && List.of("edit", "delete", "rename", "info").contains(args[1].toLowerCase())) {
                for (String s : arenaIds) if (s.startsWith(args[2].toLowerCase())) out.add(s);
            }
        } else if (admin && sub.equals("test")) {
            if (args.length == 2) {
                for (String s : TestMenus.MENUS) if (s.startsWith(args[1].toLowerCase())) out.add(s);
                if ("arenes".startsWith(args[1].toLowerCase())) out.add("arenes");
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("fiche") || args[1].equalsIgnoreCase("carte")) {
                    for (Role r : Role.values()) if (r.id().startsWith(args[2].toLowerCase())) out.add(r.id());
                } else {
                    for (String n : List.of("3", "8", "12")) if (n.startsWith(args[2])) out.add(n);
                }
            }
        }
        return out;
    }
}
