package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * One place a ghast can be sent to.
 *
 * <h2>Why the world is a name and not a {@link World}</h2>
 * A stop outlives the server it was made on. Holding the world object would keep an unloaded world in
 * the heap and — worse — a stop in a world that is not loaded <em>right now</em> would have to be thrown
 * away at load time rather than simply being unreachable until that world comes back. A multiverse
 * server that unloads a world for maintenance would otherwise silently lose every stop in it. The same
 * reasoning as the homes module, and the same conclusion.
 *
 * <h2>Why a stop is owned</h2>
 * So that two players can both have a stop called "mine" and neither has to find out that the name was
 * taken. {@link #shared} is how a stop leaves its owner's world and becomes something the rest of the
 * server can fly to — a bus stop rather than a private landing pad.
 *
 * @param name      the key, normalised by {@link Names}
 * @param owner     who made it
 * @param world     the world's name, not the world
 * @param shared    whether everybody may fly to it, and whether it shows in their destination list
 * @param createdAt when it was made, for the lore line and for a stable "oldest first" ordering
 */
public record Stop(String name, UUID owner, String world, double x, double y, double z,
                   boolean shared, long createdAt) {

    public static Stop of(String name, UUID owner, Location location, boolean shared, long createdAt) {
        return new Stop(name, owner,
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), shared, createdAt);
    }

    /** The place itself, or {@code null} when its world is not loaded. */
    public Location location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z);
    }

    public boolean isReachable() {
        return Bukkit.getWorld(world) != null;
    }

    /** "x, y, z", rounded, for a lore line — a stop's coordinates are the useful part of it. */
    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    public Stop withShared(boolean nowShared) {
        return new Stop(name, owner, world, x, y, z, nowShared, createdAt);
    }
}
