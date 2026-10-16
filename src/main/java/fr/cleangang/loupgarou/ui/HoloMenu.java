package fr.cleangang.loupgarou.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menu 3D flottant devant la caméra, visible uniquement par son joueur.
 * Le viseur sert de curseur : l'élément visé grossit et s'illumine, n'importe quel clic le sélectionne.
 * S'accroupir ferme le menu (l'étoile ou /lg menu le rouvre).
 */
public final class HoloMenu {

    @FunctionalInterface
    public interface ClickHandler {
        void click(LgMenu menu, int slot, boolean rightClick);
    }

    private static final Map<UUID, HoloMenu> OPEN = new HashMap<>();
    private static Plugin plugin;
    private static long tickCounter;

    // ---- mise en page (en blocs, relative aux yeux du joueur) ----
    private static final double DEPTH = 3.4;
    private static final double COL_SPACING = 0.58;
    private static final double ROW_SPACING = 0.68;
    private static final double FIRST_ROW_Y = 0.30;
    private static final int PER_ROW = 8;
    /** Hauteur d'une ligne de TextDisplay à l'échelle 1 (en blocs). */
    private static final double LINE = 0.25;
    /** Écart entre le centre d'une icône et le haut de son étiquette. */
    private static final double LABEL_GAP = 0.27;
    private static final float ICON_SCALE = 0.42f;
    private static final float HOVER_FACTOR = 1.3f;

    private final Player player;
    private final LgMenu menu;
    private final ClickHandler handler;
    private final Location eye;
    private final Vector forward;
    private final Vector right;
    private final Map<Integer, Element> elements = new LinkedHashMap<>();
    private TextDisplay header;
    private TextDisplay hint;
    private TextDisplay backdrop;
    private Element hovered;
    private long lastClick = -10;
    private boolean closed;

    private HoloMenu(Player player, LgMenu menu, ClickHandler handler) {
        this.player = player;
        this.menu = menu;
        this.handler = handler;
        this.eye = player.getEyeLocation().clone();
        double yaw = Math.toRadians(eye.getYaw());
        this.forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        this.right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
    }

    // ------------------------------------------------------------------ API statique

    public static void init(Plugin owner) {
        plugin = owner;
        Bukkit.getScheduler().runTaskTimer(owner, () -> {
            tickCounter++;
            for (HoloMenu holo : new ArrayList<>(OPEN.values())) holo.tick();
        }, 1L, 1L);
    }

    /** Ouvre (ou remplace) le menu 3D du joueur. */
    public static void open(Player player, LgMenu menu, ClickHandler handler) {
        close(player);
        HoloMenu holo = new HoloMenu(player, menu, handler);
        OPEN.put(player.getUniqueId(), holo);
        menu.onRender(holo::rebuild);
        holo.rebuild();
        holo.sound("block.amethyst_block.resonate", 0.6f, 1.4f);
    }

    public static HoloMenu get(Player player) {
        HoloMenu holo = OPEN.get(player.getUniqueId());
        return holo == null || holo.closed ? null : holo;
    }

    public static boolean isOpen(Player player) {
        return get(player) != null;
    }

    public static void close(Player player) {
        HoloMenu holo = OPEN.remove(player.getUniqueId());
        if (holo != null) holo.destroy();
    }

    public static void closeAll() {
        for (HoloMenu holo : new ArrayList<>(OPEN.values())) holo.destroy();
        OPEN.clear();
    }

    public LgMenu menu() {
        return menu;
    }

    // ------------------------------------------------------------------ construction

    private void rebuild() {
        if (closed) return;
        if (!player.isOnline()) {
            close(player);
            return;
        }
        boolean sheet = menu.theme() == MenuTheme.SHEET;
        Map<Integer, ItemStack> items = menu.items();
        Map<Integer, Placement> wanted = new LinkedHashMap<>();

        double bottomY;
        double width;
        if (sheet) {
            ItemStack card = items.get(LgMenu.SHEET_CARD);
            if (card != null) wanted.put(LgMenu.SHEET_CARD, new Placement(-1.0, 0.15, 1.5f, 0.75, true, Double.NaN));
            ItemStack close = items.get(LgMenu.MIDDLE_BUTTON);
            if (close != null) wanted.put(LgMenu.MIDDLE_BUTTON, new Placement(0.95, -1.0, ICON_SCALE, 0.35, true, -1.0 - LABEL_GAP));
            bottomY = -1.75;
            width = 4.0;
        } else {
            // grille de cibles (têtes des joueurs)
            List<Integer> heads = new ArrayList<>();
            for (int slot : LgMenu.HEAD_SLOTS) if (items.containsKey(slot)) heads.add(slot);
            int rows = (heads.size() + PER_ROW - 1) / PER_ROW;
            for (int i = 0; i < heads.size(); i++) {
                int row = i / PER_ROW;
                int inRow = Math.min(PER_ROW, heads.size() - row * PER_ROW);
                int col = i % PER_ROW;
                double x = (col - (inRow - 1) / 2.0) * COL_SPACING;
                double y = FIRST_ROW_Y - row * ROW_SPACING;
                wanted.put(heads.get(i), new Placement(x, y, ICON_SCALE, 0.28, true, y - LABEL_GAP));
            }
            double lastRowY = rows == 0 ? FIRST_ROW_Y + ROW_SPACING : FIRST_ROW_Y - (rows - 1) * ROW_SPACING;
            double buttonsY = lastRowY - 0.78;
            boolean anyButton = false;
            int[] buttons = {LgMenu.LEFT_BUTTON, LgMenu.MIDDLE_BUTTON, LgMenu.RIGHT_BUTTON};
            double[] bx = {-1.15, 0, 1.15};
            for (int b = 0; b < buttons.length; b++) {
                if (!items.containsKey(buttons[b])) continue;
                anyButton = true;
                wanted.put(buttons[b], new Placement(bx[b], buttonsY, ICON_SCALE, 0.38, true, buttonsY - LABEL_GAP));
            }
            // icône d'info (ex. victime des loups pour la Sorcière), à gauche du titre
            ItemStack info = items.get(LgMenu.INFO_SLOT);
            if (info != null) wanted.put(LgMenu.INFO_SLOT, new Placement(-2.0, 0.95, 0.45f, 0, false, Double.NaN));
            // marge sous les étiquettes : de la place pour le détail affiché au survol
            bottomY = (anyButton ? buttonsY : lastRowY) - LABEL_GAP - 0.3 - 0.5;
            int cols = Math.min(PER_ROW, Math.max(3, heads.size()));
            width = Math.max(4.6, cols * COL_SPACING + 0.9);
        }

        // supprime les éléments disparus
        elements.entrySet().removeIf(e -> {
            if (wanted.containsKey(e.getKey())) return false;
            e.getValue().remove();
            if (hovered == e.getValue()) hovered = null;
            return true;
        });
        // crée / met à jour les autres
        for (Map.Entry<Integer, Placement> e : wanted.entrySet()) {
            int slot = e.getKey();
            ItemStack item = items.get(slot);
            Element el = elements.get(slot);
            if (el == null) {
                el = new Element(slot, e.getValue());
                elements.put(slot, el);
                el.spawn(item);
            } else {
                el.update(item, e.getValue());
            }
        }

        // titre / texte d'en-tête
        Component headerText = sheet ? sheetText(items.get(LgMenu.INFO_SLOT)) : headerText(items.get(LgMenu.INFO_SLOT));
        if (sheet) {
            header = text(header, at(0.95, -0.7, DEPTH), headerText, 0.52f, 150, TextDisplay.TextAlignment.LEFT,
                    Color.fromARGB(0, 0, 0, 0));
        } else {
            header = text(header, at(0, 0.68, DEPTH), headerText, 0.6f, 220, TextDisplay.TextAlignment.CENTER,
                    Color.fromARGB(0, 0, 0, 0));
        }
        Component hintText = Component.text("Vise avec le viseur · clic pour choisir · accroupis-toi pour fermer", NamedTextColor.GRAY);
        hint = text(hint, at(0, bottomY + 0.08, DEPTH), hintText, 0.38f, 400, TextDisplay.TextAlignment.CENTER,
                Color.fromARGB(0, 0, 0, 0));

        // panneau de fond semi-transparent
        double top = sheet ? 1.5 : 1.45;
        double height = top - bottomY;
        TextColor c = menu.theme().color();
        Color panel = Color.fromARGB(150, c.red() / 6, c.green() / 6, c.blue() / 6);
        // le panneau est 0,3 bloc plus loin : on l'agrandit d'autant pour qu'il couvre bien tout le menu
        double k = (DEPTH + 0.3) / DEPTH;
        double panelBottom = bottomY - 0.1;
        backdrop = text(backdrop, at(0, panelBottom * k, DEPTH + 0.3), Component.text(" "), 1f, 200,
                TextDisplay.TextAlignment.CENTER, panel);
        backdrop.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f((float) (width * k / 0.15), (float) ((top - panelBottom) * k / 0.275), 1f), new Quaternionf()));
    }

    private Component headerText(ItemStack info) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(menu.theme().title().toUpperCase(), menu.theme().color()).decorate(TextDecoration.BOLD));
        if (info != null) {
            ItemMeta meta = info.getItemMeta();
            if (meta != null && meta.displayName() != null) lines.add(meta.displayName());
            if (meta != null && meta.lore() != null) lines.addAll(meta.lore());
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private Component sheetText(ItemStack info) {
        List<Component> lines = new ArrayList<>();
        if (info != null) {
            ItemMeta meta = info.getItemMeta();
            if (meta != null && meta.displayName() != null) lines.add(meta.displayName().decorate(TextDecoration.BOLD));
            if (meta != null && meta.lore() != null) lines.addAll(meta.lore());
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    // ------------------------------------------------------------------ boucle

    private void tick() {
        if (closed) return;
        if (!player.isOnline() || !player.getWorld().equals(eye.getWorld())
                || player.getLocation().distanceSquared(eye) > 16) {
            close(player);
            return;
        }
        if (tickCounter % 2 != 0) return;
        Element best = null;
        double bestScore = 1.0;
        Location look = player.getEyeLocation();
        Vector dir = look.getDirection();
        Vector origin = look.toVector();
        for (Element el : elements.values()) {
            if (!el.placement.clickable || !menu.clickable(el.slot)) continue;
            Vector to = el.center.toVector().subtract(origin);
            double dist = to.length();
            if (dist < 0.1) continue;
            double score = dir.angle(to) / Math.atan(el.placement.radius / dist);
            if (score < bestScore) {
                bestScore = score;
                best = el;
            }
        }
        if (best != hovered) {
            if (hovered != null) hovered.highlight(false);
            hovered = best;
            if (best != null) {
                best.highlight(true);
                sound("block.note_block.hat", 0.35f, 1.8f);
            }
        }
    }

    /** Appelé par les événements de clic (gauche ou droit). */
    /** @return vrai si le clic a été pris par le menu (un élément était visé). */
    public boolean click(boolean rightClick) {
        if (closed || hovered == null || !menu.clickable(hovered.slot)) return false;
        if (tickCounter - lastClick < 4) return true; // un même clic déclenche parfois plusieurs événements
        lastClick = tickCounter;
        sound("ui.button.click", 0.6f, 1.2f);
        Element el = hovered;
        el.pulse();
        handler.click(menu, el.slot, rightClick);
        return true;
    }

    private void destroy() {
        closed = true;
        for (Element el : elements.values()) el.remove();
        elements.clear();
        if (header != null) header.remove();
        if (hint != null) hint.remove();
        if (backdrop != null) backdrop.remove();
        menu.onRender(null);
    }

    // ------------------------------------------------------------------ outils

    private Location at(double x, double y, double depth) {
        Location l = eye.clone()
                .add(forward.clone().multiply(depth))
                .add(right.clone().multiply(x))
                .add(0, y, 0);
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    private <T extends Display> T prepare(T e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.setBillboard(Display.Billboard.CENTER);
        e.setBrightness(new Display.Brightness(15, 15));
        e.setViewRange(0.5f);
        e.setTeleportDuration(2);
        return e;
    }

    private TextDisplay text(TextDisplay existing, Location loc, Component content, float scale, int lineWidth,
                             TextDisplay.TextAlignment align, Color background) {
        TextDisplay td = existing;
        if (td == null || !td.isValid()) {
            World w = loc.getWorld();
            td = w.spawn(loc, TextDisplay.class, e -> {
                prepare(e);
                e.setShadowed(true);
                e.setSeeThrough(false);
                e.setDefaultBackground(false);
            });
            player.showEntity(plugin, td);
        } else if (td.getLocation().distanceSquared(loc) > 0.0001) {
            td.teleport(loc);
        }
        td.text(content);
        td.setLineWidth(lineWidth);
        td.setAlignment(align);
        td.setBackgroundColor(background);
        td.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
        return td;
    }

    private void sound(String key, float volume, float pitch) {
        player.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }

    private record Placement(double x, double y, float scale, double radius, boolean clickable, double labelY) {}

    /** Un objet du menu : icône 3D + étiquette en dessous. */
    private final class Element {
        final int slot;
        Placement placement;
        Location center;
        ItemDisplay icon;
        TextDisplay label;
        ItemStack item;
        boolean lit;

        Element(int slot, Placement placement) {
            this.slot = slot;
            this.placement = placement;
            this.center = at(placement.x(), placement.y(), DEPTH);
        }

        void spawn(ItemStack stack) {
            this.item = stack;
            icon = center.getWorld().spawn(center, ItemDisplay.class, e -> {
                prepare(e);
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
                e.setTransformation(scaleOf(placement.scale()));
                e.setGlowColorOverride(toColor(menu.theme().color()));
            });
            player.showEntity(plugin, icon);
            updateLabel();
        }

        void update(ItemStack stack, Placement p) {
            boolean moved = p.x() != placement.x() || p.y() != placement.y();
            this.placement = p;
            if (moved) {
                center = at(p.x(), p.y(), DEPTH);
                icon.teleport(center);
            }
            if (!stack.equals(item)) {
                item = stack;
                icon.setItemStack(stack);
            }
            updateLabel();
            if (moved) icon.setTransformation(scaleOf(placement.scale() * (lit ? HOVER_FACTOR : 1f)));
        }

        void updateLabel() {
            if (Double.isNaN(placement.labelY())) {
                if (label != null) {
                    label.remove();
                    label = null;
                }
                return;
            }
            List<Component> lines = labelLines();
            float scale = lit ? 0.46f : 0.42f;
            // une TextDisplay grandit vers le haut depuis sa position : on ancre le haut de l'étiquette
            // pour qu'en s'agrandissant (survol) elle descende au lieu de recouvrir l'icône
            double top = placement.labelY() - (lit ? 0.06 : 0);
            double origin = top - lines.size() * LINE * scale - 0.03;
            Location loc = at(placement.x(), origin, DEPTH - (lit ? 0.05 : 0));
            TextColor c = menu.theme().color();
            Color bg = lit ? Color.fromARGB(215, c.red() / 3, c.green() / 3, c.blue() / 3)
                    : Color.fromARGB(110, 8, 8, 12);
            label = text(label, loc, Component.join(JoinConfiguration.newlines(), lines), scale, 150,
                    TextDisplay.TextAlignment.CENTER, bg);
        }

        List<Component> labelLines() {
            List<Component> lines = new ArrayList<>();
            ItemMeta meta = item.getItemMeta();
            Component name = meta != null && meta.displayName() != null ? meta.displayName()
                    : Component.text(item.getType().name());
            lines.add(lit ? Component.text("▶ ", NamedTextColor.YELLOW).append(name) : name);
            List<Component> lore = meta != null && meta.lore() != null ? meta.lore() : List.of();
            if (lit) {
                lines.addAll(lore);
            } else if (!lore.isEmpty() && PlainTextComponentSerializer.plainText().serialize(lore.get(0)).length() <= 12) {
                // au repos : juste le nom (+ une info courte comme « 2 voix »), le détail s'affiche au survol
                lines.add(lore.get(0));
            }
            return lines;
        }

        void highlight(boolean on) {
            lit = on;
            icon.setInterpolationDelay(0);
            icon.setInterpolationDuration(3);
            icon.setTransformation(scaleOf(placement.scale() * (on ? HOVER_FACTOR : 1f)));
            icon.setGlowing(on);
            updateLabel();
        }

        void pulse() {
            icon.setInterpolationDelay(0);
            icon.setInterpolationDuration(2);
            icon.setTransformation(scaleOf(placement.scale() * 1.5f));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (icon != null && icon.isValid()) {
                    icon.setInterpolationDelay(0);
                    icon.setInterpolationDuration(3);
                    icon.setTransformation(scaleOf(placement.scale() * (lit ? HOVER_FACTOR : 1f)));
                }
            }, 3L);
        }

        void remove() {
            if (icon != null) icon.remove();
            if (label != null) label.remove();
        }
    }

    private static Transformation scaleOf(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    private static Color toColor(TextColor c) {
        return Color.fromRGB(c.red(), c.green(), c.blue());
    }
}
