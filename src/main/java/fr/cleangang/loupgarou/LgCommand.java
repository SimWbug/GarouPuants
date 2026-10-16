package fr.cleangang.loupgarou;

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
import java.util.List;

public final class LgCommand implements TabExecutor {
    private static final List<String> PLAYER_SUBS = List.of("join", "leave", "menu", "carte", "roles");
    private static final List<String> ADMIN_SUBS = List.of("admin", "test", "slot", "start", "forcestart", "stop", "skip", "setcenter", "setlobby", "reload");

    private final LoupGarouPlugin plugin;
    private final Game game;
    private final TestMenus tests;

    public LgCommand(LoupGarouPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
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

        switch (sub) {
            case "join" -> { if (requirePlayer(sender)) game.join(player); }
            case "leave" -> { if (requirePlayer(sender)) game.leave(player); }
            case "menu" -> { if (requirePlayer(sender)) game.reopenMenu(player); }
            case "carte", "card" -> { if (requirePlayer(sender)) game.showOwnCard(player); }
            case "slot" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Tête de loup : case " + (game.joinSlot() + 1)
                            + " de la barre. Change avec /lg slot <1-9>.", NamedTextColor.YELLOW));
                    List<String> worlds = game.joinWorlds();
                    sender.sendMessage(Component.text("Mondes : " + (worlds.isEmpty() ? "tous" : String.join(", ", worlds))
                            + (plugin.getConfig().getBoolean("join-item.enabled", true) ? "" : " (désactivée !)"), NamedTextColor.GRAY));
                    if (player != null) {
                        sender.sendMessage(Component.text("Ton monde : " + player.getWorld().getName() + " → "
                                + (game.inJoinWorld(player) ? "tête donnée ici" : "pas de tête ici (/lg slot monde pour l'ajouter)"),
                                game.inJoinWorld(player) ? NamedTextColor.GREEN : NamedTextColor.RED));
                    }
                    return true;
                }
                if (args[1].equalsIgnoreCase("monde")) {
                    if (!requirePlayer(sender)) return true;
                    List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("join-item.worlds"));
                    String w = player.getWorld().getName();
                    if (worlds.stream().noneMatch(x -> x.equalsIgnoreCase(w))) worlds.add(w);
                    plugin.getConfig().set("join-item.worlds", worlds);
                    plugin.getConfig().set("join-item.enabled", true);
                    plugin.saveConfig();
                    game.refreshAllJoinItems();
                    sender.sendMessage(Component.text("La tête de loup est maintenant donnée dans le monde " + w + ".", NamedTextColor.GREEN));
                    return true;
                }
                int n = parse(args[1], -1);
                if (n < 1 || n > 9) {
                    sender.sendMessage(Component.text("Donne un numéro de case entre 1 et 9.", NamedTextColor.RED));
                    return true;
                }
                plugin.getConfig().set("join-item.slot", n - 1);
                plugin.getConfig().set("join-item.enabled", true);
                plugin.saveConfig();
                game.refreshAllJoinItems();
                sender.sendMessage(Component.text("La tête de loup est maintenant dans la case " + n + ".", NamedTextColor.GREEN));
            }
            case "admin" -> { if (requirePlayer(sender)) game.openAdmin(player); }
            case "forcestart" -> {
                if (player != null) game.startCountdown(player, true);
                else sender.sendMessage("Lance la commande en jeu.");
            }
            case "start" -> {
                boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
                if (player != null) game.startCountdown(player, force);
                else sender.sendMessage("Lance la commande en jeu.");
            }
            case "stop" -> {
                game.forceStop();
                sender.sendMessage(Component.text("Partie arrêtée.", NamedTextColor.YELLOW));
            }
            case "skip" -> {
                game.skipPhase();
                sender.sendMessage(Component.text("Phase suivante.", NamedTextColor.YELLOW));
            }
            case "setcenter" -> {
                if (!requirePlayer(sender)) return true;
                plugin.getConfig().set("table", player.getLocation().toBlockLocation().add(0.5, 0, 0.5));
                plugin.saveConfig();
                sender.sendMessage(Component.text("Centre de la table enregistré. Les joueurs seront placés en cercle autour.", NamedTextColor.GREEN));
            }
            case "setlobby" -> {
                if (!requirePlayer(sender)) return true;
                plugin.getConfig().set("lobby", player.getLocation());
                plugin.getConfig().set("lobby-world", player.getWorld().getName());
                plugin.saveConfig();
                game.refreshAllJoinItems();
                sender.sendMessage(Component.text("Lobby enregistré.", NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                game.refreshAllJoinItems();
                sender.sendMessage(Component.text("Configuration rechargée.", NamedTextColor.GREEN));
            }
            case "test" -> {
                if (!requirePlayer(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/lg test <menu> [rôle | nombre de joueurs]", NamedTextColor.GOLD));
                    sender.sendMessage(Component.text("Menus : " + String.join(", ", TestMenus.MENUS), NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("Ex. : /lg test vote 12 · /lg test fiche sorciere · /lg test carte loup_garou",
                            NamedTextColor.GRAY));
                    return true;
                }
                if (game.isActive(player)) {
                    sender.sendMessage(Component.text("Impossible pendant une partie.", NamedTextColor.RED));
                    return true;
                }
                if (args[1].equalsIgnoreCase("admin")) {
                    game.openAdmin(player);
                    return true;
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
                if (error != null) sender.sendMessage(Component.text(error, NamedTextColor.RED));
                else if (!args[1].equalsIgnoreCase("fermer") && !args[1].equalsIgnoreCase("carte")) {
                    sender.sendMessage(Component.text("Menu de test ouvert : vise et clique · accroupis-toi pour fermer.", NamedTextColor.GRAY));
                }
            }
            case "roles" -> {
                int n = args.length > 1 ? parse(args[1], game.playerCount()) : Math.max(game.playerCount(), plugin.getConfig().getInt("min-players", 5));
                sender.sendMessage(Component.text("Composition pour " + n + " joueurs : ", NamedTextColor.GOLD)
                        .append(Component.text(game.rolePreview(n), NamedTextColor.WHITE)));
            }
            default -> {
                sender.sendMessage(Component.text("— Loup-Garou —", NamedTextColor.RED));
                sender.sendMessage(Component.text("/lg join | leave | carte | menu | roles [n]", NamedTextColor.GRAY));
                if (admin) sender.sendMessage(Component.text("/lg admin | test <menu> | slot <1-9> | start | forcestart | stop | skip | setcenter | setlobby | reload", NamedTextColor.GRAY));
            }
        }
        return true;
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
        if (args.length == 1) {
            List<String> all = new ArrayList<>(PLAYER_SUBS);
            if (sender.hasPermission("loupgarou.admin")) all.addAll(ADMIN_SUBS);
            for (String s : all) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args[0].equalsIgnoreCase("test") && sender.hasPermission("loupgarou.admin")) {
            if (args.length == 2) {
                for (String s : TestMenus.MENUS) if (s.startsWith(args[1].toLowerCase())) out.add(s);
            } else if (args.length == 3) {
                if (args[1].equalsIgnoreCase("fiche") || args[1].equalsIgnoreCase("carte")) {
                    for (Role r : Role.values()) if (r.id().startsWith(args[2].toLowerCase())) out.add(r.id());
                } else {
                    for (String n : List.of("3", "8", "12", "21")) if (n.startsWith(args[2])) out.add(n);
                }
            }
        }
        return out;
    }
}
