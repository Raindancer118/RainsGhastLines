package de.raindancer.ghastlines;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A line: two or more stops in an order, flown either there-and-stop or round and round.
 *
 * <h2>Why the stops are names and not {@link Stop} objects</h2>
 * A route is edited over days and the stops it names get moved, shared and deleted in between. Holding
 * copies would mean a route quietly flying to where a stop used to be. Names are resolved at departure,
 * which is also the only moment a missing stop can be reported to somebody who can do something about
 * it. {@link #missingStops} is that check.
 *
 * <h2>Loop versus one way</h2>
 * The difference is exactly one thing: what a loop does after its last stop. {@link #journey} spells it
 * out rather than leaving the flight engine to remember — a one-way route's journey ends at the last
 * stop, a loop's returns to the first, and a loop keeps departing again until somebody stops it.
 *
 * @param name      the key, normalised by {@link Names}
 * @param owner     who may edit it and start it
 * @param stops     stop names, in travel order; may hold fewer than two while being built
 * @param loop      whether the last stop leads back to the first
 * @param shared    whether the rest of the server may see it and ride it
 * @param createdAt when it was made
 */
public record Route(String name, UUID owner, List<String> stops, boolean loop, boolean shared,
                    long createdAt) {

    /** Below this a route cannot be flown: one stop is a destination, not a line. */
    public static final int MINIMUM_STOPS = 2;

    /** A route cannot grow past this — the stop list has to stay something a menu can show. */
    public static final int MAXIMUM_STOPS = 24;

    public Route {
        stops = List.copyOf(stops);
    }

    public static Route empty(String name, UUID owner, long createdAt) {
        return new Route(name, owner, List.of(), false, false, createdAt);
    }

    public boolean isFlyable() {
        return stops.size() >= MINIMUM_STOPS;
    }

    public boolean isFull() {
        return stops.size() >= MAXIMUM_STOPS;
    }

    /**
     * The stops of one complete run, in order.
     * <p>
     * For a loop that is the stop list with the first stop appended, so the ghast finishes where it
     * started and the next run is the same list again. For a one-way route it is the list itself.
     */
    public List<String> journey() {
        if (!isFlyable()) {
            return List.of();
        }
        if (!loop) {
            return stops;
        }
        List<String> round = new ArrayList<>(stops);
        round.add(stops.getFirst());
        return List.copyOf(round);
    }

    /** The stops named here that {@code known} does not have, in route order and without repeats. */
    public List<String> missingStops(java.util.function.Predicate<String> known) {
        List<String> missing = new ArrayList<>();
        for (String stop : stops) {
            if (!known.test(stop) && !missing.contains(stop)) {
                missing.add(stop);
            }
        }
        return List.copyOf(missing);
    }

    // ------------------------------------------------------------------ editing

    /**
     * Appends a stop.
     * <p>
     * Repeats are allowed on purpose: a line that calls at the market twice, or an out-and-back that
     * ends where it started without being a loop, are both real timetables. Only two of the
     * <em>same</em> stop in a row are refused, because that is a leg of zero length and reads as a
     * mis-click rather than as a plan.
     */
    public Route plus(String stop) {
        if (isFull() || (!stops.isEmpty() && stops.getLast().equals(stop))) {
            return this;
        }
        List<String> longer = new ArrayList<>(stops);
        longer.add(stop);
        return new Route(name, owner, longer, loop, shared, createdAt);
    }

    /** Removes the stop at {@code index}, by position rather than by name, because repeats are legal. */
    public Route minus(int index) {
        if (index < 0 || index >= stops.size()) {
            return this;
        }
        List<String> shorter = new ArrayList<>(stops);
        shorter.remove(index);
        return new Route(name, owner, shorter, loop, shared, createdAt);
    }

    /** Moves the stop at {@code index} one place earlier, so a route can be reordered in the GUI. */
    public Route moveUp(int index) {
        if (index <= 0 || index >= stops.size()) {
            return this;
        }
        List<String> reordered = new ArrayList<>(stops);
        reordered.add(index - 1, reordered.remove(index));
        return new Route(name, owner, reordered, loop, shared, createdAt);
    }

    public Route moveDown(int index) {
        if (index < 0 || index >= stops.size() - 1) {
            return this;
        }
        List<String> reordered = new ArrayList<>(stops);
        reordered.add(index + 1, reordered.remove(index));
        return new Route(name, owner, reordered, loop, shared, createdAt);
    }

    public Route withLoop(boolean nowLoop) {
        return new Route(name, owner, stops, nowLoop, shared, createdAt);
    }

    public Route withShared(boolean nowShared) {
        return new Route(name, owner, stops, loop, nowShared, createdAt);
    }

    /** "loop" or "one way", for a lore line and for {@code /groute list}. */
    public String kind() {
        return loop ? "loop" : "one way";
    }
}
