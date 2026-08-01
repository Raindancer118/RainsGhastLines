package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

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

    /**
     * How far in front of a player a summoned ghast comes to rest.
     *
     * <h2>Why not on them</h2>
     * Because a happy ghast is four blocks across, and one that descends onto the exact spot somebody is
     * standing on arrives in their face — it fills the screen, and getting on means backing out from under
     * it first. Five blocks along the way they are looking puts it where they can see all of it and walk
     * into the harness. Their own eye height is kept, so on a cliff edge it holds station rather than
     * sinking to whatever is below.
     */
    public static final double LANDING_OFFSET = 5.0;

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
            return player == null || !player.isOnline() ? null : inFrontOf(player);
        }
        return fixed == null ? null : fixed.location();
    }

    /** A point {@link #LANDING_OFFSET} blocks along the way the player is looking, at their own height. */
    static Location inFrontOf(Player player) {
        Location where = player.getLocation();
        Vector facing = where.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) {
            // Looking straight up or down: any direction will do, and one of them is repeatable.
            facing = new Vector(0, 0, 1);
        }
        return where.clone().add(facing.normalize().multiply(LANDING_OFFSET));
    }
}
