package fr.cleangang.loupgarou.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transforme un menu de jeu « grille » (têtes + 3 boutons + info) en tableau 3D :
 * bandeau de titre à la couleur du rôle, une bannière par joueur (tête, pseudo, jauge de voix et têtes des votants),
 * boutons unifiés (vert = valider, rouge = danger, gris = passer), sablier et ligne d'aide.
 */
final class BoardLayout {
    static final double DEPTH = 4.0;
    static final int TIMER_BG = 9000;
    static final int TIMER_FILL = 9001;
    static final int TIMER_TEXT = 9001;
    static final int HINT_TEXT = 9999;
    static final Key ARCHIVO = Key.key("lgt", "archivo");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final double ROW_H = 0.44;
    private static final double BAR_H = 0.38;
    private static final double BTN_W = 1.36;
    private static final double BTN_H = 0.40;

    private BoardLayout() {}

    static boolean applies(LgMenu m) {
        if (m.custom()) return false;
        return switch (m.theme()) {
            case VOTE, WOLVES, SEER, WITCH, CUPID, HUNTER, GUARD, MAYOR_VOTE, MAYOR, SUCCESSION -> true;
            default -> false;
        };
    }

    static Component font(String text, TextColor color) {
        return Items.menuText(text, color);
    }

    static ItemStack flat(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("lgt", model));
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    static void apply(LgMenu m) {
        Map<Integer, ItemStack> items = m.items();
        List<Integer> heads = new ArrayList<>();
        for (int slot : LgMenu.HEAD_SLOTS) if (items.containsKey(slot)) heads.add(slot);
        List<Integer> buttons = new ArrayList<>();
        for (int slot : new int[]{LgMenu.LEFT_BUTTON, LgMenu.MIDDLE_BUTTON, LgMenu.RIGHT_BUTTON}) {
            if (items.containsKey(slot)) buttons.add(slot);
        }
        ItemStack info = items.get(LgMenu.INFO_SLOT);
        if (info != null) m.removeItem(LgMenu.INFO_SLOT);

        // 1 colonne jusqu'à 4 joueurs, 2 jusqu'à 10, 3 au-delà (16 joueurs = 5 lignes)
        int cols = heads.size() <= 4 ? 1 : heads.size() <= 10 ? 2 : 3;
        int rows = (heads.size() + cols - 1) / cols;
        double barW = cols == 1 ? 3.5 : cols == 2 ? 2.8 : 2.6;
        double colStep = cols == 2 ? 2.9 : 2.75;
        double width = heads.isEmpty() ? Math.max(4.4, buttons.size() * (BTN_W + 0.2) + 0.8)
                : cols == 1 ? 4.5 : cols == 2 ? 6.3 : 8.75;

        // ---- sous-titre (nom + description de l'objet d'info)
        List<Component> sub = new ArrayList<>();
        if (info != null && info.getItemMeta() != null) {
            ItemMeta meta = info.getItemMeta();
            if (meta.displayName() != null) sub.add(meta.displayName().decoration(TextDecoration.BOLD, true));
            if (meta.lore() != null) for (Component c : meta.lore()) if (sub.size() < 4) sub.add(c);
        }
        int timeLeft = m.timeLeft();
        boolean timer = timeLeft >= 0;

        // ---- hauteurs (calculées depuis le haut, puis centrées)
        double subScale = 0.42;
        double subH = sub.size() * 0.25 * subScale;
        double y = 0;
        double titleY = y - 0.34;
        y = titleY - 0.28;
        double subTop = y;
        y -= subH + (sub.isEmpty() ? 0 : 0.12);
        double rowsTop = y;
        y -= rows * ROW_H;
        double buttonsY = y - 0.06 - BTN_H / 2;
        if (!buttons.isEmpty()) y = buttonsY - BTN_H / 2;
        double timerY = y - 0.16;
        if (timer) y = timerY - 0.04;
        double hintY = y - 0.2;
        double bottom = hintY - 0.18;
        double shift = 0.05 - bottom / 2; // tableau centré sur le viseur
        double top = shift;

        m.panel(width, top, bottom + shift, DEPTH);

        // ---- bandeau de titre
        String title = m.theme().title().toUpperCase();
        m.place(9100, flat("title_" + m.theme().name().toLowerCase()), null,
                new LgMenu.Layout(0, titleY + shift, 2.9f, 0.33f, 0.02, 0, true));
        m.text(9100, font(title, NamedTextColor.WHITE), 0, titleY + shift, fit(0.6, title, 22), 400, -0.01);

        if (!sub.isEmpty()) {
            double subY = subTop - subH / 2 + shift;
            boolean headInfo = info.getType() == Material.PLAYER_HEAD;
            m.text(9101, Component.join(JoinConfiguration.newlines(), sub), headInfo ? 0.25 : 0, subY, (float) subScale, 330, 0);
            if (headInfo) {
                ItemStack head = info.clone();
                m.place(9102, head, null, new LgMenu.Layout(-1.25, subY, 0.36f, 0.36f, 0, 0, false));
            }
        }

        // ---- bannières des joueurs
        for (int i = 0; i < heads.size(); i++) {
            int slot = heads.get(i);
            int col = i % cols;
            int row = i / cols;
            double cx = (col - (cols - 1) / 2.0) * colStep;
            double cy = rowsTop - ROW_H / 2 - row * ROW_H + shift;
            banner(m, slot, items.get(slot), cx, cy, barW);
        }

        // ---- boutons
        double spacing = BTN_W + 0.2;
        for (int i = 0; i < buttons.size(); i++) {
            int slot = buttons.get(i);
            double bx = (i - (buttons.size() - 1) / 2.0) * spacing;
            button(m, slot, items.get(slot), bx, buttonsY + shift);
        }

        // ---- sablier
        if (timer) {
            double tw = width - 1.4;
            int total = m.timeTotal();
            double frac = Math.max(0, Math.min(1, (timeLeft - 1) / (double) total));
            m.timerWidth(tw);
            m.place(TIMER_BG, flat("timer_bg"), null, new LgMenu.Layout(-0.25, timerY + shift, (float) tw, 0.09f, 0.01, 0, true));
            m.place(TIMER_FILL, flat(timeLeft <= 10 ? "timer_red" : "timer_fill"), null,
                    new LgMenu.Layout(-0.25 - tw / 2, timerY + shift, (float) Math.max(0.001, tw * frac), 0.07f, 0, 0, true, true));
            m.text(TIMER_TEXT, timerText(timeLeft), tw / 2 + 0.05, timerY + shift, 0.42f, 200, 0);
        }

        // ---- ligne d'aide (remplacée par l'aide de l'élément visé)
        m.text(HINT_TEXT, defaultHint(), 0, hintY + shift, 0.36f, 500, 0);
    }

    static Component timerText(int left) {
        return font(left + " S", left <= 10 ? TextColor.color(0xFF6A5A) : NamedTextColor.WHITE);
    }

    static Component defaultHint() {
        return Component.text("Vise avec le viseur · clic pour choisir · accroupis-toi pour fermer", TextColor.color(0xBFD9FF));
    }

    // ------------------------------------------------------------------ bannière d'un joueur

    private static void banner(LgMenu m, int slot, ItemStack head, double cx, double cy, double barW) {
        ItemMeta meta = head.getItemMeta();
        String name = meta != null && meta.displayName() != null ? PLAIN.serialize(meta.displayName()) : "?";
        List<String> lore = new ArrayList<>();
        if (meta != null && meta.lore() != null) for (Component c : meta.lore()) lore.add(PLAIN.serialize(c).trim());
        LgMenu.Entry e = m.entry(slot);
        boolean selected = e != null ? e.selected() : meta != null && meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride();
        String status = e != null ? e.status() : lore.isEmpty() || lore.get(0).startsWith("Clique") ? null : lore.get(0);
        String hint = e != null ? e.hint() : null;
        if (hint == null) {
            for (String l : lore) if (l.startsWith("Clique")) hint = l + " — " + name;
        }
        if (hint != null && m.hint(slot) == null) m.hint(slot, hint);
        boolean clickable = m.action(slot) != null;

        int base = 10000 + slot * 40;
        // fond (élément cliquable : garde l'action de la tête)
        m.place(slot, flat(selected ? "player_bar_me" : clickable ? "player_bar" : "player_bar_off"), m.action(slot),
                new LgMenu.Layout(cx, cy, (float) barW, (float) BAR_H, 0.03, 0, true));
        // tête
        ItemStack icon = new ItemStack(Material.PLAYER_HEAD);
        if (meta instanceof SkullMeta sm && sm.getOwningPlayer() != null) {
            SkullMeta im = (SkullMeta) icon.getItemMeta();
            im.setOwningPlayer(sm.getOwningPlayer());
            icon.setItemMeta(im);
        }
        double left = cx - barW / 2;
        m.place(base, icon, null, new LgMenu.Layout(left + 0.24, cy, 0.3f, 0.3f, -0.02, 0, false));
        // pseudo
        double nameZone = barW * 0.43;
        double nameX = left + 0.46 + nameZone / 2;
        m.text(base, font(name.toUpperCase(), NamedTextColor.WHITE), nameX, cy, fit(0.42, name, 11), 300, -0.02);

        double rightStart = left + 0.46 + nameZone + 0.08;
        double rightEnd = cx + barW / 2 - 0.12;
        if (e != null && e.votes() >= 0) {
            // jauge de voix + nombre + têtes des votants
            double gw = rightEnd - rightStart - 0.3;
            double frac = Math.max(0, Math.min(1, e.votes() / (double) Math.max(1, e.maxVotes())));
            boolean red = m.theme() == MenuTheme.WOLVES;
            m.place(base + 1, flat("gauge_bg"), null, new LgMenu.Layout(rightStart + gw / 2, cy - 0.08, (float) gw, 0.075f, 0, 0, true));
            m.place(base + 2, flat(red ? "gauge_red" : "gauge_gold"), null,
                    new LgMenu.Layout(rightStart, cy - 0.08, (float) Math.max(0.001, gw * frac), 0.06f, -0.01, 0, true, true));
            m.text(base + 1, font(String.valueOf(e.votes()), red ? TextColor.color(0xFF8070) : TextColor.color(0xFFD24A)),
                    rightEnd - 0.12, cy, 0.55f, 100, -0.02);
            int max = (int) Math.floor(gw / 0.16);
            List<Map.Entry<UUID, String>> voters = e.voters();
            for (int v = 0; v < voters.size() && v < max; v++) {
                ItemStack mini = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) mini.getItemMeta();
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(voters.get(v).getKey()));
                mini.setItemMeta(sm);
                m.place(base + 3 + v, mini, null, new LgMenu.Layout(rightStart + 0.07 + v * 0.16, cy + 0.08, 0.14f, 0.14f, -0.02, 0, false));
            }
        } else if (status != null) {
            TextColor c = selected ? NamedTextColor.WHITE : TextColor.color(0xBFD9FF);
            m.text(base + 1, font(status.toUpperCase(), c), (rightStart + rightEnd) / 2, cy, fit(0.38, status, 17), 300, -0.02);
        }
    }

    // ------------------------------------------------------------------ boutons unifiés

    private static void button(LgMenu m, int slot, ItemStack item, double x, double y) {
        ItemMeta meta = item.getItemMeta();
        String label = meta != null && meta.displayName() != null ? PLAIN.serialize(meta.displayName()) : "";
        label = label.replace("♛", "").trim().toUpperCase();
        boolean selected = meta != null && meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride();
        if (selected && !label.startsWith("✔")) label = "✔ " + label;
        boolean clickable = m.action(slot) != null;
        String color = clickable ? colorOf(item) : "gray";
        if (meta != null && meta.lore() != null && !meta.lore().isEmpty() && m.hint(slot) == null) {
            m.hint(slot, PLAIN.serialize(meta.lore().get(0)));
        }
        m.place(slot, flat("bt_" + color), m.action(slot), new LgMenu.Layout(x, y, (float) BTN_W, (float) BTN_H, 0.02, 0, true));
        m.text(20000 + slot, font(label, clickable ? NamedTextColor.WHITE : TextColor.color(0xDDDDDD)), x, y, fit(0.48, label, 12), 300, -0.01);
    }

    private static String colorOf(ItemStack item) {
        Material t = item.getType();
        if (t == Material.POTION && item.getItemMeta() instanceof PotionMeta pm && pm.getColor() != null) {
            Color c = pm.getColor();
            return c.getGreen() > c.getRed() ? "green" : "red";
        }
        return switch (t) {
            case LIME_DYE, EMERALD -> "green";
            case RED_DYE, TNT -> "red";
            case LIGHTNING_ROD, ORANGE_DYE -> "orange";
            case BELL, GOLDEN_HELMET -> "gold";
            case CLOCK -> "blue";
            default -> "gray";
        };
    }

    /** Échelle du texte réduite pour les textes longs. */
    static float fit(double scale, String text, int maxChars) {
        return (float) Math.min(scale, scale * maxChars / Math.max(1, text.length()));
    }
}
