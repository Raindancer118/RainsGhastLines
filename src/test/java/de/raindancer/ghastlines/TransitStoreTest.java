package de.raindancer.ghastlines;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file: what goes in comes back out, and a file somebody has edited by hand does not take the plugin down.
 *
 * <h2>Why the round trip is tested rather than the writer</h2>
 * The writer's one job is that a restart changes nothing. Testing "did it write this key" would pin the shape
 * of the file, which is nobody's business but this class's; testing that a second store loaded from the same
 * file has the same contents pins the promise.
 */
class TransitStoreTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static TransitStore store(Path file) {
        return new TransitStore(file, Logger.getLogger("TransitStoreTest"));
    }

    /** Saves are queued; close() flushes them and is what a shutdown does. */
    private static TransitStore reloaded(Path file, TransitStore original) {
        original.close();
        TransitStore again = store(file);
        again.load();
        return again;
    }

    @Test
    @DisplayName("stops, routes and claims all survive a restart")
    void everythingRoundTrips(@TempDir Path folder) {
        Path file = folder.resolve("transit.yml");
        TransitStore store = store(file);

        store.putStop(ALICE, "Alice", new Stop("base", ALICE, "world", 1.5, 64, -2.5, false, 100L));
        store.putStop(ALICE, "Alice", new Stop("market", ALICE, "world", 10, 70, 20, true, 200L));
        store.putRoute(ALICE, "Alice",
                new Route("commute", ALICE, List.of("base", "market"), true, true, 300L));
        UUID ghast = UUID.randomUUID();
        store.putClaim(new GhastClaim(ghast, ALICE, "Bus 12", "world", 5, 80, 5, 400L), "Alice");

        TransitStore again = reloaded(file, store);

        assertThat(again.stopsOf(ALICE)).containsExactly(
                new Stop("base", ALICE, "world", 1.5, 64, -2.5, false, 100L),
                new Stop("market", ALICE, "world", 10, 70, 20, true, 200L));
        assertThat(again.findRoute(ALICE, "commute")).contains(
                new Route("commute", ALICE, List.of("base", "market"), true, true, 300L));
        assertThat(again.claimOf(ghast)).contains(
                new GhastClaim(ghast, ALICE, "Bus 12", "world", 5, 80, 5, 400L));
        assertThat(again.nameOf(ALICE)).isEqualTo("Alice");
        again.close();
    }

    @Test
    @DisplayName("two players may both have a stop called the same thing")
    void namesAreScopedToTheirOwner(@TempDir Path folder) {
        Path file = folder.resolve("transit.yml");
        TransitStore store = store(file);
        store.putStop(ALICE, "Alice", new Stop("mine", ALICE, "world", 1, 1, 1, false, 0L));
        store.putStop(BOB, "Bob", new Stop("mine", BOB, "world", 2, 2, 2, false, 0L));

        TransitStore again = reloaded(file, store);
        assertThat(again.findStop(ALICE, "mine")).get().extracting(Stop::x).isEqualTo(1.0);
        assertThat(again.findStop(BOB, "mine")).get().extracting(Stop::x).isEqualTo(2.0);
        again.close();
    }

    @Test
    @DisplayName("only shared stops leave their owner, and a shared stop is still theirs")
    void sharingIsWhatMakesAStopPublic(@TempDir Path folder) {
        TransitStore store = store(folder.resolve("transit.yml"));
        store.putStop(ALICE, "Alice", new Stop("base", ALICE, "world", 1, 1, 1, false, 0L));
        store.putStop(ALICE, "Alice", new Stop("market", ALICE, "world", 2, 2, 2, true, 0L));

        assertThat(store.sharedStops()).extracting(Stop::name).containsExactly("market");
        assertThat(store.stopsOf(ALICE)).extracting(Stop::name).containsExactly("base", "market");
        store.close();
    }

    @Test
    @DisplayName("lookups are case-insensitive, because the names are stored folded")
    void lookupsFoldCase(@TempDir Path folder) {
        TransitStore store = store(folder.resolve("transit.yml"));
        store.putStop(ALICE, "Alice", new Stop("base", ALICE, "world", 1, 1, 1, false, 0L));
        assertThat(store.findStop(ALICE, "BASE")).isPresent();
        assertThat(store.findStop(ALICE, "my.stop"))
                .as("a name this plugin would never store cannot match one it did")
                .isEmpty();
        store.close();
    }

    @Test
    @DisplayName("removing the last of something forgets the player rather than leaving an empty shell")
    void removalTidiesUp(@TempDir Path folder) {
        Path file = folder.resolve("transit.yml");
        TransitStore store = store(file);
        store.putStop(ALICE, "Alice", new Stop("base", ALICE, "world", 1, 1, 1, false, 0L));
        assertThat(store.removeStop(ALICE, "base")).isPresent();
        assertThat(store.removeStop(ALICE, "base")).isEmpty();
        assertThat(store.stopCount(ALICE)).isZero();
        assertThat(store.totalStops()).isZero();

        TransitStore again = reloaded(file, store);
        assertThat(again.stopsOf(ALICE)).isEmpty();
        again.close();
    }

    @Test
    @DisplayName("a claim can be updated in place without a second entry appearing")
    void claimsAreKeyedByTheGhast(@TempDir Path folder) {
        TransitStore store = store(folder.resolve("transit.yml"));
        UUID ghast = UUID.randomUUID();
        GhastClaim claim = new GhastClaim(ghast, ALICE, "old", "world", 0, 0, 0, 0L);
        store.putClaim(claim, "Alice");
        store.refreshClaim(claim.withName("new"));

        assertThat(store.totalClaims()).isEqualTo(1);
        assertThat(store.claimOf(ghast)).get().extracting(GhastClaim::name).isEqualTo("new");
        assertThat(store.claimsOf(ALICE)).hasSize(1);
        assertThat(store.claimsOf(BOB)).isEmpty();
        store.close();
    }

    @Test
    @DisplayName("a hand-edited file with rubbish in it loads everything that is not rubbish")
    void skipsUnreadableEntries(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("transit.yml");
        Files.writeString(file, """
                players:
                  not-a-uuid:
                    stops:
                      base:
                        world: world
                  %s:
                    name: Alice
                    stops:
                      good:
                        world: world
                        x: 1.0
                        y: 2.0
                        z: 3.0
                      'not a name':
                        world: world
                    routes:
                      line:
                        stops: [good, 'not a name']
                        loop: true
                ghasts:
                  also-not-a-uuid:
                    owner: %s
                """.formatted(ALICE, ALICE));

        TransitStore store = store(file);
        store.load();

        assertThat(store.stopNames(ALICE)).containsExactly("good");
        assertThat(store.findRoute(ALICE, "line"))
                .as("the stop that could not be read is dropped from the route, not the whole route")
                .get().extracting(Route::stops).isEqualTo(List.of("good"));
        assertThat(store.totalClaims()).isZero();
        store.close();
    }

    @Test
    @DisplayName("a file that is not there yet is not an error")
    void missingFileLoadsEmpty(@TempDir Path folder) {
        TransitStore store = store(folder.resolve("nothing-here.yml"));
        store.load();
        assertThat(store.totalStops()).isZero();
        assertThat(store.totalRoutes()).isZero();
        assertThat(store.totalClaims()).isZero();
        store.close();
    }
}
