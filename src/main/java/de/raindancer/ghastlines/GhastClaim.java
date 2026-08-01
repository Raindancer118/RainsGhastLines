package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * One happy ghast that belongs to somebody.
 *
 * <h2>Why the last known position is stored</h2>
 * A summons has to find the ghast, and {@code Bukkit.getEntity(uuid)} only answers for an entity that is
 * loaded. A ghast parked at somebody's base is in an unloaded chunk within minutes of them walking away,
 * which is precisely the situation the whole feature exists for. Remembering where it was last seen turns
 * "cannot find your ghast" into "load that chunk and look there", which is what
 * {@code Claims#locate} does.
 *
 * <h2>Why the name is stored as well as read from the name tag</h2>
 * The displayed name comes from the name tag whenever the entity can be asked — that is the ask, and it
 * means renaming a ghast in game needs no command. But a claim shown in a menu while its ghast is in an
 * unloaded chunk still has to say something, so the name is written down at claim time and whenever the
 * ghast is seen. {@link Claims#displayName} is the one place that decides between them.
 *
 * @param ghast     the entity's UUID; the identity of the claim
 * @param owner     who claimed it
 * @param name      its name as last seen, the fallback for when the entity is not loaded
 * @param world     the world it was last seen in, by name
 * @param claimedAt when it was claimed
 */
public record GhastClaim(UUID ghast, UUID owner, String name, String world, double x, double y, double z,
                         long claimedAt) {

    public static GhastClaim of(UUID ghast, UUID owner, String name, Location where, long claimedAt) {
        return new GhastClaim(ghast, owner, name,
                where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), claimedAt);
    }

    /** Where it was last seen, or {@code null} when that world is not loaded. */
    public Location lastSeen() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z);
    }

    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    public GhastClaim seenAt(String nowName, Location where) {
        if (where == null || where.getWorld() == null) {
            return withName(nowName);
        }
        return new GhastClaim(ghast, owner, nowName == null || nowName.isBlank() ? name : nowName,
                where.getWorld().getName(), where.getX(), where.getY(), where.getZ(), claimedAt);
    }

    public GhastClaim withName(String nowName) {
        if (nowName == null || nowName.isBlank() || nowName.equals(name)) {
            return this;
        }
        return new GhastClaim(ghast, owner, nowName, world, x, y, z, claimedAt);
    }
}
