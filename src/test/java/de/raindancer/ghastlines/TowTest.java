package de.raindancer.ghastlines;

import org.assertj.core.data.Offset;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a boat hangs under a flying ghast.
 *
 * <h2>What these pin</h2>
 * The first version of the tow pinned the boat to a point three blocks under the ghast and never touched its
 * rotation, so it neither swung nor turned: a rigid boom with a boat on the end, flying sideways. Every test
 * here is one half of "it hangs on a rope" — it keeps its own momentum, it is only pulled once the rope goes
 * taut, the swing dies away instead of running for ever, and it points where it is being pulled.
 */
class TowTest {

    private static final Offset<Double> CLOSE = Offset.offset(1.0e-6);

    private static Vector at(double x, double y, double z) {
        return new Vector(x, y, z);
    }

    @Test
    @DisplayName("the load hangs under the ghast, not beside it")
    void anchorIsBelow() {
        assertThat(Tow.anchor(at(10, 80, -4)))
                .isEqualTo(at(10, 80 - Tow.HANG_BELOW, -4));
    }

    @Test
    @DisplayName("inside the slack it is left alone to swing, not hauled into place")
    void slackIsFreedom() {
        Vector anchor = at(0, 60, 0);
        Vector cargo = at(2, 60, 0);              // two blocks off, inside the slack
        Vector drifting = at(0.3, 0, 0);

        Vector next = Tow.velocity(cargo, anchor, drifting, new Vector());
        assertThat(next.getX())
                .as("its own momentum survives — that is the swing")
                .isCloseTo(0.3 * Tow.DAMPING, CLOSE);
        assertThat(next.getY()).isZero();
    }

    @Test
    @DisplayName("past the slack the rope pulls it back, toward the ghast")
    void ropeGoesTaut() {
        Vector anchor = at(0, 60, 0);
        Vector cargo = at(Tow.SLACK + 4, 60, 0);

        Vector next = Tow.velocity(cargo, anchor, new Vector(), new Vector());
        assertThat(next.getX())
                .as("pulled back toward the anchor, which is in -x from here")
                .isNegative();
        assertThat(Math.abs(next.getX())).isCloseTo(4 * Tow.SPRING, CLOSE);
    }

    @Test
    @DisplayName("a long stretch cannot fling it")
    void pullIsCapped() {
        Vector anchor = at(0, 60, 0);
        Vector far = at(50, 60, 0);
        assertThat(Tow.velocity(far, anchor, new Vector(), new Vector()).length())
                .isLessThanOrEqualTo(Tow.MAX_PULL + 1.0e-9);
    }

    @Test
    @DisplayName("it travels with the ghast rather than being dragged a tick late")
    void itMovesWithTheCarrier() {
        Vector anchor = at(0, 60, 0);
        Vector cargo = at(0, 60, 0);
        Vector carrier = at(0.19, 0, 0);

        Vector next = Tow.velocity(cargo, anchor, new Vector(), carrier);
        assertThat(next.getX()).isPositive();

        // Held there over many ticks it converges on the ghast's own speed rather than lagging for ever.
        Vector velocity = new Vector();
        for (int tick = 0; tick < 200; tick++) {
            velocity = Tow.velocity(cargo, anchor, velocity, carrier);
        }
        assertThat(velocity.getX()).isCloseTo(carrier.getX(), Offset.offset(1.0e-4));
    }

    @Test
    @DisplayName("a swing dies away instead of running for ever")
    void swingSettles() {
        // Integrated properly: the load moves under its own velocity each tick, which is what makes this a
        // pendulum rather than a spring being wound up against a fixed point.
        Vector anchor = at(0, 60, 0);
        Vector cargo = at(Tow.SLACK + 2, 60, 0);
        Vector velocity = at(0.8, 0, 0);

        double furthest = 0;
        for (int tick = 0; tick < 400; tick++) {
            velocity = Tow.velocity(cargo, anchor, velocity, new Vector());
            cargo = cargo.clone().add(velocity);
            if (tick > 200) {
                furthest = Math.max(furthest, cargo.distance(anchor));
            }
        }
        assertThat(velocity.length())
                .as("damping is what stops a load pendulum-ing under the ghast for the whole flight")
                .isLessThan(0.05);
        assertThat(furthest)
                .as("and it comes to rest hanging within the rope's slack")
                .isLessThanOrEqualTo(Tow.SLACK + 0.5);
    }

    /**
     * The load pinned against terrain — wedged in a doorway, caught on a fence.
     * <p>
     * Its position does not change, so the spring asks for the same pull every tick. Without a cap that
     * accumulates until the moment it comes free, at which point it leaves at a hundred blocks a second.
     */
    @Test
    @DisplayName("a load that cannot move does not wind the spring up")
    void aStuckLoadCannotBeWoundUp() {
        Vector anchor = at(0, 60, 0);
        Vector stuck = at(Tow.SLACK + 5, 60, 0);
        Vector velocity = new Vector();
        for (int tick = 0; tick < 500; tick++) {
            velocity = Tow.velocity(stuck, anchor, velocity, new Vector());
        }
        assertThat(velocity.length()).isLessThanOrEqualTo(Tow.MAX_SPEED + 1.0e-9);
    }

    @Test
    @DisplayName("a load that has fallen far behind is recovered before the leash snaps")
    void tooFarIsRecovered() {
        Vector anchor = at(0, 60, 0);
        assertThat(Tow.tooFar(at(Tow.SNAP_GUARD - 1, 60, 0), anchor)).isFalse();
        assertThat(Tow.tooFar(at(Tow.SNAP_GUARD + 1, 60, 0), anchor)).isTrue();
        assertThat(Tow.SNAP_GUARD)
                .as("under vanilla's 16-block snap, so the rope never gets to decide")
                .isLessThan(16.0);
    }

    // ------------------------------------------------------------------ which way it faces

    @Test
    @DisplayName("it turns toward the way it is being pulled")
    void yawFollowsTheMotion() {
        // Minecraft yaw: 0 is +Z, -90 is +X.
        float turned = Tow.yaw(0f, at(1, 0, 0));
        assertThat(turned).isNegative();
        assertThat(Math.abs(turned))
                .as("and it turns rather than snapping")
                .isLessThan(90f);
    }

    @Test
    @DisplayName("it gets there, and holds still once it has")
    void yawConverges() {
        float yaw = 0f;
        Vector east = at(1, 0, 0);
        for (int tick = 0; tick < 100; tick++) {
            yaw = Tow.yaw(yaw, east);
        }
        assertThat((double) yaw).isCloseTo(-90, Offset.offset(0.5));
        assertThat(Tow.yaw(yaw, east)).isCloseTo(yaw, Offset.offset(0.5f));
    }

    @Test
    @DisplayName("a turn takes the short way round, not the long one")
    void yawTakesTheShortWay() {
        // Facing just west of north, wanting just east of north: ten degrees, not three hundred and fifty.
        float turned = Tow.yaw(175f, at(-0.01, 0, -1));
        assertThat(Tow.wrapDegrees(turned - 175f))
                .as("crossing the ±180 seam must not send it the long way round")
                .isBetween(-1.0, 6.0);
    }

    @Test
    @DisplayName("a load that is barely moving keeps the heading it has")
    void stillLoadKeepsItsYaw() {
        assertThat(Tow.yaw(42f, new Vector())).isEqualTo(42f);
        assertThat(Tow.yaw(42f, at(0.001, 0, 0.001))).isEqualTo(42f);
    }

    @Test
    @DisplayName("the load turns more slowly than the ghast, because it is on a rope")
    void loadLagsTheAircraft() {
        assertThat(Tow.MAX_TURN_PER_TICK).isLessThan(Steering.MAX_TURN_PER_TICK);
    }
}
