package fr.cleangang.loupgarou.ui;

/** L'ordre des valeurs doit correspondre à THEMES dans tools/generate_pack.py. */
public enum MenuTheme {
    VOTE, WOLVES, SEER, WITCH, CUPID, HUNTER, GUARD, ADMIN;

    public char glyph() {
        return (char) (0xE100 + ordinal());
    }
}
