package fr.cleangang.loupgarou.game;

import net.kyori.adventure.text.format.TextColor;

/** L'ordre des valeurs doit correspondre à ROLES dans tools/generate_pack.py (glyphes). */
public enum Role {
    LOUP_GAROU("loup_garou", "Loup-Garou", Team.WOLVES, 0xE04A3A,
            "Chaque nuit, dévore un villageois avec la meute. Ne te fais pas démasquer."),
    VILLAGEOIS("villageois", "Simple Villageois", Team.VILLAGE, 0xD4A35A,
            "Aucun pouvoir, mais ta voix compte : démasque les loups et vote."),
    VOYANTE("voyante", "Voyante", Team.VILLAGE, 0xB57EDC,
            "Chaque nuit, découvre la vraie carte d'un joueur."),
    SORCIERE("sorciere", "Sorcière", Team.VILLAGE, 0x3DD17E,
            "Une potion de vie et une potion de mort, utilisables une seule fois chacune."),
    CHASSEUR("chasseur", "Chasseur", Team.VILLAGE, 0xF0913A,
            "Si tu meurs, tu tires une dernière balle sur le joueur de ton choix."),
    CUPIDON("cupidon", "Cupidon", Team.VILLAGE, 0xFF7EB6,
            "La première nuit, lie deux amoureux : si l'un meurt, l'autre meurt de chagrin."),
    SALVATEUR("salvateur", "Salvateur", Team.VILLAGE, 0x4AA8F0,
            "Chaque nuit, protège un joueur des loups (pas deux fois de suite le même).");

    public enum Team { VILLAGE, WOLVES }

    private final String id;
    private final String displayName;
    private final Team team;
    private final TextColor color;
    private final String description;

    Role(String id, String displayName, Team team, int rgb, String description) {
        this.id = id;
        this.displayName = displayName;
        this.team = team;
        this.color = TextColor.color(rgb);
        this.description = description;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Team team() { return team; }
    public TextColor color() { return color; }
    public String description() { return description; }

    public String goal() {
        return switch (this) {
            case LOUP_GAROU -> "Éliminer les villageois jusqu'à être aussi nombreux qu'eux.";
            case CUPIDON -> "Aider le village. Les amoureux gagnent s'ils sont les deux derniers.";
            default -> "Démasquer et éliminer tous les Loups-Garous.";
        };
    }
}
