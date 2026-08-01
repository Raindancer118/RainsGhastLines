package de.raindancer.ghastlines;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the chunks a flight is passing through loaded, and — the part that matters — stops keeping them.
 *
 * <h2>Why a flight needs chunk tickets at all</h2>
 * An entity does not load the world around it. Fly a ghast a hundred blocks past the edge of what a player
 * has loaded and it stops being ticked: no velocity is applied, the flight freezes in mid-air, and it comes
 * back to life whenever somebody happens to walk near it. A plugin chunk ticket is the supported way to say
 * "keep this loaded, I am using it", and it is the only way a long flight works at all.
 *
 * <h2>Why they are counted rather than simply added and removed</h2>
 * Two ghasts on the same line pass through the same chunks. If each removed its own tickets on arrival, the
 * first to land would unload the chunks the second is still flying through. So a chunk is ticketed when the
 * first flight wants it and released when the last one is done with it.
 *
 * <p>A leaked ticket is a chunk loaded for the rest of the server's life, which is why
 * {@link #releaseAll} exists and why {@link FlightService} calls it on every ending a flight can have —
 * arrival, cancellation, the ghast dying, and the plugin shutting down.
 *
 * <h2>Why the ticket calls go through the global region</h2>
 * Chunk tickets are server-wide bookkeeping, and a flight adds them for chunks it has not reached yet —
 * which on Folia belong to a region other than the one running the flight. The global region owns anything
 * that is not one region's business, so that is where these run. One tick of latency is free here: the
 * tickets are taken out two chunks ahead of the ghast, which is several seconds of flying.
 */
final class Tickets {

    private final Plugin plugin;

    /** world name → chunk key → how many flights want it. */
    private final Map<String, Map<Long, Integer>> wanted = new ConcurrentHashMap<>();

    Tickets(Plugin plugin) {
        this.plugin = plugin;
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    /**
     * Brings a holder's set of chunks in line with what it now wants, taking and giving back tickets for
     * the difference only.
     *
     * @param held the holder's own set, updated in place to match {@code want}
     */
    void reconcile(World world, Collection<Long> want, Collection<Long> held) {
        List<Long> toAcquire = new ArrayList<>();
        for (Long key : want) {
            if (!held.contains(key)) {
                toAcquire.add(key);
            }
        }
        List<Long> toRelease = new ArrayList<>();
        for (Long key : held) {
            if (!want.contains(key)) {
                toRelease.add(key);
            }
        }
        if (toAcquire.isEmpty() && toRelease.isEmpty()) {
            return;
        }
        held.removeAll(toRelease);
        held.addAll(toAcquire);
        apply(world, toAcquire, toRelease);
    }

    /** Gives back everything a holder had. Called on every way a flight can end. */
    void releaseAll(World world, Collection<Long> held) {
        if (world == null || held.isEmpty()) {
            held.clear();
            return;
        }
        List<Long> going = new ArrayList<>(held);
        held.clear();
        apply(world, List.of(), going);
    }

    private void apply(World world, List<Long> acquiring, List<Long> releasing) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Map<Long, Integer> counts = wanted.computeIfAbsent(world.getName(),
                    ignored -> new ConcurrentHashMap<>());
            for (Long key : acquiring) {
                if (counts.merge(key, 1, Integer::sum) == 1) {
                    world.addPluginChunkTicket(chunkX(key), chunkZ(key), plugin);
                }
            }
            for (Long key : releasing) {
                Integer left = counts.merge(key, -1, (was, delta) -> was + delta <= 0 ? null : was + delta);
                if (left == null) {
                    world.removePluginChunkTicket(chunkX(key), chunkZ(key), plugin);
                }
            }
        });
    }
}
