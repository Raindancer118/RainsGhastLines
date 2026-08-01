package de.raindancer.ghastlines;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every stop, every route and every claimed ghast — in memory, and on disk one file behind.
 *
 * <h2>Why all three live in one class and one file</h2>
 * They are written by the same commands in the same breath: claiming a ghast at a stop touches two of
 * them, and deleting a stop has to be visible to the routes that name it. Three stores would be three
 * copies of the writer machinery below and three files that can disagree about the moment a change
 * happened. The homes module gets away with one map because it only has one kind of thing.
 *
 * <h2>Why the writes go through one thread of this class's own</h2>
 * A stop is made from whichever region thread the player is on — on Folia that is genuinely several
 * threads — and read again by the GUI, by tab completion and by a flight in progress. Saving on the
 * calling thread would put a file write in the middle of a tick; saving with the server's async scheduler
 * would let two saves interleave and produce a file that is half of each. A single-threaded executor
 * owned here gives both properties for free: writes never block a tick, and they happen in the order the
 * changes did. Each save is handed a finished snapshot, so what lands on disk is the state at the moment
 * of the change even if two changes are queued behind it.
 *
 * <h2>Why the file is replaced rather than written in place</h2>
 * The whole server's network is one file. Writing over it means a crash mid-write costs everybody
 * everything; writing a temporary file and moving it into place means the worst case is that the last
 * change is missing.
 */
public final class TransitStore {

    private final Path file;
    private final Logger logger;

    /** owner → name → stop. Both levels concurrent: read from region threads, written from them too. */
    private final Map<UUID, Map<String, Stop>> stops = new ConcurrentHashMap<>();

    /** owner → name → route. */
    private final Map<UUID, Map<String, Route>> routes = new ConcurrentHashMap<>();

    /** The claims, keyed by the ghast — the identity a summons and a click both arrive with. */
    private final Map<UUID, GhastClaim> claims = new ConcurrentHashMap<>();

    /** Last known name per player, written to the file so an admin reading it sees who is who. */
    private final Map<UUID, String> lastKnownNames = new ConcurrentHashMap<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RainsGhastLines-save");
        thread.setDaemon(true);
        return thread;
    });

    public TransitStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ stops

    /** This player's stops, sorted so the list is the same every time it is shown. */
    public List<Stop> stopsOf(UUID owner) {
        return sorted(stops.get(owner), Comparator.comparing(Stop::name));
    }

    public Optional<Stop> findStop(UUID owner, String name) {
        String key = Names.normalise(name);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Stop> mine = stops.get(owner);
        return Optional.ofNullable(mine == null ? null : mine.get(key));
    }

    public int stopCount(UUID owner) {
        Map<String, Stop> mine = stops.get(owner);
        return mine == null ? 0 : mine.size();
    }

    public List<String> stopNames(UUID owner) {
        return stopsOf(owner).stream().map(Stop::name).toList();
    }

    /** Every stop anybody has shared — what somebody else's destination list is made of. */
    public List<Stop> sharedStops() {
        List<Stop> shared = new ArrayList<>();
        stops.values().forEach(mine -> mine.values().stream().filter(Stop::shared).forEach(shared::add));
        shared.sort(Comparator.comparing(Stop::name).thenComparing(stop -> stop.owner().toString()));
        return List.copyOf(shared);
    }

    public void putStop(UUID owner, String ownerName, Stop stop) {
        stops.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>()).put(stop.name(), stop);
        rememberName(owner, ownerName);
        persist();
    }

    public Optional<Stop> removeStop(UUID owner, String name) {
        String key = Names.normalise(name);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Stop> mine = stops.get(owner);
        Stop removed = mine == null ? null : mine.remove(key);
        if (removed == null) {
            return Optional.empty();
        }
        if (mine.isEmpty()) {
            stops.remove(owner);
        }
        persist();
        return Optional.of(removed);
    }

    // ------------------------------------------------------------------ routes

    public List<Route> routesOf(UUID owner) {
        return sorted(routes.get(owner), Comparator.comparing(Route::name));
    }

    public Optional<Route> findRoute(UUID owner, String name) {
        String key = Names.normalise(name);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Route> mine = routes.get(owner);
        return Optional.ofNullable(mine == null ? null : mine.get(key));
    }

    public int routeCount(UUID owner) {
        Map<String, Route> mine = routes.get(owner);
        return mine == null ? 0 : mine.size();
    }

    public List<String> routeNames(UUID owner) {
        return routesOf(owner).stream().map(Route::name).toList();
    }

    /** Every route anybody has published — the timetable the rest of the server can read. */
    public List<Route> sharedRoutes() {
        List<Route> shared = new ArrayList<>();
        routes.values().forEach(mine -> mine.values().stream().filter(Route::shared).forEach(shared::add));
        shared.sort(Comparator.comparing(Route::name).thenComparing(route -> route.owner().toString()));
        return List.copyOf(shared);
    }

    public void putRoute(UUID owner, String ownerName, Route route) {
        routes.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>()).put(route.name(), route);
        rememberName(owner, ownerName);
        persist();
    }

    public Optional<Route> removeRoute(UUID owner, String name) {
        String key = Names.normalise(name);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Route> mine = routes.get(owner);
        Route removed = mine == null ? null : mine.remove(key);
        if (removed == null) {
            return Optional.empty();
        }
        if (mine.isEmpty()) {
            routes.remove(owner);
        }
        persist();
        return Optional.of(removed);
    }

    // ------------------------------------------------------------------ claimed ghasts

    public Optional<GhastClaim> claimOf(UUID ghast) {
        return Optional.ofNullable(claims.get(ghast));
    }

    public List<GhastClaim> claimsOf(UUID owner) {
        List<GhastClaim> mine = new ArrayList<>();
        claims.values().stream().filter(claim -> claim.owner().equals(owner)).forEach(mine::add);
        mine.sort(Comparator.comparingLong(GhastClaim::claimedAt));
        return List.copyOf(mine);
    }

    public int claimCount(UUID owner) {
        return (int) claims.values().stream().filter(claim -> claim.owner().equals(owner)).count();
    }

    public List<GhastClaim> allClaims() {
        return List.copyOf(claims.values());
    }

    public void putClaim(GhastClaim claim, String ownerName) {
        claims.put(claim.ghast(), claim);
        rememberName(claim.owner(), ownerName);
        persist();
    }

    public Optional<GhastClaim> removeClaim(UUID ghast) {
        GhastClaim removed = claims.remove(ghast);
        if (removed == null) {
            return Optional.empty();
        }
        persist();
        return Optional.of(removed);
    }

    /**
     * Updates a claim in place without touching the rest of the file's shape.
     * <p>
     * Called every time a ghast is seen, which is often, so it does nothing — and queues no save — when
     * the claim has not actually changed.
     */
    public void refreshClaim(GhastClaim updated) {
        GhastClaim existing = claims.get(updated.ghast());
        if (updated.equals(existing)) {
            return;
        }
        claims.put(updated.ghast(), updated);
        persist();
    }

    public int totalStops() {
        return stops.values().stream().mapToInt(Map::size).sum();
    }

    public int totalRoutes() {
        return routes.values().stream().mapToInt(Map::size).sum();
    }

    public int totalClaims() {
        return claims.size();
    }

    /** The name this player last used, for a menu that has to describe somebody else's shared stop. */
    public String nameOf(UUID player) {
        return lastKnownNames.getOrDefault(player, "");
    }

    // ------------------------------------------------------------------ disk

    public void load() {
        stops.clear();
        routes.clear();
        claims.clear();
        lastKnownNames.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        int skipped = loadPlayers(yaml.getConfigurationSection("players"))
                + loadGhasts(yaml.getConfigurationSection("ghasts"));
        if (skipped > 0) {
            logger.warning(skipped + " entr(y/ies) in " + file.getFileName()
                    + " could not be read and were left alone; everything else loaded.");
        }
    }

    private int loadPlayers(ConfigurationSection players) {
        if (players == null) {
            return 0;
        }
        int skipped = 0;
        for (String rawId : players.getKeys(false)) {
            UUID owner = parseUuid(rawId);
            ConfigurationSection entry = players.getConfigurationSection(rawId);
            if (owner == null || entry == null) {
                skipped++;
                continue;
            }
            rememberName(owner, entry.getString("name", ""));
            skipped += loadStops(owner, entry.getConfigurationSection("stops"));
            skipped += loadRoutes(owner, entry.getConfigurationSection("routes"));
        }
        return skipped;
    }

    private int loadStops(UUID owner, ConfigurationSection section) {
        if (section == null) {
            return 0;
        }
        int skipped = 0;
        Map<String, Stop> mine = new ConcurrentHashMap<>();
        for (String rawName : section.getKeys(false)) {
            ConfigurationSection where = section.getConfigurationSection(rawName);
            String name = Names.normalise(rawName);
            if (where == null || name == null) {
                skipped++;
                continue;
            }
            mine.put(name, new Stop(name, owner, where.getString("world", ""),
                    where.getDouble("x"), where.getDouble("y"), where.getDouble("z"),
                    where.getBoolean("shared", false), where.getLong("created")));
        }
        if (!mine.isEmpty()) {
            stops.put(owner, mine);
        }
        return skipped;
    }

    private int loadRoutes(UUID owner, ConfigurationSection section) {
        if (section == null) {
            return 0;
        }
        int skipped = 0;
        Map<String, Route> mine = new ConcurrentHashMap<>();
        for (String rawName : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(rawName);
            String name = Names.normalise(rawName);
            if (entry == null || name == null) {
                skipped++;
                continue;
            }
            // Stop names are normalised on the way back in as well: a hand-edited file is the one place
            // a route can name a stop in a form no lookup would ever match.
            List<String> called = new ArrayList<>();
            for (String raw : entry.getStringList("stops")) {
                String stop = Names.normalise(raw);
                if (stop == null) {
                    skipped++;
                    continue;
                }
                called.add(stop);
            }
            mine.put(name, new Route(name, owner, called, entry.getBoolean("loop", false),
                    entry.getBoolean("shared", false), entry.getLong("created")));
        }
        if (!mine.isEmpty()) {
            routes.put(owner, mine);
        }
        return skipped;
    }

    private int loadGhasts(ConfigurationSection section) {
        if (section == null) {
            return 0;
        }
        int skipped = 0;
        for (String rawId : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(rawId);
            UUID ghast = parseUuid(rawId);
            UUID owner = entry == null ? null : parseUuid(entry.getString("owner", ""));
            if (ghast == null || owner == null) {
                skipped++;
                continue;
            }
            claims.put(ghast, new GhastClaim(ghast, owner, entry.getString("name", ""),
                    entry.getString("world", ""), entry.getDouble("x"), entry.getDouble("y"),
                    entry.getDouble("z"), entry.getLong("claimed")));
        }
        return skipped;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private void rememberName(UUID player, String name) {
        if (name != null && !name.isBlank()) {
            lastKnownNames.put(player, name);
        }
    }

    private static <T> List<T> sorted(Map<String, T> from, Comparator<T> order) {
        if (from == null || from.isEmpty()) {
            return List.of();
        }
        List<T> all = new ArrayList<>(from.values());
        all.sort(order);
        return List.copyOf(all);
    }

    /** Queues a save of the state as it is right now. */
    private void persist() {
        Map<UUID, Map<String, Stop>> stopSnapshot = new LinkedHashMap<>();
        stops.forEach((owner, mine) -> stopSnapshot.put(owner, Map.copyOf(mine)));
        Map<UUID, Map<String, Route>> routeSnapshot = new LinkedHashMap<>();
        routes.forEach((owner, mine) -> routeSnapshot.put(owner, Map.copyOf(mine)));
        Map<UUID, GhastClaim> claimSnapshot = Map.copyOf(claims);
        Map<UUID, String> names = Map.copyOf(lastKnownNames);
        submit(() -> write(stopSnapshot, routeSnapshot, claimSnapshot, names));
    }

    private void submit(Runnable task) {
        if (writer.isShutdown()) {
            // Shutting down: the flush in close() is the last word, and a change made after it is a
            // change made while the server was already gone.
            task.run();
            return;
        }
        writer.execute(task);
    }

    private void write(Map<UUID, Map<String, Stop>> stopSnapshot,
                       Map<UUID, Map<String, Route>> routeSnapshot,
                       Map<UUID, GhastClaim> claimSnapshot, Map<UUID, String> names) {
        YamlConfiguration yaml = new YamlConfiguration();

        names.forEach((owner, name) -> {
            if (stopSnapshot.containsKey(owner) || routeSnapshot.containsKey(owner)) {
                yaml.set("players." + owner + ".name", name);
            }
        });
        stopSnapshot.forEach((owner, mine) -> mine.forEach((name, stop) -> {
            String path = "players." + owner + ".stops." + name;
            yaml.set(path + ".world", stop.world());
            yaml.set(path + ".x", stop.x());
            yaml.set(path + ".y", stop.y());
            yaml.set(path + ".z", stop.z());
            yaml.set(path + ".shared", stop.shared());
            yaml.set(path + ".created", stop.createdAt());
        }));
        routeSnapshot.forEach((owner, mine) -> mine.forEach((name, route) -> {
            String path = "players." + owner + ".routes." + name;
            yaml.set(path + ".stops", route.stops());
            yaml.set(path + ".loop", route.loop());
            yaml.set(path + ".shared", route.shared());
            yaml.set(path + ".created", route.createdAt());
        }));
        claimSnapshot.forEach((ghast, claim) -> {
            String path = "ghasts." + ghast;
            yaml.set(path + ".owner", claim.owner().toString());
            yaml.set(path + ".name", claim.name());
            yaml.set(path + ".world", claim.world());
            yaml.set(path + ".x", claim.x());
            yaml.set(path + ".y", claim.y());
            yaml.set(path + ".z", claim.z());
            yaml.set(path + ".claimed", claim.claimedAt());
        });

        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".writing");
            Files.writeString(temporary, yaml.saveToString());
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Could not save the ghast lines to " + file
                    + "; they are still in memory and the next change will try again.", failure);
        }
    }

    /** Flushes and stops the writer. Blocks briefly: a shutdown must not lose the last change. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("The ghast lines were still being written when the server shut down.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
