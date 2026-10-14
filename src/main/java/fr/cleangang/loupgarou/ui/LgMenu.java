package fr.cleangang.loupgarou.ui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Menu 6 lignes avec fond custom. Le contenu est reconstruit par {@link #render()} à chaque changement. */
public final class LgMenu implements InventoryHolder {
    public static final int[] HEAD_SLOTS;
    public static final int INFO_SLOT = 4;
    public static final int LEFT_BUTTON = 47;
    public static final int MIDDLE_BUTTON = 49;
    public static final int RIGHT_BUTTON = 51;

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

    public LgMenu(MenuTheme theme, Consumer<LgMenu> renderer) {
        this(theme, Glyphs.menuTitle(theme), renderer);
    }

    public LgMenu(MenuTheme theme, net.kyori.adventure.text.Component title, Consumer<LgMenu> renderer) {
        this.theme = theme;
        this.renderer = renderer;
        this.inventory = Bukkit.createInventory(this, 54, title);
        render();
    }

    public void render() {
        inventory.clear();
        actions.clear();
        renderer.accept(this);
    }

    public void set(int slot, ItemStack item, Runnable action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    private boolean lastClickRight;

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
