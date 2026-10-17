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
    /** spectateur → joueur suivi */
    private static final Map<UUID, UUID> WATCHING = new HashMap<>();
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
    private ItemDisplay backdrop;
    private double depth = DEPTH;
    private final Map<Integer, TextDisplay> freeTexts = new HashMap<>();
    private TextDisplay side;
    /** Spectateurs qui regardent par les yeux de ce joueur : ils voient aussi son menu. */
    private final java.util.Set<UUID> watchers = new java.util.HashSet<>();
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
        for (Map.Entry<UUID, UUID> w : WATCHING.entrySet()) {
            if (w.getValue().equals(player.getUniqueId())) holo.watchers.add(w.getKey());
        }
        menu.onRender(holo::rebuild);
        holo.rebuild();
        holo.unfold();
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
        if (holo != null) holo.destroy(true);
    }

    public static void closeAll() {
        for (HoloMenu holo : new ArrayList<>(OPEN.values())) holo.destroy(false);
        OPEN.clear();
    }

    public LgMenu menu() {
        return menu;
    }

    /** Le spectateur voit désormais le menu 3D du joueur qu'il suit (et les suivants qu'il ouvrira). */
    public static void watch(Player spectator, Player target) {
        unwatch(spectator);
        WATCHING.put(spectator.getUniqueId(), target.getUniqueId());
        HoloMenu holo = OPEN.get(target.getUniqueId());
        if (holo != null && !holo.closed) holo.addWatcher(spectator);
    }

    public static void unwatch(Player spectator) {
        UUID target = WATCHING.remove(spectator.getUniqueId());
        if (target == null) return;
        HoloMenu holo = OPEN.get(target);
        if (holo != null) holo.removeWatcher(spectator);
    }

    private void addWatcher(Player p) {
        if (!watchers.add(p.getUniqueId())) return;
        for (org.bukkit.entity.Entity e : entities()) p.showEntity(plugin, e);
    }

    private void removeWatcher(Player p) {
        if (!watchers.remove(p.getUniqueId())) return;
        for (org.bukkit.entity.Entity e : entities()) p.hideEntity(plugin, e);
    }

    private List<org.bukkit.entity.Entity> entities() {
        List<org.bukkit.entity.Entity> out = new ArrayList<>();
        for (Element el : elements.values()) {
            if (el.icon != null) out.add(el.icon);
            if (el.label != null) out.add(el.label);
        }
        for (org.bukkit.entity.Entity e : new org.bukkit.entity.Entity[]{header, hint, backdrop, side}) if (e != null) out.add(e);
        out.addAll(freeTexts.values());
        return out;
    }

    /** Montre une entité du menu à son joueur et aux spectateurs qui le suivent. */
    private void reveal(org.bukkit.entity.Entity e) {
        player.showEntity(plugin, e);
        for (UUID id : watchers) {
            Player w = Bukkit.getPlayer(id);
            if (w != null) w.showEntity(plugin, e);
        }
    }

    // ------------------------------------------------------------------ construction

    private void rebuild() {
        if (closed) return;
        if (!player.isOnline()) {
            close(player);
            return;
        }
        if (menu.custom()) {
            rebuildCustom();
            return;
        }
        boolean sheet = menu.theme() == MenuTheme.SHEET;
        boolean summary = menu.theme() == MenuTheme.SUMMARY;
        Map<Integer, ItemStack> items = menu.items();
        Map<Integer, Placement> wanted = new LinkedHashMap<>();

        double bottomY;
        double width;
        if (summary) {
            // à gauche : les cartes de tous les joueurs (4 par ligne) ; à droite : l'historique
            List<Integer> heads = new ArrayList<>();
            for (int slot : LgMenu.HEAD_SLOTS) if (items.containsKey(slot)) heads.add(slot);
            int perRow = 4;
            for (int i = 0; i < heads.size(); i++) {
                int row = i / perRow;
                int inRow = Math.min(perRow, heads.size() - row * perRow);
                int col = i % perRow;
                double x = -1.45 + (col - (inRow - 1) / 2.0) * 0.62;
                double y = 0.45 - row * 0.85;
                wanted.put(heads.get(i), new Placement(x, y, 0.36f, 0.26, true, y - 0.24));
            }
            int rows = Math.max(1, (heads.size() + perRow - 1) / perRow);
            bottomY = Math.min(-1.25, 0.45 - (rows - 1) * 0.85 - 0.24 - 0.3 - 0.35);
            width = 6.0;
        } else if (sheet) {
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
        if (summary) {
            header = text(header, at(0, 0.88, DEPTH), headerText, 0.62f, 300, TextDisplay.TextAlignment.CENTER,
                    Color.fromARGB(0, 0, 0, 0));
            ItemStack hist = items.get(LgMenu.HISTORY_SLOT);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("DÉROULÉ DE LA PARTIE", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            if (hist != null && hist.getItemMeta() != null && hist.getItemMeta().lore() != null) lines.addAll(hist.getItemMeta().lore());
            double topY = 0.62;
            double origin = topY - lines.size() * LINE * 0.4 - 0.03;
            side = text(side, at(1.5, origin, DEPTH), Component.join(JoinConfiguration.newlines(), lines), 0.4f, 260,
                    TextDisplay.TextAlignment.LEFT, Color.fromARGB(90, 0, 0, 0));
            bottomY = Math.min(bottomY, origin - 0.4);
        } else if (sheet) {
            header = text(header, at(0.95, -0.7, DEPTH), headerText, 0.52f, 150, TextDisplay.TextAlignment.LEFT,
                    Color.fromARGB(0, 0, 0, 0));
        } else {
            header = text(header, at(0, 0.68, DEPTH), headerText, 0.6f, 220, TextDisplay.TextAlignment.CENTER,
                    Color.fromARGB(0, 0, 0, 0));
        }
        Component hintText = summary
                ? Component.text("Vise une carte pour les détails · accroupis-toi pour fermer", TextColor.color(0xBFD9FF))
                : Component.text("Vise avec le viseur · clic pour choisir · accroupis-toi pour fermer", TextColor.color(0xBFD9FF));
        hint = text(hint, at(0, bottomY + 0.08, DEPTH), hintText, 0.38f, 400, TextDisplay.TextAlignment.CENTER,
                Color.fromARGB(0, 0, 0, 0));

        placePanel(width, top_(sheet, summary), bottomY - 0.1);
    }

    private static double top_(boolean sheet, boolean summary) {
        return sheet ? 1.5 : summary ? 1.55 : 1.45;
    }

    /** Panneau de fond : cadre bleu du resource pack, choisi selon les proportions du menu. */
    private void placePanel(double width, double top, double panelBottom) {
        // le panneau est 0,3 bloc plus loin : on l'agrandit d'autant pour qu'il couvre bien tout le menu
        double k = (depth + 0.3) / depth;
        double height = top - panelBottom;
        int ratio = (int) Math.max(10, Math.min(30, Math.round(width / height * 10)));
        ItemStack panelItem = new ItemStack(org.bukkit.Material.PAPER);
        ItemMeta pm = panelItem.getItemMeta();
        pm.setItemModel(new org.bukkit.NamespacedKey("lgt", "panel_" + ratio));
        panelItem.setItemMeta(pm);
        Location panelAt = at(0, (top + panelBottom) / 2 * k, depth + 0.3);
        if (backdrop == null || !backdrop.isValid()) {
            backdrop = panelAt.getWorld().spawn(panelAt, ItemDisplay.class, e -> {
                prepare(e);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            });
            reveal(backdrop);
        } else if (backdrop.getLocation().distanceSquared(panelAt) > 0.0001) {
            backdrop.teleport(panelAt);
        }
        backdrop.setItemStack(panelItem);
        backdrop.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f((float) (width * k), (float) (height * k), 0.05f), new Quaternionf()));
    }

    /** Menu en mise en page libre : chaque objet et chaque texte à la position donnée par le menu. */
    private void rebuildCustom() {
        LgMenu.Panel panel = menu.panel();
        depth = panel.depth();
        Map<Integer, ItemStack> items = menu.items();
        Map<Integer, Placement> wanted = new LinkedHashMap<>();
        for (Map.Entry<Integer, LgMenu.Layout> e : menu.layouts().entrySet()) {
            LgMenu.Layout l = e.getValue();
            wanted.put(e.getKey(), new Placement(l.x(), l.y(), l.sx(), l.radius(), menu.clickable(e.getKey()), Double.NaN,
                    l.sy(), l.z(), l.flat(), l.leftAnchored()));
        }
        elements.entrySet().removeIf(e -> {
            if (wanted.containsKey(e.getKey())) return false;
            e.getValue().remove();
            if (hovered == e.getValue()) hovered = null;
            return true;
        });
        for (Map.Entry<Integer, Placement> e : wanted.entrySet()) {
            ItemStack item = items.get(e.getKey());
            Element el = elements.get(e.getKey());
            if (el == null) {
                el = new Element(e.getKey(), e.getValue());
                elements.put(e.getKey(), el);
                el.spawn(item);
            } else {
                el.update(item, e.getValue());
            }
        }
        // textes
        Map<Integer, LgMenu.Text> texts = menu.texts();
        freeTexts.entrySet().removeIf(e -> {
            if (texts.containsKey(e.getKey())) return false;
            e.getValue().remove();
            return true;
        });
        for (Map.Entry<Integer, LgMenu.Text> e : texts.entrySet()) {
            LgMenu.Text t = e.getValue();
            int lines = 1 + PlainTextComponentSerializer.plainText().serialize(t.text()).split("\n", -1).length - 1;
            double origin = t.y() - lines * LINE * t.scale() / 2;
            TextDisplay td = text(freeTexts.get(e.getKey()), at(t.x(), origin, depth + t.z()), t.text(), t.scale(),
                    t.lineWidth(), TextDisplay.TextAlignment.CENTER, Color.fromARGB(0, 0, 0, 0));
            freeTexts.put(e.getKey(), td);
        }
        placePanel(panel.width(), panel.top(), panel.bottom());
        updateHint();
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
            double score;
            if (el.placement.flat()) {
                // rectangle : point visé dans le plan du menu
                double along = dir.dot(forward);
                if (along <= 0.05) continue;
                double t = to.dot(forward) / along;
                Vector hit = origin.clone().add(dir.clone().multiply(t)).subtract(el.center.toVector());
                double hx = Math.abs(hit.dot(right)) / (el.placement.scale() / 2);
                double hy = Math.abs(hit.getY()) / (el.placement.sy() / 2);
                score = Math.max(hx, hy) * 0.999;
            } else {
                score = dir.angle(to) / Math.atan(el.placement.radius / dist);
            }
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
            updateHint();
        }
        if (tickCounter % 20 == 0) updateTimer();
    }

    /** Ligne d'aide du tableau : décrit l'élément visé (« Clique pour voter contre Pierre »). */
    private void updateHint() {
        TextDisplay td = freeTexts.get(BoardLayout.HINT_TEXT);
        if (td == null || !td.isValid()) return;
        String h = hovered == null ? null : menu.hint(hovered.slot);
        td.text(h == null ? BoardLayout.defaultHint()
                : Component.text("▶ " + h, NamedTextColor.YELLOW).font(BoardLayout.ARCHIVO));
    }

    /** Sablier : la jauge se vide en continu (interpolation sur 1 s), rouge dans les 10 dernières secondes. */
    private void updateTimer() {
        Element fill = elements.get(BoardLayout.TIMER_FILL);
        if (fill == null) {
            // menu ouvert juste avant le début de la phase : on le redessine pour faire apparaître le sablier
            if (menu.custom() && menu.timeLeft() >= 0 && menu.layouts().containsKey(9100)) menu.render();
            return;
        }
        if (!menu.custom()) return;
        int left = menu.timeLeft();
        if (left < 0) return;
        int total = menu.timeTotal();
        double frac = Math.max(0, Math.min(1, (left - 1) / (double) total));
        float w = (float) Math.max(0.001, menu.timerWidth() * frac);
        fill.placement = new Placement(fill.placement.x(), fill.placement.y(), w, 0, false, Double.NaN,
                fill.placement.sy(), fill.placement.z(), true, true);
        fill.icon.setInterpolationDelay(0);
        fill.icon.setInterpolationDuration(20);
        fill.icon.setTransformation(fill.size(1f));
        if (left <= 10) fill.icon.setItemStack(BoardLayout.flat("timer_red"));
        TextDisplay td = freeTexts.get(BoardLayout.TIMER_TEXT);
        if (td != null && td.isValid()) td.text(BoardLayout.timerText(Math.max(0, left)));
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

    private void destroy(boolean animate) {
        if (closed) return;
        closed = true;
        menu.onRender(null);
        List<org.bukkit.entity.Entity> all = entities();
        elements.clear();
        freeTexts.clear();
        header = hint = null;
        backdrop = null;
        side = null;
        if (!animate || plugin == null || !plugin.isEnabled()) {
            for (org.bukkit.entity.Entity e : all) e.remove();
            return;
        }
        // le panneau se replie vers le centre, le contenu rétrécit, puis tout disparaît
        ItemDisplay panel = null;
        for (org.bukkit.entity.Entity e : all) {
            if (!(e instanceof Display d) || !d.isValid()) continue;
            Transformation t = d.getTransformation();
            boolean isPanel = d instanceof ItemDisplay id && id.getItemStack() != null && id.getItemStack().hasItemMeta()
                    && id.getItemStack().getItemMeta().hasItemModel()
                    && id.getItemStack().getItemMeta().getItemModel().getKey().startsWith("panel_");
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(isPanel ? 5 : 3);
            d.setTransformation(shrink(t, isPanel));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.Entity e : all) e.remove();
        }, 6L);
    }

    /** Ouverture : le panneau se déplie du centre vers les bords, puis le contenu apparaît. */
    private void unfold() {
        Map<Display, Transformation> targets = new HashMap<>();
        for (org.bukkit.entity.Entity e : entities()) {
            if (!(e instanceof Display d)) continue;
            Transformation t = d.getTransformation();
            targets.put(d, t);
            d.setTransformation(shrink(t, d == backdrop));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (closed) return;
            for (Map.Entry<Display, Transformation> en : targets.entrySet()) {
                Display d = en.getKey();
                if (!d.isValid()) continue;
                boolean isPanel = d == backdrop;
                // si l'élément a changé entre-temps (rafraîchissement), on garde sa nouvelle taille
                if (!d.getTransformation().equals(shrink(en.getValue(), isPanel))) continue;
                d.setInterpolationDelay(isPanel ? 0 : 3);
                d.setInterpolationDuration(isPanel ? 6 : 4);
                d.setTransformation(en.getValue());
            }
        }, 2L);
    }

    private static Transformation shrink(Transformation t, boolean panel) {
        Vector3f sc = new Vector3f(t.getScale());
        Vector3f tr = new Vector3f(t.getTranslation());
        if (panel) {
            sc.x *= 0.02f;
        } else {
            sc.mul(0.01f);
            tr.mul(0.01f);
        }
        return new Transformation(tr, new Quaternionf(t.getLeftRotation()), sc, new Quaternionf(t.getRightRotation()));
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
            reveal(td);
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

    private record Placement(double x, double y, float scale, double radius, boolean clickable, double labelY,
                             float sy, double z, boolean flat, boolean left) {
        Placement(double x, double y, float scale, double radius, boolean clickable, double labelY) {
            this(x, y, scale, radius, clickable, labelY, scale, 0, false, false);
        }

        float hover() {
            return flat ? (scale > 1.8f ? 1.025f : 1.07f) : HOVER_FACTOR;
        }
    }

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
            this.center = at(placement.x(), placement.y(), depth + placement.z());
        }

        Transformation size(float factor) {
            Transformation t = scaleOf(placement.scale() * factor, placement.sy() * factor);
            if (placement.left()) t.getTranslation().set(placement.scale() * factor / 2, 0, 0);
            return t;
        }

        void spawn(ItemStack stack) {
            this.item = stack;
            icon = center.getWorld().spawn(center, ItemDisplay.class, e -> {
                prepare(e);
                e.setItemStack(stack);
                e.setItemDisplayTransform(placement.flat() ? ItemDisplay.ItemDisplayTransform.NONE
                        : ItemDisplay.ItemDisplayTransform.GUI);
                e.setTransformation(size(1f));
                e.setGlowColorOverride(placement.flat() ? Color.WHITE : toColor(menu.theme().color()));
            });
            reveal(icon);
            updateLabel();
        }

        void update(ItemStack stack, Placement p) {
            boolean moved = p.x() != placement.x() || p.y() != placement.y() || p.z() != placement.z();
            boolean resized = p.scale() != placement.scale() || p.sy() != placement.sy();
            this.placement = p;
            if (moved) {
                center = at(p.x(), p.y(), depth + p.z());
                icon.teleport(center);
            }
            if (!stack.equals(item)) {
                item = stack;
                icon.setItemStack(stack);
            }
            updateLabel();
            if (moved || resized) {
                icon.setInterpolationDelay(0);
                icon.setInterpolationDuration(4);
                icon.setTransformation(size(lit ? placement.hover() : 1f));
            }
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
            Location loc = at(placement.x(), origin, depth - (lit ? 0.05 : 0));
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
            icon.setTransformation(size(on ? placement.hover() : 1f));
            icon.setGlowing(on);
            updateLabel();
        }

        void pulse() {
            icon.setInterpolationDelay(0);
            icon.setInterpolationDuration(2);
            icon.setTransformation(size(placement.flat() ? 1.15f : 1.5f));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (icon != null && icon.isValid()) {
                    icon.setInterpolationDelay(0);
                    icon.setInterpolationDuration(3);
                    icon.setTransformation(size(lit ? placement.hover() : 1f));
                }
            }, 3L);
        }

        void remove() {
            if (icon != null) icon.remove();
            if (label != null) label.remove();
        }
    }

    private static Transformation scaleOf(float s) {
        return scaleOf(s, s);
    }

    private static Transformation scaleOf(float sx, float sy) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sx, sy, Math.min(sx, sy)), new Quaternionf());
    }

    private static Color toColor(TextColor c) {
        return Color.fromRGB(c.red(), c.green(), c.blue());
    }
}
