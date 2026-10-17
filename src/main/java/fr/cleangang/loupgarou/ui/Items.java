package fr.cleangang.loupgarou.ui;

import fr.cleangang.loupgarou.game.Role;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Items {
    public static final String TAG_MENU = "menu";
    public static final String TAG_CARD = "card";
    public static final String TAG_JOIN = "join";
    public static final String TAG_MAYOR = "mayor";
    private static NamespacedKey tagKey;

    private Items() {}

    public static void init(NamespacedKey key) {
        tagKey = key;
    }

    public static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) out.add(plain(line, NamedTextColor.GRAY));
        return out;
    }

    public static ItemStack button(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null) meta.lore(lore);
        if (glow) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack potion(Color color, Component name, List<Component> lore, boolean glow) {
        ItemStack item = button(Material.POTION, name, lore, glow);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack head(UUID owner, String name, List<Component> lore, int amount, boolean glow) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, Math.min(64, amount)));
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        meta.displayName(plain(name, NamedTextColor.YELLOW));
        if (lore != null) meta.lore(lore);
        if (glow) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack menuItem() {
        ItemStack item = button(Material.NETHER_STAR, plain("Ouvrir le menu d'action", NamedTextColor.GOLD),
                lore("Clic droit pour rouvrir le menu", "de vote ou de ton rôle."), false);
        return tag(item, TAG_MENU);
    }

    public static ItemStack cardItem(Role role) {
        ItemStack item = button(Material.PAPER, Component.text("Ta carte : " + role.displayName(), role.color()),
                lore("Clic droit : fiche de ton rôle.", "Ne la montre à personne !"), false);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("lgt", "card_" + role.id()));
        item.setItemMeta(meta);
        return tag(item, TAG_CARD);
    }

    /** Face de la carte, toujours visible (pour les menus 3D : le modèle en main montre le dos aux autres). */
    public static ItemStack cardFront(Role role, List<Component> lore) {
        ItemStack item = button(Material.PAPER, Component.text(role.displayName(), role.color()), lore, false);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("lgt", "card_front_" + role.id()));
        item.setItemMeta(meta);
        return item;
    }

    /** Tête de loup de la barre d'action : ouvre le menu de choix des arènes. */
    public static ItemStack joinItem(String queuedArena) {
        boolean joined = queuedArena != null;
        ItemStack item = button(Material.BONE,
                joined ? plain("Loup-Garou — " + queuedArena, NamedTextColor.GOLD) : plain("Jouer au Loup-Garou", NamedTextColor.GOLD),
                joined ? lore("Tu attends dans cette arène.", "Clic droit : changer d'arène ou quitter.")
                        : lore("Clic droit pour choisir une arène.", "Le village t'attend... les loups aussi."), !joined);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("lgt", joined ? "wolf_head_leave" : "wolf_head"));
        item.setItemMeta(meta);
        return tag(item, TAG_JOIN);
    }

    /** Outil de l'éditeur d'arène (le tag commence par « edit_ »). */
    public static ItemStack editTool(String tag, Material material, String name, String... loreLines) {
        ItemStack item = button(material, plain(name, NamedTextColor.AQUA), lore(loreLines), true);
        return tag(item, tag);
    }

    /** Insigne du Maire : ouvre ses pouvoirs (lancer / clore le vote, prolonger le débat). */
    public static ItemStack mayorItem() {
        ItemStack item = button(Material.BELL, plain("♛ Pouvoirs du Maire", NamedTextColor.GOLD),
                lore("Clic droit : lancer le vote, prolonger", "le débat ou clore le vote."), true);
        return tag(item, TAG_MAYOR);
    }

    public static ItemStack mayorHat() {
        return button(Material.GOLDEN_HELMET, plain("♛ Couronne du Maire", NamedTextColor.GOLD),
                lore("Sa voix compte double."), true);
    }

    /** Carte de rôle pour le menu admin : quantité = nombre de cartes, dos de carte si 0. */
    public static ItemStack roleCount(Role role, int count, List<Component> lore) {
        ItemStack item = button(Material.PAPER, Component.text(role.displayName(), role.color()), lore, false);
        item.setAmount(Math.max(1, Math.min(64, count)));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("lgt", count > 0 ? "card_" + role.id() : "card_back_item"));
        item.setItemMeta(meta);
        return item;
    }

    public static String tagOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }

    private static ItemStack tag(ItemStack item, String value) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, value);
        item.setItemMeta(meta);
        return item;
    }
}
