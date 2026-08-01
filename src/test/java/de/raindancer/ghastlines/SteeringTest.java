package de.raindancer.ghastlines;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flight model, without a server.
 *
 * <h2>Why this is worth pinning</h2>
 * Every one of these numbers is invisible in play: a ghast that cruises three blocks too low flies into a hill
 * and stalls, one that overshoots its stop oscillates over it for ever, and one that never leaves
 * {@link Steering.Phase#CLIMB} hovers at the world ceiling. None of that throws, none of it logs, and all of it
 * looks like "the plugin is broken" from the ground.
 */
class SteeringTest {

    private static final int CLEARANCE = 12;
    private static final int WORLD_MAX = 320;
    private static final double SPEED = 0.6;

    private static Vector at(double x, double y, double z) {
        return new Vector(x, y, z);
    }

    // ------------------------------------------------------------------ how high

    @Test
    @DisplayName("the cruise height clears the higher of the two ends")
    void cruiseClearsTheHigherEnd() {
        assertThat(Steering.cruiseY(64, 100, 65, CLEARANCE, WORLD_MAX)).isEqualTo(112);
        assertThat(Steering.cruiseY(100, 64, 65, CLEARANCE, WORLD_MAX)).isEqualTo(112);
    }

    @Test
    @DisplayName("a ghast already higher than it needs to be keeps its altitude rather than diving")
    void keepsExistingAltitude() {
        assertThat(Steering.cruiseY(64, 64, 200, CLEARANCE, WORLD_MAX)).isEqualTo(200);
    }

    @Test
    @DisplayName("the cruise height never reaches the world ceiling")
    void staysUnderTheCeiling() {
        double high = Steering.cruiseY(WORLD_MAX, WORLD_MAX, WORLD_MAX, CLEARANCE, WORLD_MAX);
        assertThat(high).isEqualTo(WORLD_MAX - Steering.CEILING_MARGIN);
        assertThat(Steering.cruiseY(64, 64, 10_000, CLEARANCE, WORLD_MAX))
                .isEqualTo(WORLD_MAX - Steering.CEILING_MARGIN);
    }

    // ------------------------------------------------------------------ the phases

    @Test
    @DisplayName("a short hop is flown directly, with no climb at all")
    void shortHopSkipsTheClimb() {
        Vector from = at(0, 64, 0);
        Vector to = at(8, 64, 0);
        assertThat(Steering.initial(from, to, 120)).isEqualTo(Steering.Phase.APPROACH);
    }

    @Test
    @DisplayName("a long leg climbs first, unless it is already high enough")
    void longLegClimbsFirst() {
        Vector from = at(0, 64, 0);
        Vector to = at(500, 64, 0);
        assertThat(Steering.initial(from, to, 120)).isEqualTo(Steering.Phase.CLIMB);
        assertThat(Steering.initial(at(0, 121, 0), to, 120)).isEqualTo(Steering.Phase.CRUISE);
    }

    @Test
    @DisplayName("the climb ends at the cruise height and the crossing begins")
    void climbBecomesCruise() {
        Vector to = at(500, 64, 0);
        assertThat(Steering.next(Steering.Phase.CLIMB, at(0, 100, 0), to, 120))
                .isEqualTo(Steering.Phase.CLIMB);
        assertThat(Steering.next(Steering.Phase.CLIMB, at(0, 119.5, 0), to, 120))
                .isEqualTo(Steering.Phase.CRUISE);
    }

    @Test
    @DisplayName("rising ground under a crossing puts the ghast back into a climb")
    void terrainRaisesTheCruiseAndTheGhastFollows() {
        // The caller recomputes the cruise height every tick; a mountain arriving raises it far above where
        // the ghast is, and this is what stops the ghast flying into the side of it.
        assertThat(Steering.next(Steering.Phase.CRUISE, at(100, 120, 0), at(500, 64, 0), 180))
                .isEqualTo(Steering.Phase.CLIMB);
    }

    @Test
    @DisplayName("arriving over the stop starts the descent, and reaching it starts the boarding")
    void cruiseBecomesApproachBecomesBoarding() {
        Vector to = at(500, 64, 0);
        assertThat(Steering.next(Steering.Phase.CRUISE, at(499, 120, 0), to, 120))
                .isEqualTo(Steering.Phase.APPROACH);
        assertThat(Steering.next(Steering.Phase.APPROACH, at(500, 80, 0), to, 120))
                .isEqualTo(Steering.Phase.APPROACH);
        assertThat(Steering.next(Steering.Phase.APPROACH, at(500, 65, 0), to, 120))
                .isEqualTo(Steering.Phase.BOARDING);
    }

    @Test
    @DisplayName("boarding is where a leg ends; how long it lasts is not geometry")
    void boardingIsTerminal() {
        assertThat(Steering.next(Steering.Phase.BOARDING, at(0, 64, 0), at(500, 64, 0), 120))
                .isEqualTo(Steering.Phase.BOARDING);
    }

    // ------------------------------------------------------------------ the velocity

    @Test
    @DisplayName("a climb goes straight up and stops exactly at the cruise height")
    void climbDoesNotOvershoot() {
        Vector rising = Steering.velocity(Steering.Phase.CLIMB, at(0, 64, 0), at(500, 64, 0), 120, SPEED);
        assertThat(rising.getX()).isZero();
        assertThat(rising.getZ()).isZero();
        assertThat(rising.getY()).isEqualTo(SPEED);

        Vector nearlyThere = Steering.velocity(Steering.Phase.CLIMB, at(0, 119.9, 0), at(500, 64, 0),
                120, SPEED);
        assertThat(nearlyThere.getY()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1.0e-9));
    }

    @Test
    @DisplayName("a crossing goes at the configured speed and holds the cruise line")
    void cruiseHoldsItsLine() {
        Vector along = Steering.velocity(Steering.Phase.CRUISE, at(0, 110, 0), at(500, 64, 0), 120, SPEED);
        assertThat(Math.hypot(along.getX(), along.getZ())).isCloseTo(SPEED,
                org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(along.getY())
                .as("levelling out happens at half speed, so it does not read as a second climb")
                .isCloseTo(SPEED / 2, org.assertj.core.data.Offset.offset(1.0e-9));
    }

    @Test
    @DisplayName("the last tick of a leg does not overshoot the stop")
    void approachDoesNotOvershoot() {
        Vector to = at(0, 64, 0);
        Vector last = Steering.velocity(Steering.Phase.APPROACH, at(0, 64.2, 0), to, 120, SPEED);
        assertThat(last.length()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1.0e-9));
    }

    @Test
    @DisplayName("standing on the target asks for no movement rather than dividing by zero")
    void zeroDistanceIsSafe() {
        Vector same = at(10, 64, 10);
        assertThat(Steering.velocity(Steering.Phase.APPROACH, same, same.clone(), 120, SPEED).length())
                .isZero();
        assertThat(Steering.velocity(Steering.Phase.CRUISE, same, same.clone(), 64, SPEED).length())
                .isZero();
    }

    @Test
    @DisplayName("boarding is a hover")
    void boardingHovers() {
        assertThat(Steering.velocity(Steering.Phase.BOARDING, at(0, 64, 0), at(500, 64, 0), 120, SPEED))
                .isEqualTo(new Vector());
    }

    // ------------------------------------------------------------------ what the bar says

    @Test
    @DisplayName("progress runs from nothing to all of it and never leaves that range")
    void progressIsAFraction() {
        assertThat(Steering.progress(100, 100)).isZero();
        assertThat(Steering.progress(50, 100)).isEqualTo(0.5f);
        assertThat(Steering.progress(0, 100)).isEqualTo(1f);
        assertThat(Steering.progress(500, 100))
                .as("a summons chases a player, so the distance left can exceed where it started")
                .isZero();
        assertThat(Steering.progress(-5, 100)).isEqualTo(1f);
        assertThat(Steering.progress(0, 0)).isEqualTo(1f);
    }

    @Test
    @DisplayName("the estimate is in seconds, from the distance and the speed")
    void etaIsInSeconds() {
        // 240 blocks at 12 blocks a second.
        assertThat(Steering.etaSeconds(240, 12.0 / TransitOptions.TICKS_PER_SECOND)).isEqualTo(20);
        assertThat(Steering.etaSeconds(240, 0)).isZero();
    }
}
