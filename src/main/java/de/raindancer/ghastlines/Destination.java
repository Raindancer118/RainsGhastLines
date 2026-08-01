package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Somewhere a player can ask to be flown, whatever it happens to be underneath.
 *
 * <p>A stop of their own, a stop somebody else has shared, or one of their homes: three different things
 * with three different owners, and the flight engine has no business caring which. They arrive here as
 * one shape, with the token a player types in {@link #key} and the words a menu shows in {@link #label}.
 *
 * @param key   what {@code /ghast send <this>} accepts — see {@link Destinations} for the forms
 * @param label how it is written out for a person
 * @param kind  where it came from: "stop", a player's name, or "home"
 * @param world the world's name, not the world; see {@link Stop}
 */
public record Destination(String key, String label, String kind, String world,
                          double x, double y, double z) {

    public static Destination of(String key, String label, String kind, Location where) {
        return new Destination(key, label, kind,
                where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ());
    }

    public static Destination fromStop(Stop stop, String key, String kind) {
        return new Destination(key, stop.name(), kind, stop.world(), stop.x(), stop.y(), stop.z());
    }

    /** The place itself, or {@code null} when its world is not loaded. */
    public Location location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z);
    }

    public boolean isReachable() {
        return Bukkit.getWorld(world) != null;
    }

    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    /** The icon a menu shows for it — the one visible difference between the three kinds. */
    public Material icon() {
        return switch (kind) {
            case Destinations.KIND_HOME -> Material.RED_BED;
            case Destinations.KIND_OWN -> Material.LODESTONE;
            default -> Material.BELL;
        };
    }
}
