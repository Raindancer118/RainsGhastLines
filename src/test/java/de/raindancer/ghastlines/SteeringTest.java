package de.raindancer.ghastlines;

import org.assertj.core.data.Offset;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flight model, without a server.
 *
 * <h2>Why this is worth pinning</h2>
 * Every one of these numbers is invisible in play until it is embarrassing: a ghast that measures the ground
 * underneath itself flies into the side of the mountain in front of it, one that climbs without looking up
 * pushes at a ceiling until the stall detector gives up on it, and one that stops dead over a stop and sinks
 * looks like a lift rather than an animal. All three of those shipped. None of them threw, none of them
 * logged, and every one of them was reported by somebody standing there watching it.
 */
class SteeringTest {

    private static final int CLEARANCE = 12;
    private static final int WORLD_MAX = 320;
    private static final double SPEED = 0.6;

    private static final Offset<Double> CLOSE = Offset.offset(1.0e-9);

    private static Vector at(double x, double y, double z) {
        return new Vector(x, y, z);
    }

    /** Open sky, nothing in the way, flat ground — the baseline every case varies from. */
    private static Surroundings openAt(double ground) {
        return Surroundings.open(ground);
    }

    @Nested
    @DisplayName("how high it flies")
    class Altitude {

        @Test
        @DisplayName("the cruise height clears the ground ahead, not just the ground underneath")
        void clearsWhatIsAhead() {
            // The ghast is over a valley floor at 64 with a 100-block ridge coming up. This is the
            // regression: the first version only knew about the 64.
            assertThat(Steering.cruiseY(64, 100, 64, 65, CLEARANCE, WORLD_MAX)).isEqualTo(112);
        }

        @Test
        @DisplayName("it also clears the ground under it and the stop it is going to")
        void clearsTheOtherTwoReadings() {
            assertThat(Steering.cruiseY(100, 64, 64, 65, CLEARANCE, WORLD_MAX)).isEqualTo(112);
            assertThat(Steering.cruiseY(64, 64, 100, 65, CLEARANCE, WORLD_MAX)).isEqualTo(112);
        }

        @Test
        @DisplayName("a ghast already higher than it needs to be keeps its altitude rather than diving")
        void keepsExistingAltitude() {
            assertThat(Steering.cruiseY(64, 64, 64, 200, CLEARANCE, WORLD_MAX)).isEqualTo(200);
        }

        @Test
        @DisplayName("the cruise height never reaches the world ceiling")
        void staysUnderTheCeiling() {
            assertThat(Steering.cruiseY(WORLD_MAX, WORLD_MAX, WORLD_MAX, WORLD_MAX, CLEARANCE, WORLD_MAX))
                    .isEqualTo(WORLD_MAX - Steering.CEILING_MARGIN);
            assertThat(Steering.cruiseY(64, 64, 64, 10_000, CLEARANCE, WORLD_MAX))
                    .isEqualTo(WORLD_MAX - Steering.CEILING_MARGIN);
        }
    }

    @Nested
    @DisplayName("the glide slope")
    class Glide {

        @Test
        @DisplayName("far out it holds the cruise line")
        void holdsTheLine() {
            assertThat(Steering.desiredY(120, 64, 500)).isEqualTo(120);
        }

        @Test
        @DisplayName("the descent starts far enough out to be a descent and not a drop")
        void startsEarly() {
            // 56 blocks to lose at three forward per one down: the top of the slope is 168 blocks out.
            assertThat(Steering.desiredY(120, 64, 168)).isCloseTo(120, Offset.offset(1.0e-6));
            assertThat(Steering.desiredY(120, 64, 167)).isLessThan(120);
        }

        @Test
        @DisplayName("halfway down the slope it is halfway down")
        void isLinear() {
            assertThat(Steering.desiredY(120, 64, 84)).isCloseTo(92, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("over the stop it is level with it")
        void endsAtTheStop() {
            assertThat(Steering.desiredY(120, 64, 0)).isCloseTo(64, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("a stop above the cruise line is climbed to, not glided at")
        void aStopUpAMountain() {
            assertThat(Steering.desiredY(80, 120, 30)).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("the phases")
    class Phases {

        @Test
        @DisplayName("a short hop is flown straight at the stop")
        void shortHopGoesDirect() {
            assertThat(Steering.initial(at(0, 64, 0), at(8, 64, 0), 120)).isEqualTo(Steering.Phase.APPROACH);
        }

        @Test
        @DisplayName("a long leg starts cruising, which is where the climb happens")
        void longLegCruises() {
            assertThat(Steering.initial(at(0, 64, 0), at(500, 64, 0), 120)).isEqualTo(Steering.Phase.CRUISE);
        }

        @Test
        @DisplayName("a wall ahead turns a crossing into a climb")
        void aWallMeansUp() {
            // Walled in on both sides as well, so going round is not on offer and climbing is what is left.
            Surroundings wall = new Surroundings(true, true, false, false, 64);
            assertThat(Steering.next(Steering.Phase.CRUISE, at(0, 120, 0), at(500, 64, 0), 120, wall))
                    .isEqualTo(Steering.Phase.CLIMB);
        }

        @Test
        @DisplayName("a wall ahead with a ceiling above does not become a climb into the ceiling")
        void aWallUnderARoofDoesNot() {
            Surroundings boxedIn = new Surroundings(false, true, false, false, 64);
            assertThat(Steering.next(Steering.Phase.CRUISE, at(0, 120, 0), at(500, 64, 0), 120, boxedIn))
                    .isEqualTo(Steering.Phase.CRUISE);
        }

        @Test
        @DisplayName("being far below the line turns a crossing into a climb")
        void beingLowMeansUp() {
            assertThat(Steering.next(Steering.Phase.CRUISE, at(100, 100, 0), at(500, 64, 0), 120,
                    openAt(64))).isEqualTo(Steering.Phase.CLIMB);
        }

        /**
         * The regression that made a summoned ghast hang ten blocks over the player's head, bobbing, for as
         * long as anybody was willing to watch: the glide slope pulled it down toward the stop and the
         * "too low" reflex pushed it back to the cruise line, once per tick, for ever.
         */
        @Test
        @DisplayName("descending its glide slope is not 'too low' — that fight is what stopped it landing")
        void glidingIsNotTooLow() {
            Vector stop = at(500, 64, 0);
            // Over the stop, ten blocks up, on a cruise line of 120: exactly the state it used to hang in.
            assertThat(Steering.next(Steering.Phase.CRUISE, at(500, 74, 0), stop, 120, openAt(64)))
                    .isEqualTo(Steering.Phase.CRUISE);
            // And a climb in the same place gives up rather than hauling it back to the cruise height.
            assertThat(Steering.next(Steering.Phase.CLIMB, at(500, 74, 0), stop, 120, openAt(64)))
                    .isEqualTo(Steering.Phase.CRUISE);
        }

        @Test
        @DisplayName("but being below the glide slope itself still means climb")
        void belowTheSlopeStillClimbs() {
            // 84 blocks out the slope wants 92; at 80 it is a dozen blocks under where it should be.
            assertThat(Steering.next(Steering.Phase.CRUISE, at(416, 80, 0), at(500, 64, 0), 120, openAt(64)))
                    .isEqualTo(Steering.Phase.CLIMB);
        }

        @Test
        @DisplayName("a climb ends at the wanted altitude — or the moment the way up is blocked")
        void climbEnds() {
            assertThat(Steering.next(Steering.Phase.CLIMB, at(0, 100, 0), at(500, 64, 0), 120, openAt(64)))
                    .isEqualTo(Steering.Phase.CLIMB);
            assertThat(Steering.next(Steering.Phase.CLIMB, at(0, 119.5, 0), at(500, 64, 0), 120, openAt(64)))
                    .isEqualTo(Steering.Phase.CRUISE);
            // The regression: a ghast under an overhang used to climb for ever and then be given up on.
            assertThat(Steering.next(Steering.Phase.CLIMB, at(0, 100, 0), at(500, 64, 0), 120,
                    new Surroundings(false, false, true, true, 64))).isEqualTo(Steering.Phase.CRUISE);
        }

        @Test
        @DisplayName("the last few blocks are an approach, and reaching the stop is boarding")
        void approachThenBoarding() {
            Vector to = at(500, 64, 0);
            assertThat(Steering.next(Steering.Phase.CRUISE, at(497, 65, 0), to, 120, openAt(64)))
                    .isEqualTo(Steering.Phase.APPROACH);
            assertThat(Steering.next(Steering.Phase.APPROACH, at(500, 65, 0), to, 120, openAt(64)))
                    .isEqualTo(Steering.Phase.BOARDING);
        }

        @Test
        @DisplayName("boarding is where a leg ends; how long it lasts is not geometry")
        void boardingIsTerminal() {
            assertThat(Steering.next(Steering.Phase.BOARDING, at(0, 64, 0), at(500, 64, 0), 120,
                    openAt(64))).isEqualTo(Steering.Phase.BOARDING);
        }
    }

    @Nested
    @DisplayName("the velocity")
    class Velocities {

        @Test
        @DisplayName("a crossing never exceeds the ghast's airspeed, climbing or level")
        void airspeedIsConstant() {
            Vector level = Steering.velocity(Steering.Phase.CRUISE, at(0, 120, 0), at(500, 120, 0),
                    120, SPEED, openAt(64));
            assertThat(level.length()).isCloseTo(SPEED, CLOSE);

            Vector climbing = Steering.velocity(Steering.Phase.CRUISE, at(0, 100, 0), at(500, 64, 0),
                    120, SPEED, openAt(64));
            assertThat(climbing.length())
                    .as("a diagonal climb must not be faster than level flight")
                    .isLessThanOrEqualTo(SPEED + 1.0e-9);
            assertThat(climbing.getY()).isPositive();
            assertThat(Math.hypot(climbing.getX(), climbing.getZ()))
                    .as("and it must still be going somewhere")
                    .isPositive();
        }

        @Test
        @DisplayName("a climb still edges forward, so it cannot hover against the cliff behind it")
        void climbsForward() {
            Vector rising = Steering.velocity(Steering.Phase.CLIMB, at(0, 64, 0), at(500, 64, 0),
                    120, SPEED, openAt(64));
            assertThat(rising.getY()).isPositive();
            assertThat(rising.getX()).isPositive();
            assertThat(rising.length()).isLessThanOrEqualTo(SPEED + 1.0e-9);
        }

        @Test
        @DisplayName("with no room above, a climb becomes flying out from under whatever is there")
        void climbUnderACeilingGoesSideways() {
            Vector out = Steering.velocity(Steering.Phase.CLIMB, at(0, 64, 0), at(500, 64, 0),
                    120, SPEED, new Surroundings(false, false, true, true, 64));
            assertThat(out.getY()).isZero();
            assertThat(out.getX()).isCloseTo(SPEED, CLOSE);
        }

        @Test
        @DisplayName("a wall it can climb stops the forward motion; a wall it cannot does not")
        void wallsStopForwardMotion() {
            Vector atWall = Steering.velocity(Steering.Phase.CRUISE, at(0, 120, 0), at(500, 120, 0),
                    120, SPEED, new Surroundings(true, true, false, false, 64));
            assertThat(Math.hypot(atWall.getX(), atWall.getZ())).isZero();

            Vector wedged = Steering.velocity(Steering.Phase.CRUISE, at(0, 120, 0), at(500, 120, 0),
                    120, SPEED, new Surroundings(false, true, false, false, 64));
            assertThat(wedged.getX())
                    .as("boxed in, forward is all there is left — the stall detector ends it, not this")
                    .isCloseTo(SPEED, CLOSE);
        }

        @Test
        @DisplayName("a small obstacle with room beside it is gone round, not climbed")
        void goesRoundRatherThanOver() {
            // Flying east; the tower ahead has open air to the north and south of it.
            Surroundings tower = new Surroundings(true, true, true, true, 69);
            Vector round = Steering.velocity(Steering.Phase.CRUISE, at(0, 76, 0), at(500, 76, 0),
                    76, SPEED, tower);

            assertThat(Math.abs(round.getZ()))
                    .as("going round means going sideways")
                    .isGreaterThan(Math.abs(round.getX()));
            assertThat(round.getX())
                    .as("and still making progress along the route")
                    .isPositive();
            assertThat(round.length()).isCloseTo(SPEED, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("the same obstacle is gone round the same way every tick")
        void sidestepIsRepeatable() {
            Surroundings tower = new Surroundings(true, true, true, true, 69);
            Vector first = Steering.velocity(Steering.Phase.CRUISE, at(0, 76, 0), at(500, 76, 0),
                    76, SPEED, tower);
            Vector second = Steering.velocity(Steering.Phase.CRUISE, at(0, 76, 0), at(500, 76, 0),
                    76, SPEED, tower);
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("it gives up height more gently than it gains it")
        void descentIsGentle() {
            Vector down = Steering.velocity(Steering.Phase.CRUISE, at(0, 200, 0), at(10, 64, 0),
                    120, SPEED, openAt(64));
            assertThat(down.getY()).isGreaterThanOrEqualTo(-SPEED * Steering.DESCENT_FACTOR - 1.0e-9);
            assertThat(down.getY()).isNegative();
        }

        @Test
        @DisplayName("the final few blocks are flown slowly, and do not overshoot")
        void approachSettles() {
            Vector to = at(0, 64, 0);
            Vector settling = Steering.velocity(Steering.Phase.APPROACH, at(0, 68, 0), to,
                    120, SPEED, openAt(64));
            assertThat(settling.length()).isCloseTo(SPEED * Steering.APPROACH_SPEED_FACTOR, CLOSE);

            Vector last = Steering.velocity(Steering.Phase.APPROACH, at(0, 64.1, 0), to,
                    120, SPEED, openAt(64));
            assertThat(last.length()).isCloseTo(0.1, CLOSE);
        }

        @Test
        @DisplayName("standing on the target asks for no movement rather than dividing by zero")
        void zeroDistanceIsSafe() {
            Vector same = at(10, 64, 10);
            assertThat(Steering.velocity(Steering.Phase.APPROACH, same, same.clone(), 120, SPEED,
                    openAt(64)).length()).isZero();
            assertThat(Steering.velocity(Steering.Phase.CRUISE, same, same.clone(), 64, SPEED,
                    openAt(64)).length()).isZero();
        }

        @Test
        @DisplayName("boarding is a hover")
        void boardingHovers() {
            assertThat(Steering.velocity(Steering.Phase.BOARDING, at(0, 64, 0), at(500, 64, 0),
                    120, SPEED, openAt(64))).isEqualTo(new Vector());
        }
    }

    @Nested
    @DisplayName("turning")
    class Turning {

        @Test
        @DisplayName("a reversal is a turn, not a pivot")
        void headingChangesGradually() {
            Vector flyingEast = at(SPEED, 0, 0);
            Vector wantWest = at(-SPEED, 0, 0);
            Vector turned = Steering.smooth(flyingEast, wantWest, Steering.MAX_TURN_PER_TICK);

            assertThat(turned.getX())
                    .as("one tick of a reversal is still mostly the old heading")
                    .isPositive();
            assertThat(Math.abs(turned.getZ()))
                    .as("and has begun to swing")
                    .isPositive();
            assertThat(turned.length()).isCloseTo(SPEED, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("a turn gets there, and does not overshoot when it does")
        void turnConverges() {
            Vector heading = at(SPEED, 0, 0);
            Vector wanted = at(0, 0, SPEED);
            for (int tick = 0; tick < 40; tick++) {
                heading = Steering.smooth(heading, wanted, Steering.MAX_TURN_PER_TICK);
            }
            assertThat(heading.getZ()).isCloseTo(SPEED, Offset.offset(1.0e-6));
            assertThat(heading.getX()).isCloseTo(0, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("the speed and the climb come from what was wanted, never from the old heading")
        void onlyTheDirectionIsHeldBack() {
            Vector slow = at(0.05, 0, 0);
            Vector fast = at(0, 0.3, 1.0);
            Vector turned = Steering.smooth(slow, fast, Steering.MAX_TURN_PER_TICK);
            assertThat(turned.getY()).isEqualTo(0.3);
            assertThat(Math.hypot(turned.getX(), turned.getZ())).isCloseTo(1.0, Offset.offset(1.0e-6));
        }

        @Test
        @DisplayName("with no heading to turn from, what was wanted is what happens")
        void standingStillTurnsFreely() {
            assertThat(Steering.smooth(new Vector(), at(0, 0, SPEED), Steering.MAX_TURN_PER_TICK))
                    .isEqualTo(at(0, 0, SPEED));
            assertThat(Steering.smooth(at(0, SPEED, 0), at(0, 0, SPEED), Steering.MAX_TURN_PER_TICK))
                    .as("straight up has no horizontal heading either")
                    .isEqualTo(at(0, 0, SPEED));
        }
    }

    @Nested
    @DisplayName("what the bar says")
    class Progress {

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
        @DisplayName("the estimate is in seconds, from the distance and the ghast's own speed")
        void etaIsInSeconds() {
            assertThat(Steering.etaSeconds(240, 0.6)).isEqualTo(20);
            assertThat(Steering.etaSeconds(240, 0)).isZero();
        }
    }
}
