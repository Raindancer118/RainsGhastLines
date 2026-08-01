package de.raindancer.ghastlines;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a line is: an order, a loop or not, and what happens when a stop it names goes away.
 *
 * <h2>The one that would be a real bug</h2>
 * A loop's journey has to end where it started, or a ghast working a loop flies the last leg and then jumps
 * back to the first stop instead of flying there. {@link Route#journey()} is the only place that decides it.
 */
class RouteTest {

    private static final UUID OWNER = UUID.randomUUID();

    private static Route route(boolean loop, String... stops) {
        return new Route("line", OWNER, List.of(stops), loop, false, 0L);
    }

    @Test
    @DisplayName("a one-way journey is the stops in order and ends at the last")
    void oneWayJourney() {
        assertThat(route(false, "a", "b", "c").journey()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("a loop's journey returns to the first stop, so the last leg is flown")
    void loopJourneyClosesTheCircle() {
        assertThat(route(true, "a", "b", "c").journey()).containsExactly("a", "b", "c", "a");
    }

    @Test
    @DisplayName("a route with fewer than two stops cannot be flown and has no journey")
    void needsTwoStops() {
        assertThat(route(false, "a").isFlyable()).isFalse();
        assertThat(route(false, "a").journey()).isEmpty();
        assertThat(route(true, "a", "b").isFlyable()).isTrue();
        assertThat(Route.empty("line", OWNER, 0L).isFlyable()).isFalse();
    }

    @Test
    @DisplayName("a stop may be called at twice, but not twice in a row")
    void repeatsAreAllowedButNotAdjacent() {
        Route out = route(false, "a", "b").plus("a");
        assertThat(out.stops()).containsExactly("a", "b", "a");
        assertThat(out.plus("a").stops())
                .as("a leg of zero length is a mis-click, not a timetable")
                .containsExactly("a", "b", "a");
    }

    @Test
    @DisplayName("a route stops growing at the maximum")
    void respectsTheMaximum() {
        Route full = Route.empty("line", OWNER, 0L);
        for (int index = 0; index < Route.MAXIMUM_STOPS + 5; index++) {
            // Alternating, because the same stop twice in a row is refused for its own reason.
            full = full.plus(index % 2 == 0 ? "a" : "b");
        }
        assertThat(full.stops()).hasSize(Route.MAXIMUM_STOPS);
        assertThat(full.isFull()).isTrue();
    }

    @Test
    @DisplayName("stops are dropped by position, because the same name can appear twice")
    void dropsByPosition() {
        Route line = route(false, "a", "b", "a");
        assertThat(line.minus(0).stops()).containsExactly("b", "a");
        assertThat(line.minus(2).stops()).containsExactly("a", "b");
        assertThat(line.minus(9)).isSameAs(line);
        assertThat(line.minus(-1)).isSameAs(line);
    }

    @Test
    @DisplayName("reordering moves one stop and leaves the rest alone")
    void reorders() {
        Route line = route(false, "a", "b", "c");
        assertThat(line.moveUp(2).stops()).containsExactly("a", "c", "b");
        assertThat(line.moveDown(0).stops()).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("the ends cannot be moved off the ends")
    void reorderingAtTheEdgesDoesNothing() {
        Route line = route(false, "a", "b", "c");
        assertThat(line.moveUp(0)).isSameAs(line);
        assertThat(line.moveDown(2)).isSameAs(line);
        assertThat(line.moveUp(-1)).isSameAs(line);
        assertThat(line.moveDown(99)).isSameAs(line);
    }

    @Test
    @DisplayName("a deleted stop is reported once, in route order")
    void namesMissingStopsOnce() {
        Route line = route(true, "a", "gone", "b", "gone");
        Set<String> known = Set.of("a", "b");
        assertThat(line.missingStops(known::contains)).containsExactly("gone");
    }

    @Test
    @DisplayName("the flags are switches and nothing else changes with them")
    void flagsAreIndependent() {
        Route line = route(false, "a", "b");
        assertThat(line.withLoop(true).loop()).isTrue();
        assertThat(line.withLoop(true).stops()).isEqualTo(line.stops());
        assertThat(line.withShared(true).shared()).isTrue();
        assertThat(line.withShared(true).loop()).isFalse();
        assertThat(line.kind()).isEqualTo("one way");
        assertThat(line.withLoop(true).kind()).isEqualTo("loop");
    }

    @Test
    @DisplayName("the stop list cannot be changed from outside the record")
    void stopsAreCopied() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a", "b"));
        Route line = new Route("line", OWNER, mutable, false, false, 0L);
        mutable.add("c");
        assertThat(line.stops()).containsExactly("a", "b");
    }
}
