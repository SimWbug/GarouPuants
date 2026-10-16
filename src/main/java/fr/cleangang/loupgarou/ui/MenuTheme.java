package fr.cleangang.loupgarou.ui;

import net.kyori.adventure.text.format.TextColor;

/**
 * L'ordre VOTE..ADMIN doit correspondre à THEMES dans tools/generate_pack.py (fonds d'inventaire).
 * SHEET (fiche de rôle) n'existe qu'en 3D.
 */
public enum MenuTheme {
    VOTE("Vote du village", 0xE0B060),
    WOLVES("La meute choisit", 0xE04A3A),
    SEER("Vision de la Voyante", 0xB57EDC),
    WITCH("Potions de la Sorcière", 0x3DD17E),
    CUPID("Flèche de Cupidon", 0xFF7EB6),
    HUNTER("Dernier tir du Chasseur", 0xF0913A),
    GUARD("Protection du Salvateur", 0x4AA8F0),
    ADMIN("Composition de la partie", 0xD2AA50),
    SHEET("Ta carte", 0xF0D696);

    private final String title;
    private final TextColor color;

    MenuTheme(String title, int rgb) {
        this.title = title;
        this.color = TextColor.color(rgb);
    }

    public String title() {
        return title;
    }

    public TextColor color() {
        return color;
    }

    public char glyph() {
        return (char) (0xE100 + ordinal());
    }
}
