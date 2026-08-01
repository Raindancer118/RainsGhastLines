package de.raindancer.ghastlines;

import org.bukkit.util.Vector;

/**
 * How a ghast gets from where it is to where it is going — the arithmetic, with no server in it.
 *
 * <h2>Why the ghast is flown rather than pathfound</h2>
 * {@code Mob#getPathfinder()} is the obvious answer and the wrong one here. A mob's navigation is built
 * for the dozen blocks around it: it has a maximum path length, it recomputes constantly, and asking it
 * for a point two thousand blocks away gets a refusal or a path to the edge of what it can see. A transit
 * network needs the opposite — a long, boring, predictable line — so the flight is driven directly, one
 * velocity per tick, and the AI is switched off for the duration so it cannot argue.
 *
 * <h2>Why the terrain is measured ahead and not underneath</h2>
 * The first version of this measured the ground <em>under</em> the ghast, and it flew into the side of
 * every mountain it met: by the time rising ground is underneath you the slope is already in front of
 * you, and a ghast climbing at the speed it cruises cannot out-climb a cliff it is touching. The ground
 * that matters is the ground it is about to be over, so {@link Surroundings#groundAhead} is sampled along
 * the route and {@link #cruiseY} clears the highest of it. Rising terrain therefore lifts the cruise line
 * long before the ghast gets there.
 *
 * <h2>Why the flight is not up, along, down</h2>
 * Because nothing flies like that, and it showed: the ghast rose vertically like a lift, crossed at a
 * fixed height, stopped dead over the stop and sank onto it. Now there is one wanted altitude per tick —
 * {@link #desiredY} — which is the cruise line while there is distance to run and a straight glide slope
 * once the stop is close enough to start down for. Vertical and forward motion are one vector, normalised
 * to a constant airspeed, so a climb is a climb <em>while</em> flying and a descent is a descent while
 * flying.
 *
 * <h2>What it does about things in the way</h2>
 * Three answers, in order of how little they cost. Something solid ahead with room beside it is gone
 * <em>round</em> — a five-block tower is not worth climbing over, and the sidestep plus the turn rate make it
 * a shallow curve. With no room to either side, it climbs. And a ghast under an overhang, in a hangar or down
 * a hole cannot climb at all, which is what {@link Surroundings#clearAbove} is for: it makes horizontal
 * progress instead and tries again once it is out from under. Only when none of the three is available does
 * {@link FlightService} go and search for a way out, because that is the expensive answer.
 *
 * <p>Everything here takes and returns plain vectors and doubles, so the whole flight model is testable
 * without a server. {@link FlightService} is the part that needs one.
 */
public final class Steering {

    /** Where a ghast is in its current leg. */
    public enum Phase {

        /** Going up, because something is in the way or it is a long way below its line. */
        CLIMB("Climbing"),

        /** Flying the route: forward, on the altitude the route wants at this point. */
        CRUISE("En route"),

        /** The last few blocks onto the stop, slowly. */
        APPROACH("Arriving"),

        /** Hovering at the stop while people get on and off. */
        BOARDING("Boarding");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        /** What a boss bar or a status line calls this phase. */
        public String label() {
            return label;
        }
    }

    /** Within this much of the aim point, in three dimensions, the ghast settles rather than flies. */
    public static final double APPROACH_RADIUS = 6.0;

    /** Within this much of the aim point the leg is over. */
    public static final double LANDED_RADIUS = 1.75;

    /** How close to the wanted altitude counts as being on it. */
    public static final double CLIMB_TOLERANCE = 0.75;

    /** How far below its line the ghast may drift before it stops flying forward and just climbs. */
    public static final double RECLIMB_MARGIN = 4.0;

    /**
     * Blocks flown forward per block of height given up on the way down.
     * <p>
     * Three is shallow enough to read as a descent rather than as a drop: from twelve blocks up the ghast
     * starts down about thirty-six blocks out and arrives level with the stop as it reaches it.
     */
    public static final double GLIDE_RATIO = 3.0;

    /** How much of its airspeed the ghast keeps for the final few blocks, so it settles. */
    public static final double APPROACH_SPEED_FACTOR = 0.4;

    /** The fastest it will give up height while cruising, as a fraction of airspeed. */
    public static final double DESCENT_FACTOR = 0.7;

    /**
     * A leg shorter than this is flown straight at the stop, with no cruise line at all.
     * <p>
     * Otherwise moving a ghast eight blocks would send it up to a cruise altitude and back down, which
     * looks broken rather than careful.
     */
    public static final double SHORT_HOP = 16.0;

    /** Blocks kept between the cruise height and the world ceiling, so a flight cannot hit it. */
    public static final int CEILING_MARGIN = 6;

    /**
     * How far the heading may swing in one tick, in radians.
     * <p>
     * About seven degrees, so a full reversal takes a little over a second — long enough to be a turn you can
     * watch, short enough that the ghast is not still turning when it reaches the next waypoint.
     */
    public static final double MAX_TURN_PER_TICK = 0.12;

    /** How much of a sidestep is still forward motion, and how much is sideways. */
    public static final double SIDESTEP_FORWARD = 0.4;
    public static final double SIDESTEP_SIDEWAYS = 1.0;

    private Steering() {
    }

    // ------------------------------------------------------------------ how high

    /**
     * The height this leg should be crossed at.
     *
     * @param groundBelow    the highest solid block under the ghast right now
     * @param groundAhead    the highest solid block along the stretch it is about to fly over — the
     *                       reading that actually decides whether it clears a mountain
     * @param groundAtTarget the highest solid block at the destination
     * @param currentY       where the ghast is, so one already higher than it needs to be does not dive
     * @param clearance      blocks of air the flight keeps under itself
     * @param worldMax       the world's build height
     */
    public static double cruiseY(double groundBelow, double groundAhead, double groundAtTarget,
                                 double currentY, int clearance, int worldMax) {
        double wanted = Math.max(Math.max(groundBelow, groundAhead), groundAtTarget) + clearance;
        // A ghast that is already above the height it needs keeps its altitude: dropping to exactly the
        // computed line would be a dive followed by nothing, and height is never the problem.
        double kept = Math.max(wanted, Math.min(currentY, worldMax - CEILING_MARGIN));
        return Math.min(kept, worldMax - CEILING_MARGIN);
    }

    /**
     * The altitude the ghast wants <em>at this point along the route</em>: the cruise line while there is
     * distance to run, then a straight glide down to the stop.
     *
     * @param horizontalLeft how far there still is to go, horizontally
     */
    public static double desiredY(double cruiseY, double targetY, double horizontalLeft) {
        double descent = cruiseY - targetY;
        if (descent <= 0) {
            // The stop is above the cruise line — a landing pad up a mountain. Climb to it, do not glide.
            return cruiseY;
        }
        double glideStart = descent * GLIDE_RATIO;
        if (horizontalLeft >= glideStart) {
            return cruiseY;
        }
        return targetY + descent * (horizontalLeft / glideStart);
    }

    // ------------------------------------------------------------------ the phases

    /** The phase a leg starts in. */
    public static Phase initial(Vector position, Vector target, double cruiseY) {
        // Close enough that a cruise line would be a detour: fly straight at it.
        return horizontal(position, target) <= SHORT_HOP || position.distance(target) <= APPROACH_RADIUS
                ? Phase.APPROACH
                : Phase.CRUISE;
    }

    /**
     * The phase after this tick.
     * <p>
     * {@link Phase#BOARDING} is terminal here: how long the boarding lasts is a setting, not geometry,
     * so it is the caller's business.
     */
    public static Phase next(Phase phase, Vector position, Vector target, double cruiseY,
                             Surroundings around) {
        return switch (phase) {
            case CLIMB -> {
                // Either high enough, or there is nothing to be gained by pushing at a ceiling — and flying
                // on is also how the ghast gets out from under whatever is above it. "High enough" is the
                // wanted altitude here as well: a climb that aimed at the cruise line while the glide slope
                // was already bringing the ghast down is the same fight in the other direction.
                double wantedY = Math.max(desiredY(cruiseY, target.getY(), horizontal(position, target)),
                        position.getY());
                yield position.getY() >= Math.min(cruiseY, wantedY) - CLIMB_TOLERANCE || !around.clearAbove()
                        ? Phase.CRUISE : Phase.CLIMB;
            }
            case CRUISE -> {
                if (position.distance(target) <= APPROACH_RADIUS) {
                    yield Phase.APPROACH;
                }
                // Something in the way that cannot be gone round, or a long way below where it should be:
                // gain height before flying on, and only when there is height to gain. A wall with open space
                // beside it stays a crossing, because going round it is part of crossing.
                //
                // The altitude compared against is the wanted one for this point along the route, NOT the
                // cruise line. Against the cruise line, a ghast on its glide slope is always "too low" —
                // which is the whole point of a glide slope — so the two rules fought each other over the
                // stop: the glide pulled it down, this pushed it back up to the cruise height, and it hung
                // ten blocks above the player bobbing up and down until somebody gave up and walked away.
                double wantedY = desiredY(cruiseY, target.getY(), horizontal(position, target));
                boolean needsHeight = (around.blockedAhead() && !around.canSidestep())
                        || position.getY() < wantedY - RECLIMB_MARGIN;
                yield needsHeight && around.clearAbove() ? Phase.CLIMB : Phase.CRUISE;
            }
            case APPROACH -> position.distance(target) <= LANDED_RADIUS ? Phase.BOARDING : Phase.APPROACH;
            case BOARDING -> Phase.BOARDING;
        };
    }

    // ------------------------------------------------------------------ the velocity

    /**
     * What to set the ghast's velocity to this tick.
     *
     * @param speed blocks per tick — the ghast's own airspeed; see {@link FlightService}
     */
    public static Vector velocity(Phase phase, Vector position, Vector target, double cruiseY,
                                  double speed, Surroundings around) {
        return switch (phase) {
            case CLIMB -> climb(position, target, cruiseY, speed, around);
            case CRUISE -> cruise(position, target, cruiseY, speed, around);
            case APPROACH -> {
                Vector towards = target.clone().subtract(position);
                double distance = towards.length();
                double settling = speed * APPROACH_SPEED_FACTOR;
                yield distance <= 1.0e-6
                        ? new Vector()
                        : towards.multiply(Math.min(settling, distance) / distance);
            }
            case BOARDING -> new Vector();
        };
    }

    /**
     * Up — or, when there is no room up, forward, which is how the ghast leaves a building.
     * <p>
     * Never straight up with nothing else: a little forward motion keeps a climb out of a valley from
     * turning into a hover against the cliff behind it.
     */
    private static Vector climb(Vector position, Vector target, double cruiseY, double speed,
                                Surroundings around) {
        if (!around.clearAbove()) {
            return forward(position, target, speed);
        }
        double rise = Math.min(speed, Math.max(0, cruiseY - position.getY()));
        return capped(forward(position, target, speed * 0.25).setY(rise), speed);
    }

    private static Vector cruise(Vector position, Vector target, double cruiseY, double speed,
                                 Surroundings around) {
        double wantedY = desiredY(cruiseY, target.getY(), horizontal(position, target));
        double error = wantedY - position.getY();
        double vertical = error >= 0
                ? Math.min(error, speed)
                : Math.max(error, -speed * DESCENT_FACTOR);

        if (!around.blockedAhead()) {
            return capped(forward(position, target, speed).setY(vertical), speed);
        }
        if (around.canSidestep()) {
            // Round it, not over it: a five-block tower is not worth a climb, and going round is both the
            // shorter way and — with the turn rate on top — a curve rather than a swerve.
            return capped(aside(position, target, speed, around.sidestep()).setY(vertical), speed);
        }
        // Nowhere to the sides. Climbing is next, and next() has already switched the phase for that; with no
        // room above either, forward is all there is left and the stall detector ends the flight rather than
        // this pretending it can be steered around.
        Vector along = around.clearAbove() ? new Vector() : forward(position, target, speed);
        return capped(along.setY(vertical), speed);
    }

    /**
     * Forward, leaning to one side.
     * <p>
     * Mostly sideways and a little forwards: enough of the original heading that the ghast keeps making
     * progress along the route, enough sideways that it clears what is in front of it within a second or two.
     * The result is a shallow arc, which is what going round something looks like.
     */
    private static Vector aside(Vector position, Vector target, double speed, int side) {
        Vector ahead = forward(position, target, 1.0);
        if (ahead.lengthSquared() <= 1.0e-6) {
            return new Vector();
        }
        // "Left" of a heading, in Minecraft's left-handed horizontal plane.
        Vector left = new Vector(-ahead.getZ(), 0, ahead.getX()).multiply(side);
        return ahead.multiply(SIDESTEP_FORWARD).add(left.multiply(SIDESTEP_SIDEWAYS))
                .normalize().multiply(speed);
    }

    /** Horizontal motion toward the target, never overshooting it. */
    private static Vector forward(Vector position, Vector target, double speed) {
        Vector flat = new Vector(target.getX() - position.getX(), 0, target.getZ() - position.getZ());
        double distance = flat.length();
        if (distance <= 1.0e-6) {
            return new Vector();
        }
        return flat.multiply(Math.min(speed, distance) / distance);
    }

    /**
     * Holds the airspeed constant.
     * <p>
     * Without this a ghast climbing while flying forward would travel faster than one doing either alone,
     * which is both wrong and visible — the diagonal stretches are exactly where somebody is watching.
     */
    private static Vector capped(Vector velocity, double speed) {
        double length = velocity.length();
        return length <= speed || length <= 1.0e-6 ? velocity : velocity.multiply(speed / length);
    }

    // ------------------------------------------------------------------ what the bar says

    /**
     * Turns the ghast toward where it wants to go, at most so far in one tick.
     *
     * <h2>Why a flight needs a turn rate at all</h2>
     * Setting the velocity straight to the wanted vector makes a ghast change direction instantly: it arrives
     * at a waypoint pointing north and leaves it pointing east in the same tick, which reads as teleporting
     * rather than as flying. Rotating the heading a little each tick makes the same route a curve — the ghast
     * banks into the turn, and because {@code FlightService} points it along its velocity, it looks where it
     * is going while it does.
     *
     * <p>The speed comes from {@code wanted}; only the direction is held back. A turn that slowed the ghast
     * down would make every corner a stall.
     *
     * @param maxTurn the most the heading may rotate this tick, in radians
     */
    public static Vector smooth(Vector current, Vector wanted, double maxTurn) {
        double speed = wanted.length();
        if (speed <= 1.0e-6) {
            return wanted;
        }
        double currentFlat = Math.hypot(current.getX(), current.getZ());
        if (currentFlat <= 1.0e-3) {
            // Standing still, or going straight up: there is no heading to turn from.
            return wanted;
        }
        double from = Math.atan2(current.getZ(), current.getX());
        double to = Math.atan2(wanted.getZ(), wanted.getX());
        double difference = wrap(to - from);
        double turn = Math.max(-maxTurn, Math.min(maxTurn, difference));
        double heading = from + turn;

        double wantedFlat = Math.hypot(wanted.getX(), wanted.getZ());
        return new Vector(Math.cos(heading) * wantedFlat, wanted.getY(), Math.sin(heading) * wantedFlat);
    }

    /** An angle difference brought into -π..π, so turning 350° left becomes turning 10° right. */
    private static double wrap(double radians) {
        double wrapped = radians;
        while (wrapped > Math.PI) {
            wrapped -= 2 * Math.PI;
        }
        while (wrapped < -Math.PI) {
            wrapped += 2 * Math.PI;
        }
        return wrapped;
    }

    /** Horizontal distance only — the measure the glide slope is computed against. */
    public static double horizontal(Vector from, Vector to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Seconds still to fly, from what is left and how fast the flight goes.
     * <p>
     * Deliberately naive: it counts the distance, not the climb, the boarding or the terrain. It is a
     * progress bar's estimate, and a number that is roughly right the whole way is more use than one that
     * is exactly right only at the end.
     */
    public static long etaSeconds(double blocksLeft, double blocksPerTick) {
        if (blocksPerTick <= 0) {
            return 0;
        }
        return Math.round(blocksLeft / blocksPerTick / TransitOptions.TICKS_PER_SECOND);
    }

    /** How far along a leg the ghast is, 0 to 1, from the distance left and the distance it started at. */
    public static float progress(double blocksLeft, double legLength) {
        if (legLength <= 1.0e-6) {
            return 1f;
        }
        double done = (legLength - blocksLeft) / legLength;
        return (float) Math.max(0, Math.min(1, done));
    }
}
