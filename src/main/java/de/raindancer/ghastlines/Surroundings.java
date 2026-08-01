package de.raindancer.ghastlines;

/**
 * What the world looks like around a flying ghast this tick — the three things the steering needs to
 * know and cannot work out from coordinates alone.
 *
 * <h2>Why this exists as a record</h2>
 * Reading blocks needs a server; deciding what to do about them does not. Putting the readings in a
 * value that {@link FlightService} fills in and {@link Steering} consumes keeps the whole flight model
 * unit-testable — which matters here more than anywhere else in the plugin, because "the ghast flew
 * into a hill" is a bug you can only see by standing there watching it.
 *
 * @param clearAbove   whether there is open air above the ghast, so climbing would achieve something
 * @param blockedAhead whether something solid is directly in the way at the ghast's own height
 * @param clearLeft    whether it could sidestep left instead of climbing
 * @param clearRight   whether it could sidestep right instead of climbing
 * @param groundAhead  the highest solid block along the next stretch of the route — <em>ahead</em>, not
 *                     underneath, which is the whole point: ground under the ghast is ground it has
 *                     already cleared
 */
public record Surroundings(boolean clearAbove, boolean blockedAhead, boolean clearLeft, boolean clearRight,
                           double groundAhead) {

    /** Open sky and nothing in the way — the common case, and what a test usually wants. */
    public static Surroundings open(double groundAhead) {
        return new Surroundings(true, false, true, true, groundAhead);
    }

    /** Whether going round is an option at all, and which way is nearer to hand. */
    public boolean canSidestep() {
        return clearLeft || clearRight;
    }

    /**
     * Which way to lean, as a multiplier on the "left" vector: -1 for right, +1 for left, 0 for neither.
     * <p>
     * Left is preferred when both are open only so that the choice is repeatable — a ghast that picked a side
     * at random each tick would sit in front of the obstacle shaking its head.
     */
    public int sidestep() {
        if (clearLeft) {
            return 1;
        }
        return clearRight ? -1 : 0;
    }
}
