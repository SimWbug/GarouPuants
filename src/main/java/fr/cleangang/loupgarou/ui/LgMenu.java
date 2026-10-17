package fr.cleangang.loupgarou.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Contenu d'un menu (cases → objet + action). Le contenu est reconstruit par {@link #render()} à chaque changement.
 * Il est affiché soit en 3D devant la caméra ({@link HoloMenu}, menus de jeu), soit dans un inventaire (menu admin).
 */
public final class LgMenu implements InventoryHolder {
    public static final int[] HEAD_SLOTS;
    public static final int INFO_SLOT = 4;
    public static final int LEFT_BUTTON = 47;
    public static final int MIDDLE_BUTTON = 49;
    public static final int RIGHT_BUTTON = 51;
    /** Fiche de rôle : la grande carte. */
    public static final int SHEET_CARD = 0;
    /** Résumé de fin de partie : objet dont la description contient l'historique. */
    public static final int HISTORY_SLOT = 8;

    static {
        HEAD_SLOTS = new int[21];
        int i = 0;
        for (int row = 1; row <= 3; row++) {
            for (int col = 1; col <= 7; col++) {
                HEAD_SLOTS[i++] = row * 9 + col;
            }
        }
    }

    private final Inventory inventory;
    private final MenuTheme theme;
    private final Consumer<LgMenu> renderer;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private final Map<Integer, ItemStack> items = new LinkedHashMap<>();
    private Runnable onRender;

    // ---- mise en page libre (menus 3D dessinés à la main, ex. choix de l'arène)
    /** Position d'un objet : x, y en blocs (0 = centre), taille sx × sy, z (+ = plus loin), rayon de visée. */
    public record Layout(double x, double y, float sx, float sy, double z, double radius, boolean flat, boolean leftAnchored) {
        public Layout(double x, double y, float sx, float sy, double z, double radius, boolean flat) {
            this(x, y, sx, sy, z, radius, flat, false);
        }
    }

    /**
     * Infos d'une cible pour l'affichage en bannières : statut court, voix (jauge + têtes des votants),
     * sélection, texte d'aide au survol. votes < 0 = pas de jauge.
     */
    public record Entry(String status, int votes, int maxVotes, List<Map.Entry<UUID, String>> voters, boolean selected,
                        String hint) {}
    /** Texte libre : centré en x, y = centre vertical du texte. */
    public record Text(Component text, double x, double y, float scale, int lineWidth, double z) {}
    /** Panneau de fond : largeur, haut, bas, distance au joueur. */
    public record Panel(double width, double top, double bottom, double depth) {}

    private final Map<Integer, Layout> layouts = new HashMap<>();
    private final Map<Integer, Text> texts = new LinkedHashMap<>();
    private Panel panel;
    private boolean lastClickRight;
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final Map<Integer, String> hints = new HashMap<>();
    private IntSupplier timeLeft;
    private IntSupplier timeTotal;
    private double timerWidth;

    public LgMenu(MenuTheme theme, Consumer<LgMenu> renderer) {
        this(theme, Glyphs.menuTitle(theme), renderer);
    }

    public LgMenu(MenuTheme theme, Component title, Consumer<LgMenu> renderer) {
        this.theme = theme;
        this.renderer = renderer;
        this.inventory = Bukkit.createInventory(this, 54, title);
        render();
    }

    public void render() {
        inventory.clear();
        actions.clear();
        items.clear();
        layouts.clear();
        texts.clear();
        entries.clear();
        hints.clear();
        panel = null;
        renderer.accept(this);
        if (BoardLayout.applies(this)) BoardLayout.apply(this);
        if (onRender != null) onRender.run();
    }

    public void set(int slot, ItemStack item, Runnable action) {
        // les éléments des menus 3D ont des numéros au-delà des 54 cases de l'inventaire
        if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, item);
        if (item != null) items.put(slot, item);
        if (action != null) actions.put(slot, action);
    }

    /** Place un objet à une position libre (mode « mise en page libre »). */
    public void place(int slot, ItemStack item, Runnable action, Layout layout) {
        set(slot, item, action);
        layouts.put(slot, layout);
    }

    public void text(int id, Component text, double x, double y, float scale, int lineWidth, double z) {
        texts.put(id, new Text(text, x, y, scale, lineWidth, z));
    }

    public void panel(double width, double top, double bottom, double depth) {
        this.panel = new Panel(width, top, bottom, depth);
    }

    public void entry(int slot, Entry entry) {
        entries.put(slot, entry);
    }

    public Entry entry(int slot) {
        return entries.get(slot);
    }

    /** Texte affiché sous le panneau quand le joueur vise cet élément. */
    public void hint(int slot, String text) {
        hints.put(slot, text);
    }

    public String hint(int slot) {
        return hints.get(slot);
    }

    /** Sablier du menu : temps restant / durée de la phase (en secondes, -1 = pas de minuterie). */
    public void timer(IntSupplier left, IntSupplier total) {
        this.timeLeft = left;
        this.timeTotal = total;
    }

    public int timeLeft() {
        return timeLeft == null ? -1 : timeLeft.getAsInt();
    }

    public int timeTotal() {
        return timeTotal == null ? -1 : Math.max(1, timeTotal.getAsInt());
    }

    void timerWidth(double w) {
        this.timerWidth = w;
    }

    public double timerWidth() {
        return timerWidth;
    }

    Runnable action(int slot) {
        return actions.get(slot);
    }

    void removeItem(int slot) {
        items.remove(slot);
        actions.remove(slot);
    }

    public boolean custom() {
        return panel != null;
    }

    public Panel panel() {
        return panel;
    }

    public Map<Integer, Layout> layouts() {
        return layouts;
    }

    public Map<Integer, Text> texts() {
        return texts;
    }

    /** Appelé après chaque {@link #render()} (mise à jour de l'affichage 3D). */
    public void onRender(Runnable hook) {
        this.onRender = hook;
    }

    public Map<Integer, ItemStack> items() {
        return items;
    }

    public boolean clickable(int slot) {
        return actions.containsKey(slot);
    }

    public void click(int slot, boolean rightClick) {
        lastClickRight = rightClick;
        Runnable action = actions.get(slot);
        if (action != null) action.run();
    }

    /** Vrai si le dernier clic était un clic droit (utile pour les boutons +1 / -1). */
    public boolean lastClickRight() {
        return lastClickRight;
    }

    public MenuTheme theme() {
        return theme;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
