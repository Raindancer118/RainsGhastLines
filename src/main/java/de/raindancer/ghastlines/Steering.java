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
 * <h2>Why there are four phases and not one straight line</h2>
 * A straight line from a ghast parked in a valley to a stop behind a mountain goes through the mountain.
 * Climbing first, crossing at a height kept clear of whatever is underneath, and only then coming down
 * is what a flight actually looks like, and it needs no path search at all: the only question per tick is
 * "am I high enough yet", and the ground under the ghast answers it. {@code cruiseY} is recomputed every
 * tick by the caller from the ground below, so rising terrain simply raises the cruise height and
 * {@link #next} puts the ghast back into {@link Phase#CLIMB} until it has caught up.
 *
 * <p>Everything here takes and returns plain vectors and doubles, so the whole flight model is testable
 * without a server. {@link FlightService} is the part that needs one.
 */
public final class Steering {

    /** Where a ghast is in its current leg. */
    public enum Phase {

        /** Going up, to get above whatever is in the way. */
        CLIMB("Climbing"),

        /** Crossing, at a height kept clear of the ground below. */
        CRUISE("En route"),

        /** Coming down onto the stop — also how a short hop is flown, since it needs no climb. */
        APPROACH("Descending"),

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

    /** Within this many blocks of the target, horizontally, the descent starts. */
    public static final double ARRIVAL_RADIUS = 2.0;

    /** Within this much of the target point in three dimensions, the leg is over. */
    public static final double LANDED_RADIUS = 1.75;

    /** How close to the cruise height counts as having got there. */
    public static final double CLIMB_TOLERANCE = 0.75;

    /** How far below the cruise height the ghast may drift before it climbs again. */
    public static final double RECLIMB_MARGIN = 3.0;

    /**
     * A leg shorter than this is flown as a direct approach with no climb at all.
     * <p>
     * Otherwise moving a ghast eight blocks would send it forty blocks into the sky and back, which
     * looks broken rather than careful.
     */
    public static final double SHORT_HOP = 16.0;

    /** Blocks kept between the cruise height and the world ceiling, so a flight cannot hit it. */
    public static final int CEILING_MARGIN = 6;

    private Steering() {
    }

    /**
     * The height this leg should be crossed at.
     *
     * @param groundBelow the highest solid block under the ghast right now
     * @param groundAtTarget the highest solid block at the destination
     * @param currentY    where the ghast is, so a ghast already higher than it needs to be does not dive
     * @param clearance   blocks of air the flight keeps under itself
     * @param worldMax    the world's build height
     */
    public static double cruiseY(double groundBelow, double groundAtTarget, double currentY,
                                 int clearance, int worldMax) {
        double wanted = Math.max(groundBelow, groundAtTarget) + clearance;
        // A ghast that is already above the height it needs keeps its altitude: dropping to exactly the
        // computed line would be a dive followed by nothing, and height is never the problem.
        double kept = Math.max(wanted, Math.min(currentY, worldMax - CEILING_MARGIN));
        return Math.min(kept, worldMax - CEILING_MARGIN);
    }

    /** The phase a leg starts in. */
    public static Phase initial(Vector position, Vector target, double cruiseY) {
        if (horizontal(position, target) <= SHORT_HOP) {
            return Phase.APPROACH;
        }
        return position.getY() >= cruiseY - CLIMB_TOLERANCE ? Phase.CRUISE : Phase.CLIMB;
    }

    /**
     * The phase after this tick.
     * <p>
     * {@link Phase#BOARDING} is terminal here: how long the boarding lasts is a setting, not geometry,
     * so it is the caller's business.
     */
    public static Phase next(Phase phase, Vector position, Vector target, double cruiseY) {
        return switch (phase) {
            case CLIMB -> position.getY() >= cruiseY - CLIMB_TOLERANCE ? Phase.CRUISE : Phase.CLIMB;
            case CRUISE -> {
                if (horizontal(position, target) <= ARRIVAL_RADIUS) {
                    yield Phase.APPROACH;
                }
                // The ground came up under us — or something pushed us down. Get back up first.
                yield position.getY() < cruiseY - RECLIMB_MARGIN ? Phase.CLIMB : Phase.CRUISE;
            }
            case APPROACH -> position.distance(target) <= LANDED_RADIUS ? Phase.BOARDING : Phase.APPROACH;
            case BOARDING -> Phase.BOARDING;
        };
    }

    /**
     * What to set the ghast's velocity to this tick.
     *
     * @param speed blocks per tick
     */
    public static Vector velocity(Phase phase, Vector position, Vector target, double cruiseY,
                                  double speed) {
        return switch (phase) {
            case CLIMB -> new Vector(0, Math.min(speed, Math.max(0, cruiseY - position.getY())), 0);
            case CRUISE -> {
                Vector flat = new Vector(target.getX() - position.getX(), 0,
                        target.getZ() - position.getZ());
                double distance = flat.length();
                Vector along = distance <= 1.0e-6
                        ? new Vector()
                        : flat.multiply(Math.min(speed, distance) / distance);
                // Holding the cruise line while crossing, at half speed so it reads as levelling out
                // rather than as a second climb happening at the same time.
                double correction = clamp(cruiseY - position.getY(), -speed / 2, speed / 2);
                yield along.setY(correction);
            }
            case APPROACH -> {
                Vector towards = target.clone().subtract(position);
                double distance = towards.length();
                yield distance <= 1.0e-6 ? new Vector() : towards.multiply(Math.min(speed, distance) / distance);
            }
            case BOARDING -> new Vector();
        };
    }

    /** Horizontal distance only — the measure that decides when a crossing is over. */
    public static double horizontal(Vector from, Vector to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Seconds still to fly, from what is left and how fast the flight goes.
     * <p>
     * Deliberately naive: it counts the distance, not the climb, the boarding or the terrain. It is a
     * progress bar's estimate, and a number that is roughly right the whole way is more use than one
     * that is exactly right only at the end.
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

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
