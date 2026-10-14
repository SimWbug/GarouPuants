package fr.cleangang.loupgarou.ui;

import fr.cleangang.loupgarou.game.Role;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Affiche une carte au centre de l'écran avec une animation de retournement (dos → face). */
public final class CardAnimator {
    private CardAnimator() {}

    public static void reveal(Plugin plugin, Player viewer, Role role, Component subtitle) {
        UUID id = viewer.getUniqueId();
        // {tick, frame} : frames négatives = dos de carte, positives = face
        int[][] steps = {{0, -1}, {14, -2}, {16, -3}, {18, 3}, {20, 2}, {22, 1}};
        for (int[] step : steps) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p == null) return;
                int f = step[1];
                boolean last = f == 1;
                Component card = f < 0 ? Glyphs.cardBack(-f - 1) : Glyphs.cardFront(role, f - 1);
                Title.Times times = last
                        ? Title.Times.times(Ticks.duration(0), Ticks.duration(70), Ticks.duration(15))
                        : Title.Times.times(Ticks.duration(0), Ticks.duration(f == -1 ? 20 : 6), Ticks.duration(0));
                p.showTitle(Title.title(card, last ? subtitle : Component.empty(), times));
                if (f == -2) {
                    p.playSound(Sound.sound(Key.key("item.book.page_turn"), Sound.Source.MASTER, 1f, 0.8f));
                }
                if (last) {
                    p.playSound(Sound.sound(Key.key("block.amethyst_block.chime"), Sound.Source.MASTER, 1f, 1f));
                    p.sendActionBar(Component.text(role.description(), role.color()));
                }
            }, step[0]);
        }
    }

    /** Affiche le dos de carte seul (mise en scène de la distribution). */
    public static void showBack(Player viewer, Component subtitle) {
        viewer.showTitle(Title.title(Glyphs.cardBack(0), subtitle,
                Title.Times.times(Ticks.duration(5), Ticks.duration(40), Ticks.duration(5))));
    }
}
