package de.raindancer.ghastlines;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a claimed ghast loaded, whether or not anybody is near it.
 *
 * <h2>What this is for</h2>
 * An entity does not load the world around it, and an entity in an unloaded chunk does not exist as far as
 * the server is concerned: it is not ticked, it cannot be found by {@code Bukkit.getEntity}, and it cannot be
 * moved. For a claimed ghast that is the difference between a vehicle and a rumour. Calling one from the far
 * side of the map worked only when somebody happened to be standing near where it was parked, and a ghast
 * that flew out of everybody's view had to be searched for chunk by chunk before it could be told anything.
 * The config has promised since the first version that a claimed ghast "can be called from anywhere"; this is
 * what makes that true.
 *
 * <h2>Why it follows them rather than pinning the spot they were claimed at</h2>
 * A parked happy ghast has its own AI back — see {@code FlightService#land} — so it drifts, and a mooring
 * nailed to where it was last written down would eventually be a mooring next to a ghast rather than around
 * one. The chunks are moved to wherever the ghasts actually are, a few times a minute, which is far more
 * often than a drifting animal can leave a three-by-three of them.
 *
 * <h2>What it costs, and the way out</h2>
 * A ticketed chunk is a chunk the server ticks: mobs, redstone, crops, the lot. Nine of them per claimed
 * ghast is a real bill on a server with a lot of ghasts, and {@code max-per-player} is the only thing bounding
 * it. So it is a setting — {@code keep-loaded} — and turning it off returns the old behaviour rather than
 * breaking anything: the ghasts are still claimed, still theirs, and still fly. They are just only there when
 * somebody is.
 *
 * <p>The tickets are taken through the same {@link Tickets} the flights use, and for the same reason it counts
 * rather than simply adding and removing: a flight passing through a moored ghast's chunk must not release it
 * on arrival, and a ghast parking inside another one's mooring must not take a chunk away when it leaves.
 */
final class Standby {

    /** How often the moorings are moved to wherever the ghasts actually are. */
    private static final long REFRESH_TICKS = 100L;

    /** Chunks held around a ghast: its own, and the ring around it, so a drift over an edge changes nothing. */
    private static final int RADIUS = 1;

    private final GhastLines plugin;
    private final Tickets tickets;

    /** ghast → the chunks held for it, and the world they are in. */
    private final Map<UUID, Set<Long>> held = new ConcurrentHashMap<>();
    private final Map<UUID, String> worlds = new ConcurrentHashMap<>();

    private ScheduledTask task;

    Standby(GhastLines plugin, Tickets tickets) {
        this.plugin = plugin;
        this.tickets = tickets;
    }

    void start() {
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                scheduled -> refresh(), 1L, REFRESH_TICKS);
    }

    /** Gives every mooring back. Called from the one exit, like the flights' own tickets. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID ghast : List.copyOf(held.keySet())) {
            release(ghast);
        }
    }

    private void refresh() {
        if (!plugin.options().keepLoaded()) {
            // Switched off while the server was running: let go of everything and keep checking, so
            // switching it back on picks the ghasts up again without a restart.
            for (UUID ghast : List.copyOf(held.keySet())) {
                release(ghast);
            }
            return;
        }
        Set<UUID> claimed = new HashSet<>();
        for (GhastClaim claim : plugin.store().allClaims()) {
            World world = Bukkit.getWorld(claim.world());
            if (world == null) {
                continue;
            }
            // Where it actually is if it is loaded — which, once this is running, it always is — and where
            // it was last written down otherwise. The second case is the first refresh after a restart.
            Entity live = Bukkit.getEntity(claim.ghast());
            Location where = live != null && live.isValid() ? live.getLocation() : claim.lastSeen();
            if (where == null || where.getWorld() == null) {
                continue;
            }
            claimed.add(claim.ghast());
            // A ghast that has flown to another world does not keep its old world's chunks.
            String was = worlds.put(claim.ghast(), where.getWorld().getName());
            if (was != null && !was.equals(where.getWorld().getName())) {
                tickets.releaseAll(Bukkit.getWorld(was), held.get(claim.ghast()));
            }
            tickets.reconcile(where.getWorld(), around(where),
                    held.computeIfAbsent(claim.ghast(), ignored -> ConcurrentHashMap.newKeySet()));
        }
        // Released, killed, or claimed on a server this file has since been carried off.
        for (UUID ghast : List.copyOf(held.keySet())) {
            if (!claimed.contains(ghast)) {
                release(ghast);
            }
        }
    }

    private void release(UUID ghast) {
        Set<Long> chunks = held.remove(ghast);
        String world = worlds.remove(ghast);
        if (chunks != null && world != null) {
            tickets.releaseAll(Bukkit.getWorld(world), chunks);
        }
    }

    private static List<Long> around(Location where) {
        int centreX = where.getBlockX() >> 4;
        int centreZ = where.getBlockZ() >> 4;
        List<Long> keys = new ArrayList<>();
        for (int x = centreX - RADIUS; x <= centreX + RADIUS; x++) {
            for (int z = centreZ - RADIUS; z <= centreZ + RADIUS; z++) {
                keys.add(Tickets.key(x, z));
            }
        }
        return keys;
    }
}
