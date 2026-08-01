package de.raindancer.ghastlines;

import org.bukkit.util.Vector;

/**
 * How something hanging under a ghast behaves — the arithmetic, with no server in it.
 *
 * <h2>What was wrong with the first version</h2>
 * It pinned the cargo to a point exactly three blocks under the ghast by overwriting its velocity every
 * tick. That is not a slung load, it is a rigid boom: the boat never swung, never trailed, never lagged
 * behind on take-off, and — because nothing ever touched its rotation — it kept pointing whichever way it
 * happened to be facing while the ghast turned around it and flew off sideways. It looked exactly like what
 * it was: a box being teleported along a line.
 *
 * <h2>What a load under a helicopter actually does</h2>
 * It hangs below the aircraft, swings when the aircraft turns, trails behind when it accelerates, and comes
 * back under it when the aircraft holds still — and it points roughly the way it is being pulled. So this is
 * a spring and a damper rather than a lock: the cargo keeps its own momentum, is pulled toward the point
 * under the ghast only in proportion to how far past the slack it has drifted, and loses a little speed each
 * tick so the swing settles instead of oscillating for ever. Inside the slack radius it is left alone
 * entirely, which is the freedom to hang.
 *
 * <p>The rope still has the last word: past {@link #SNAP_GUARD} the cargo is put back rather than pulled,
 * because vanilla's leash snaps at sixteen blocks and a swing that reaches that far ends the tow.
 */
public final class Tow {

    /** How far under the ghast the load hangs — under the body, where the leads are tied. */
    public static final double HANG_BELOW = 3.0;

    /**
     * How far the load may drift from directly under the ghast before anything pulls it back.
     * <p>
     * This is the swing. Too small and it is the rigid boom again; too large and the load wanders far enough
     * behind to look untethered. Three blocks is about a boat's length, which reads as rope.
     */
    public static final double SLACK = 3.0;

    /** How hard it is pulled back, per block past the slack. A spring constant, in velocity per block. */
    public static final double SPRING = 0.22;

    /**
     * How much of its own speed the load keeps each tick.
     * <p>
     * This is what makes a swing die away rather than run for ever. Close to vanilla's own air drag on
     * purpose: the load should behave like something in the same air as everything else.
     */
    public static final double DAMPING = 0.86;

    /** The fastest the load will be pulled, so a long stretch cannot fling it. */
    public static final double MAX_PULL = 1.2;

    /**
     * The fastest the load may end up going, whatever the spring and the carrier ask for.
     * <p>
     * Not belt and braces: a load that <em>cannot move</em> — wedged against terrain, or held by something
     * else — has the same stretch every tick, so the spring adds the same pull every tick and the velocity
     * climbs without limit until whatever is holding it lets go and it leaves at a hundred blocks a second.
     * The cap is what makes the spring safe against a load that does not respond to it.
     */
    public static final double MAX_SPEED = 1.5;

    /**
     * Past this the load is put back rather than pulled.
     * <p>
     * Under vanilla's snap distance of sixteen and under the ten at which the rope goes taut, so a load that
     * has fallen a long way behind — a flight that turned hard, a chunk that loaded late — is recovered
     * before the leash has an opinion about it.
     */
    public static final double SNAP_GUARD = 9.0;

    /**
     * How far the load's heading may swing in one tick, in radians.
     * <p>
     * Slower than the ghast's own turn rate, because a load on a rope turns after the aircraft does, not
     * with it. About four degrees: a right-angle turn takes a little over a second to follow through.
     */
    public static final double MAX_TURN_PER_TICK = 0.07;

    private Tow() {
    }

    /** The point the load wants to be at: straight under the ghast. */
    public static Vector anchor(Vector ghast) {
        return new Vector(ghast.getX(), ghast.getY() - HANG_BELOW, ghast.getZ());
    }

    /**
     * The load's velocity for this tick.
     *
     * @param cargo    where the load is
     * @param anchor   where it would hang if nothing had happened — see {@link #anchor}
     * @param current  what the load is doing now, so its momentum is kept rather than overwritten
     * @param carrier  what the ghast is doing, so the load travels with it rather than being dragged along
     *                 one tick late
     */
    public static Vector velocity(Vector cargo, Vector anchor, Vector current, Vector carrier) {
        Vector toAnchor = anchor.clone().subtract(cargo);
        double stretch = toAnchor.length();

        // The rope is slack: keep the load's own momentum, carry it along, and let it swing.
        Vector swing = current.clone().multiply(DAMPING).add(carrier.clone().multiply(1 - DAMPING));
        if (stretch <= SLACK || stretch <= 1.0e-6) {
            return swing;
        }

        double pull = Math.min((stretch - SLACK) * SPRING, MAX_PULL);
        return capped(swing.add(toAnchor.multiply(pull / stretch)));
    }

    private static Vector capped(Vector velocity) {
        double speed = velocity.length();
        return speed <= MAX_SPEED || speed <= 1.0e-9 ? velocity : velocity.multiply(MAX_SPEED / speed);
    }

    /** Whether the load has drifted far enough that it should be put back rather than pulled. */
    public static boolean tooFar(Vector cargo, Vector anchor) {
        return cargo.distance(anchor) > SNAP_GUARD;
    }

    /**
     * Which way the load should be facing: along the way it is being pulled, turned into it gradually.
     *
     * <h2>Why it follows its own motion and not the ghast's heading</h2>
     * A load on a rope is dragged, so it points along the drag — which is the same thing as the ghast's
     * heading while flying straight, and honestly different in a turn, where a real slung load swings wide
     * and lags. Using the motion gets both for free, and it degrades gracefully: a load that is barely
     * moving keeps the yaw it has rather than snapping to some arbitrary direction.
     *
     * @param currentYaw where it is facing now, in degrees
     * @param motion     what it is doing; ignored when there is not enough of it to have a direction
     * @return the yaw for this tick, in degrees
     */
    public static float yaw(float currentYaw, Vector motion) {
        double speed = Math.hypot(motion.getX(), motion.getZ());
        if (speed < 0.02) {
            return currentYaw;
        }
        // Minecraft's yaw: 0 is +Z, and it increases clockwise looking down.
        double wanted = Math.toDegrees(Math.atan2(-motion.getX(), motion.getZ()));
        double difference = wrapDegrees(wanted - currentYaw);
        double step = Math.toDegrees(MAX_TURN_PER_TICK);
        return (float) wrapDegrees(currentYaw + Math.max(-step, Math.min(step, difference)));
    }

    /** An angle brought into -180..180, so turning 350° left becomes turning 10° right. */
    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        }
        if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
