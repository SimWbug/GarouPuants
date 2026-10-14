package fr.cleangang.loupgarou.ui;

import fr.cleangang.loupgarou.game.Role;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;

/** Caractères de la police custom du resource pack (cartes au centre de l'écran + fonds de menus). */
public final class Glyphs {
    public static final Key CARD_FONT = Key.key("lgt", "cards");
    public static final Key GUI_FONT = Key.key("lgt", "gui");
    private static final String NEG_8 = "";

    private Glyphs() {}

    /** frame : 0 = pleine largeur, 1 = 62 %, 2 = 25 % (animation de retournement). */
    public static Component cardBack(int frame) {
        return glyph((char) (0xE000 + frame), CARD_FONT);
    }

    public static Component cardFront(Role role, int frame) {
        return glyph((char) (0xE010 + role.ordinal() * 3 + frame), CARD_FONT);
    }

    /** Titre de menu : décalage de -8 px puis l'image de fond 176x222. */
    public static Component menuTitle(MenuTheme theme) {
        return Component.text()
                .append(Component.text(NEG_8, Style.style().font(GUI_FONT).build()))
                .append(glyph(theme.glyph(), GUI_FONT))
                .build();
    }

    /** Fiche explicative d'un rôle (fond de menu). */
    public static Component roleSheetTitle(Role role) {
        return Component.text()
                .append(Component.text(NEG_8, Style.style().font(GUI_FONT).build()))
                .append(glyph((char) (0xE120 + role.ordinal()), GUI_FONT))
                .build();
    }

    private static Component glyph(char c, Key font) {
        // Blanc = pas de teinte ; pas d'ombre pour ne pas salir l'image
        return Component.text(String.valueOf(c), Style.style()
                .font(font)
                .color(NamedTextColor.WHITE)
                .shadowColor(ShadowColor.none())
                .build());
    }
}
