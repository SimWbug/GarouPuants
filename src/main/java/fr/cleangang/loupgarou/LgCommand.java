package fr.cleangang.loupgarou;

import fr.cleangang.loupgarou.game.Game;
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
    private static final List<String> ADMIN_SUBS = List.of("admin", "start", "stop", "skip", "setcenter", "setlobby", "reload");

    private final LoupGarouPlugin plugin;
    private final Game game;

    public LgCommand(LoupGarouPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
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
            case "admin" -> { if (requirePlayer(sender)) game.openAdmin(player); }
            case "start" -> {
                if (player != null) game.startCountdown(player);
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
                plugin.saveConfig();
                sender.sendMessage(Component.text("Lobby enregistré.", NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("Configuration rechargée.", NamedTextColor.GREEN));
            }
            case "roles" -> {
                int n = args.length > 1 ? parse(args[1], game.playerCount()) : Math.max(game.playerCount(), plugin.getConfig().getInt("min-players", 5));
                sender.sendMessage(Component.text("Composition pour " + n + " joueurs : ", NamedTextColor.GOLD)
                        .append(Component.text(game.rolePreview(n), NamedTextColor.WHITE)));
            }
            default -> {
                sender.sendMessage(Component.text("— Loup-Garou —", NamedTextColor.RED));
                sender.sendMessage(Component.text("/lg join | leave | carte | menu | roles [n]", NamedTextColor.GRAY));
                if (admin) sender.sendMessage(Component.text("/lg admin | start | stop | skip | setcenter | setlobby | reload", NamedTextColor.GRAY));
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
        }
        return out;
    }
}
