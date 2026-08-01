package de.raindancer.ghastlines;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Getting out from under something, against a world drawn by hand.
 *
 * <h2>Why this can be tested at all</h2>
 * {@link Escape} takes the world as a {@link Escape.Space} — one method, "is this block free" — so a cliff, a
 * tunnel and a sealed box are three lambdas rather than three server worlds. That is the whole reason the
 * search is written this way: "the ghast got out of the cave" is otherwise something you can only find out by
 * building a cave and watching.
 */
class EscapeTest {

    /** Open sky everywhere. */
    private static final Escape.Space OPEN = (x, y, z) -> true;

    /** Ground at y=0 and nothing else. */
    private static Escape.Space ground() {
        return (x, y, z) -> y > 0;
    }

    /**
     * A ledge: ground at 0, and a solid slab at y=20 covering everything west of x=40. Standing under it, the
     * way out is east — not up.
     */
    private static Escape.Space overhang() {
        return (x, y, z) -> {
            if (y <= 0) {
                return false;
            }
            return !(x < 40 && y >= 20 && y <= 23);
        };
    }

    @Test
    @DisplayName("in the open there is nothing to escape from")
    void openSkyNeedsNoRoute() {
        // The start never counts as the way out, so the first step up is the answer and the route is short.
        List<Vector> way = Escape.route(OPEN, new Vector(0, 64, 0));
        assertThat(way).hasSize(1);
        assertThat(way.getFirst().getY()).isGreaterThan(64);
    }

    @Test
    @DisplayName("under an overhang the way out is sideways, and it is found")
    void findsTheWayOutFromUnderALedge() {
        List<Vector> way = Escape.route(overhang(), new Vector(20, 10, 0));
        assertThat(way).isNotEmpty();

        Vector out = way.getLast();
        assertThat(out.getX())
                .as("the only opening is east of x=40, so that is where the route has to end up")
                .isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("a sealed box has no way out, and says so instead of guessing")
    void sealedIsSealed() {
        // A 12-block room with walls on every side.
        Escape.Space box = (x, y, z) ->
                x > 0 && x < 12 && z > 0 && z < 12 && y > 0 && y < 12;
        assertThat(Escape.route(box, new Vector(6, 4, 6))).isEmpty();
    }

    @Test
    @DisplayName("nothing is routed through a gap a ghast does not fit in")
    void respectsTheGhastsSize() {
        // A room with a two-block slot in the east wall — open air, and far too small for a happy ghast.
        Escape.Space slot = (x, y, z) -> {
            boolean insideRoom = x > 0 && x < 12 && z > 0 && z < 12 && y > 0 && y < 12;
            boolean throughSlot = x >= 12 && y >= 5 && y <= 6 && z >= 5 && z <= 6;
            return insideRoom || throughSlot;
        };
        assertThat(Escape.route(slot, new Vector(6, 4, 6)))
                .as("a two-block slot is not a door for something four blocks across")
                .isEmpty();
    }

    @Test
    @DisplayName("the route is corners, not a staircase")
    void routeIsSmoothed() {
        List<Vector> way = Escape.route(overhang(), new Vector(20, 10, 0));
        // A lattice path east then up would be a dozen cells; what comes back should be a handful of turns.
        assertThat(way.size())
                .as("a smoothed route is corners: %s", way)
                .isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the search stays inside the radius it was given")
    void staysWithinRadius() {
        // The only opening is 60 blocks away; with a radius of 16 it must not be found.
        Escape.Space farAway = (x, y, z) -> y > 0 && !(y >= 20 && y <= 23 && x < 60);
        assertThat(Escape.route(farAway, new Vector(0, 10, 0), 16)).isEmpty();
        assertThat(Escape.route(farAway, new Vector(0, 10, 0), 80)).isNotEmpty();
    }

    @Test
    @DisplayName("ground under the ghast is not a way out")
    void doesNotRouteIntoTheFloor() {
        List<Vector> way = Escape.route(ground(), new Vector(0, 40, 0));
        assertThat(way).isNotEmpty();
        assertThat(way.getFirst().getY())
                .as("up is tried before down, so an escape never starts by burrowing")
                .isGreaterThanOrEqualTo(40);
    }
}
