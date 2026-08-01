package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One hop of a flight: where the ghast is going next, and what to call it.
 *
 * <h2>Why the target is resolved every tick rather than captured</h2>
 * A summons flies to a <em>player</em>, and players walk. Capturing the location where they stood when they
 * typed the command would land the ghast in the field they have since left, which is the difference between
 * a taxi and a delivery to your last known address. A leg that follows somebody therefore holds their id
 * and looks them up; a leg to a stop holds the stop, which does not move.
 *
 * <p>An id and not the {@link Player}: a leaked player reference pins that player's chunks — and the world
 * around them — in the heap until the server restarts, and a flight is exactly the kind of object that
 * outlives the tick it was made in.
 *
 * @param label  what a boss bar and {@code /ghast status} call this leg
 * @param fixed  the place it is going, or {@code null} when it follows somebody
 * @param follow the player it follows, or {@code null} when it is going to a fixed place
 */
public record Leg(String label, Destination fixed, UUID follow) {

    public static Leg to(Destination destination) {
        return new Leg(destination.label(), destination, null);
    }

    /** A leg that chases a player — what a summons is. */
    public static Leg following(Player player) {
        return new Leg(player.getName(), null, player.getUniqueId());
    }

    public boolean isFollowing() {
        return follow != null;
    }

    /** Where the ghast is heading right now, or {@code null} when that has stopped being answerable. */
    public Location target() {
        if (follow != null) {
            Player player = Bukkit.getPlayer(follow);
            return player == null || !player.isOnline() ? null : player.getLocation();
        }
        return fixed == null ? null : fixed.location();
    }
}
