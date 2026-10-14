package fr.cleangang.loupgarou;

import fr.cleangang.loupgarou.game.Game;
import fr.cleangang.loupgarou.game.GameListener;
import fr.cleangang.loupgarou.ui.Items;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.util.UUID;

public final class LoupGarouPlugin extends JavaPlugin {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("loupgaroumc-pack".getBytes());

    private Game game;
    private ResourcePackInfo cachedPack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Items.init(new NamespacedKey(this, "item"));
        game = new Game(this);
        getServer().getPluginManager().registerEvents(new GameListener(game), this);
        LgCommand command = new LgCommand(this, game);
        PluginCommand lg = getCommand("lg");
        if (lg != null) {
            lg.setExecutor(command);
            lg.setTabCompleter(command);
        }
        game.refreshAllJoinItems();
        getLogger().info("Loup-Garou prêt. /lg join pour rejoindre le village.");
    }

    @Override
    public void onDisable() {
        if (game != null) game.forceStop();
        // retire les têtes de loup pour ne pas laisser d'objets orphelins
        for (Player p : Bukkit.getOnlinePlayers()) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (Items.TAG_JOIN.equals(Items.tagOf(inv.getItem(i)))) inv.setItem(i, null);
            }
        }
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        cachedPack = null;
    }

    /** Envoie le resource pack configuré (cartes + menus). Le sha1 est calculé si absent. */
    public void sendResourcePack(Player player) {
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        if (cachedPack != null) {
            sendPack(player, cachedPack);
            return;
        }
        String sha1 = getConfig().getString("resource-pack.sha1", "");
        if (sha1 != null && !sha1.isBlank()) {
            cachedPack = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1.trim().toLowerCase());
            sendPack(player, cachedPack);
            return;
        }
        UUID target = player.getUniqueId();
        ResourcePackInfo.resourcePackInfo().id(PACK_ID).uri(URI.create(url)).computeHashAndBuild()
                .thenAccept(info -> Bukkit.getScheduler().runTask(this, () -> {
                    cachedPack = info;
                    Player p = Bukkit.getPlayer(target);
                    if (p != null) sendPack(p, info);
                }))
                .exceptionally(err -> {
                    getLogger().warning("Impossible de télécharger le resource pack : " + err.getMessage());
                    return null;
                });
    }

    private void sendPack(Player player, ResourcePackInfo info) {
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .replace(false)
                .required(getConfig().getBoolean("resource-pack.required", false))
                .prompt(Component.text("Pack Loup-Garou : cartes de rôle et menus de vote", NamedTextColor.GOLD))
                .build());
    }
}
