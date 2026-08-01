package de.raindancer.ghastlines;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import io.papermc.paper.entity.Leashable;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The flight engine: it takes off, it crosses, it comes down, and it waits at the stop.
 *
 * <h2>Why the ghast is flown by velocity and not by pathfinding</h2>
 * See {@link Steering}. The short version is that a mob's navigation is built for the dozen blocks around
 * it and a transit line is two thousand, so the AI is switched off for the duration and the flight is driven
 * one velocity per tick.
 *
 * <h2>Why every flight is a task on the ghast's own scheduler</h2>
 * {@code Entity#getScheduler()} is regionised on Folia and ordinary on Paper, it runs on the thread that
 * owns the entity — which is the only thread allowed to touch it — and it cancels itself when the entity
 * goes away. A ghast being killed mid-flight therefore needs no listener to notice; the {@code retired}
 * callback is where that is cleaned up. The listeners below cover the things the scheduler cannot see: the
 * player a summons was flying to logging out, and a ghast being removed for a reason other than dying.
 *
 * <h2>Why a flight keeps chunk tickets</h2>
 * See {@link Tickets}. Without them the ghast stops being ticked the moment it leaves the area a player has
 * loaded, and the flight freezes in mid-air.
 *
 * <h2>What is deliberately not solved here</h2>
 * A player riding a ghast still has the reins: vanilla lets whoever is in the harness steer, and their input
 * is added to what this class sets. Flying by hand while the autopilot is on therefore fights it rather than
 * being ignored, which is the right way round — the passenger can always take over, and a passenger who
 * keeps their hands off is flown to the stop.
 */
public final class FlightService implements Listener {

    /** How high above a stop the ghast comes to rest, so a player can reach the harness. */
    public static final double HOVER_ABOVE_STOP = 1.0;

    /** How often the boss bar, the chunk tickets and the ghast's remembered position are refreshed. */
    private static final int REFRESH_TICKS = 5;

    /**
     * Chunks either side of the ghast that are held loaded.
     * <p>
     * Three rather than two because {@link #look} may only read blocks inside this square — see there —
     * and two chunks was not far enough ahead to clear a mountain in time.
     */
    private static final int TICKET_RADIUS = 3;

    /** How far ahead the terrain is read. Inside the ticketed square, less a chunk for drift. */
    private static final double LOOKAHEAD = (TICKET_RADIUS - 1) * 16.0;

    /** How often a point is sampled along that stretch. A hill is wider than this. */
    private static final double SAMPLE_SPACING = 8.0;

    /** A happy ghast is four blocks tall, so this is where "above it" starts. */
    private static final int GHAST_HEIGHT = 4;

    /** Blocks of air above the ghast that count as room to climb into. */
    private static final int CLIMB_PROBE = 3;

    /** How far ahead a wall is looked for. */
    private static final double[] AHEAD_PROBES = {3.0, 6.0};

    /** The heights a wall is looked for at: the ghast's middle and its shoulders. */
    private static final int[] BODY_HEIGHTS = {1, 3};

    /**
     * Blocks per tick a ridden happy ghast actually flies, per unit of its {@code FLYING_SPEED}.
     *
     * <h2>Why this is measured and not derived</h2>
     * It was derived twice and both times it was wrong. Reading vanilla's own bytecode says
     * {@code HappyGhast.travel} passes {@code flying_speed * 5 / 3} to {@code travelFlying}, which adds it to
     * the velocity and scales the result by an air drag of {@code 0.91} — giving a terminal speed of
     * {@code a * 5/3 / (1 - 0.91)}, or about 18 blocks a second for a stock ghast. A real ridden happy ghast,
     * measured on a real server by sitting in the harness and holding forward, does **3.5 to 4**. Something
     * between the rider's input and that arithmetic scales it down by a factor of five, and rather than keep
     * guessing at which term it is, this is the number that was observed.
     *
     * <p>{@code 3.8} blocks per tick per unit of the attribute puts a stock ghast — {@code flying_speed 0.05}
     * — at {@code 0.19} blocks a tick, which is 3.8 blocks a second: what one feels like. Kept as a ratio
     * against the attribute rather than as a flat speed so that a ghast carrying a speed modifier still flies
     * faster, which is the whole reason for reading the attribute at all.
     *
     * <p>Happy ghasts are slow. That is the animal, and a transit network made of them is a slow network; a
     * server that wants otherwise has {@code ghasts.speed-percent}.
     */
    private static final double BLOCKS_PER_TICK_PER_FLYING_SPEED = 3.8;

    /** Used only if a happy ghast turns up without the attribute registered. Vanilla's own value. */
    private static final double FALLBACK_FLYING_SPEED = 0.05;

    /** How long a diverted ghast is given to come down before it is left where it is. */
    private static final int SETTLE_TIMEOUT_TICKS = 20 * TransitOptions.TICKS_PER_SECOND;

    /**
     * Columns tried when looking for somewhere to set down, nearest first.
     * <p>
     * Straight down, then the four sides at four blocks — a happy ghast's own width, so the ring is the next
     * place it could actually fit — then the same again at eight.
     */
    private static final int[][] SETTLE_OFFSETS = {
            {0, 0},
            {4, 0}, {-4, 0}, {0, 4}, {0, -4},
            {8, 0}, {-8, 0}, {0, 8}, {0, -8},
            {6, 6}, {-6, 6}, {6, -6}, {-6, -6}};

    /** Floors that are solid and still no place to leave somebody's animal. */
    private static final Set<Material> DANGEROUS_FLOOR = Set.of(
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE, Material.WITHER_ROSE);

    private final GhastLines plugin;
    private final Tickets tickets;

    /** Flights in progress, keyed by the ghast: one ghast can only be going to one place. */
    private final Map<UUID, Flight> flying = new ConcurrentHashMap<>();

    /** When each player last had a ghast come to them, for the summon cooldown. */
    private final Map<UUID, Long> lastSummon = new ConcurrentHashMap<>();

    public FlightService(GhastLines plugin) {
        this.plugin = plugin;
        this.tickets = new Tickets(plugin);
        this.standby = new Standby(plugin, tickets);
        this.standby.start();
    }

    /**
     * The moorings that keep claimed ghasts loaded when nothing is flying them.
     * <p>
     * Built here rather than in the plugin class because there are two of those — one for the module, one for
     * the standalone jar — and a seam that has to be soldered twice is a seam that will one day be soldered
     * once. It shares the flights' {@link Tickets} deliberately: two counters over one set of chunk tickets
     * would take each other's chunks away.
     */
    private final Standby standby;

    // ------------------------------------------------------------------ what is in the air

    /**
     * Every flight in progress.
     * <p>
     * Read from whichever thread asked — a status command runs wherever the sender is — while each flight is
     * written by the region thread that owns its ghast. The numbers in a {@link Flight} are ints and doubles
     * and this is a status display, so a value that is one tick stale is not a problem worth a lock for.
     */
    public List<Flight> active() {
        return List.copyOf(flying.values());
    }

    public Optional<Flight> flightOf(UUID ghast) {
        return Optional.ofNullable(flying.get(ghast));
    }

    public boolean isFlying(UUID ghast) {
        return flying.containsKey(ghast);
    }

    /** Seconds before this player may summon again, or 0. */
    public long summonCooldownRemaining(Player player) {
        TransitOptions options = plugin.options();
        if (!options.hasSummonCooldown() || player.hasPermission(Permissions.UNLIMITED)) {
            return 0;
        }
        Long last = lastSummon.get(player.getUniqueId());
        if (last == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, options.summonCooldownSeconds() - elapsed);
    }

    // ------------------------------------------------------------------ starting a flight

    /**
     * Brings a player's ghast to them, wherever they are.
     * <p>
     * Every refusal is answered here rather than reported upwards: a command that does nothing and says
     * nothing reads as a broken plugin, and a player will type it again.
     */
    public void summon(Player who, GhastClaim claim) {
        long wait = summonCooldownRemaining(who);
        if (wait > 0) {
            Text.tell(who, Text.warn("Another <seconds>s before you can call a ghast again.",
                    Text.num("seconds", wait)));
            return;
        }
        if (busy(who, claim)) {
            return;
        }
        Text.tell(who, Text.info("Calling <ghast> …", Text.part("ghast", nameOf(claim))));
        withGhast(who, claim, ghast -> {
            Location target = who.getLocation();
            if (!canReach(who, ghast, target, "come to you")) {
                return;
            }
            lastSummon.put(who.getUniqueId(), System.currentTimeMillis());
            depart(new Flight(claim.ghast(), claim.owner(), Flight.Purpose.SUMMON,
                    List.of(Leg.following(who)), false, null, who.getUniqueId(),
                    ghast.getWorld().getName(), System.currentTimeMillis()), ghast);
        });
    }

    /** Sends a ghast — and whoever is aboard it — to one place. */
    public void send(Player who, GhastClaim claim, Destination destination) {
        if (busy(who, claim)) {
            return;
        }
        if (!destination.isReachable()) {
            Text.tell(who, Text.error("<where> is in <world>, which is not loaded right now.",
                    Text.arg("where", destination.label()), Text.arg("world", destination.world())));
            return;
        }
        withGhast(who, claim, ghast -> {
            Location target = destination.location();
            if (target == null || !canReach(who, ghast, target, "fly there")) {
                return;
            }
            depart(new Flight(claim.ghast(), claim.owner(), Flight.Purpose.TRANSFER,
                    List.of(Leg.to(destination)), false, null, who.getUniqueId(),
                    ghast.getWorld().getName(), System.currentTimeMillis()), ghast);
        });
    }

    /**
     * Puts a ghast into service on a line.
     * <p>
     * The stops are resolved here, at departure, and not when the route was written: a route names its stops
     * and they get moved, shared and deleted in between. This is also the only moment a missing stop can be
     * reported to somebody who can do something about it.
     */
    public void runRoute(Player who, GhastClaim claim, Route route) {
        if (busy(who, claim)) {
            return;
        }
        if (!route.isFlyable()) {
            Text.tell(who, Text.error("'<route>' needs at least <n> stops before it can be flown.",
                    Text.arg("route", route.name()), Text.num("n", Route.MINIMUM_STOPS)));
            return;
        }
        List<String> missing = route.missingStops(
                name -> plugin.store().findStop(route.owner(), name).isPresent());
        if (!missing.isEmpty()) {
            Text.tell(who, Text.error("'<route>' calls at <list>, which no longer exist.",
                    Text.arg("route", route.name()), Text.arg("list", String.join(", ", missing))));
            return;
        }

        List<Leg> legs = new ArrayList<>();
        Set<String> worlds = new HashSet<>();
        for (String name : route.journey()) {
            Stop stop = plugin.store().findStop(route.owner(), name).orElseThrow();
            legs.add(Leg.to(Destination.fromStop(stop, stop.name(), Destinations.KIND_OWN)));
            worlds.add(stop.world());
        }
        if (worlds.size() > 1 && !plugin.options().allowCrossWorld()) {
            Text.tell(who, Text.error("'<route>' has stops in more than one world, and flights between "
                    + "worlds are switched off here.", Text.arg("route", route.name())));
            return;
        }

        withGhast(who, claim, ghast -> depart(new Flight(claim.ghast(), claim.owner(),
                Flight.Purpose.ROUTE, legs, route.loop(), route.name(), who.getUniqueId(),
                ghast.getWorld().getName(), System.currentTimeMillis()), ghast));
    }

    /** True — and says so — when this ghast is already busy. */
    private boolean busy(Player who, GhastClaim claim) {
        Flight already = flying.get(claim.ghast());
        if (already == null) {
            return false;
        }
        Text.tell(who, Text.warn("<ghast> is already flying — <where>. Use /ghast recall to stop it.",
                Text.part("ghast", nameOf(claim)), Text.arg("where", already.heading())));
        return true;
    }

    /**
     * Finds the ghast, loading the chunk it was parked in if that is what it takes, and answers the player
     * when it cannot be found at all.
     */
    private void withGhast(Player who, GhastClaim claim, java.util.function.Consumer<HappyGhast> then) {
        plugin.claims().locate(claim, then, () -> Text.tell(who, Text.error(
                "<ghast> is not there any more — it was last seen in <world> at <where>. "
                        + "Use /ghast release to forget it.",
                Text.part("ghast", nameOf(claim)), Text.arg("world", claim.world()),
                Text.arg("where", claim.coordinates()))));
    }

    /** The two things that can make a destination impossible before anything has taken off. */
    private boolean canReach(Player who, HappyGhast ghast, Location target, String what) {
        TransitOptions options = plugin.options();
        World from = ghast.getWorld();
        World to = target.getWorld();
        if (to == null) {
            Text.tell(who, Text.error("That place has nowhere to go to."));
            return false;
        }
        if (!from.equals(to)) {
            if (!options.allowCrossWorld()) {
                Text.tell(who, Text.error("<ghast> is in <world>, and flights between worlds are "
                                + "switched off here.",
                        Text.part("ghast", Component.text("your ghast")), Text.arg("world", from.getName())));
                return false;
            }
            // Nothing else to check: a flight between worlds starts with the hop, so the distance inside
            // the old world is not the distance that will be flown.
            return true;
        }
        double distance = ghast.getLocation().distance(target);
        if (distance > options.maxDistance()) {
            Text.tell(who, Text.error("Too far to <what>: <distance> blocks, and the limit is <limit>.",
                    Text.arg("what", what), Text.num("distance", Math.round(distance)),
                    Text.num("limit", options.maxDistance())));
            return false;
        }
        return true;
    }

    /**
     * Takes off.
     * <p>
     * The AI goes off here and back on in {@link #land}: a happy ghast left to its own devices drifts, and a
     * drifting ghast fights every velocity this class sets.
     */
    private void depart(Flight flight, HappyGhast ghast) {
        ghast.setAware(false);
        flying.put(flight.ghast(), flight);
        beginLeg(flight, ghast);

        ScheduledTask task = ghast.getScheduler().runAtFixedRate(plugin,
                scheduled -> tick(flight, ghast, scheduled),
                () -> end(flight, "Its ghast is gone."), 1L, 1L);
        if (task == null) {
            // The entity was retired between being found and being scheduled. Nothing is flying.
            end(flight, null);
            return;
        }
        flight.task(task);
    }

    private void beginLeg(Flight flight, HappyGhast ghast) {
        Location target = flight.currentLeg().target();
        flight.beginLeg(target == null ? 0 : ghast.getLocation().distance(target));
        flight.resetStall();
        if (target == null) {
            flight.phase(Steering.Phase.BOARDING);
            return;
        }
        Surroundings around = look(ghast, target);
        flight.phase(Steering.initial(ghast.getLocation().toVector(), aimAt(target),
                cruiseHeightFor(ghast, target, around)));
    }

    // ------------------------------------------------------------------ the flight itself

    private void tick(Flight flight, HappyGhast ghast, ScheduledTask scheduled) {
        if (flight.isFinished() || !ghast.isValid()) {
            scheduled.cancel();
            end(flight, null);
            return;
        }
        Leg leg = flight.currentLeg();
        Location target = leg.target();
        if (target == null || target.getWorld() == null) {
            scheduled.cancel();
            end(flight, leg.isFollowing()
                    ? "The player it was coming to is no longer here."
                    : "Where it was going has stopped existing.");
            return;
        }

        if (!target.getWorld().equals(ghast.getWorld()) && !hop(flight, ghast, target)) {
            scheduled.cancel();
            end(flight, "It could not cross into " + target.getWorld().getName() + ".");
            setDown(flight, ghast);
            return;
        }

        Vector position = ghast.getLocation().toVector();
        Surroundings around = look(ghast, target);

        // A detour is flown before the leg is: the waypoints are how the ghast gets out of wherever it could
        // not fly straight out of, and until they are behind it the stop is not the thing to aim at.
        if (flight.isDetouring() && flyDetour(flight, ghast, position, around)) {
            refresh(flight, ghast, false);
            return;
        }

        Vector aim = aimAt(target);
        double cruiseY = cruiseHeightFor(ghast, target, around);

        if (flight.isBoarding()) {
            // Set the cargo down while the ghast waits: it has arrived, and a boat held three blocks in the
            // air at a stop is a boat nobody can get out of.
            carry(flight, ghast, false);
            List<UUID> riders = passengers(ghast);
            if (riders.isEmpty()) {
                hover(ghast);
            }
            // A ghast that is being held still cannot be flown, and a player who has just climbed into the
            // harness is trying to fly it. So the moment somebody is aboard the engine stops holding it:
            // a summons is over — it did what it was called for — and a route lets go of the controls for
            // the rest of its wait rather than pinning whoever got on.
            if (!riders.isEmpty() && flight.purpose() == Flight.Purpose.SUMMON) {
                scheduled.cancel();
                end(flight, null);
                aboardOnly(flight, ghast, Text.success("<ghast> is yours — fly it.",
                        Text.part("ghast", liveName(ghast, flight))));
                return;
            }
            if (flight.boardingTick()) {
                leaveStop(flight, ghast, scheduled);
            }
            refresh(flight, ghast, false);
            return;
        }


        Steering.Phase next = Steering.next(flight.phase(), position, aim, cruiseY, around);
        flight.phase(next);
        flight.blocksLeft(position.distance(aim));

        if (next == Steering.Phase.BOARDING) {
            arrive(flight, ghast);
            refresh(flight, ghast, true);
            return;
        }

        double airspeed = airspeed(ghast);
        flight.blocksPerTick(airspeed);
        Vector velocity = Steering.velocity(next, position, aim, cruiseY, airspeed, around);
        fly(ghast, velocity);
        carry(flight, ghast, true);

        if (flight.stalled(position)) {
            stuck(flight, ghast, position, scheduled);
            return;
        }
        refresh(flight, ghast, false);
    }

    /**
     * What happens when the ghast has stopped getting anywhere: look for a way out, and only give up if there
     * is not one.
     *
     * <h2>Why the search happens here and not before every flight</h2>
     * Because almost no flight needs it. Open sky is the normal case, the steering handles hills and walls on
     * its own, and searching for a route around something that is not there would cost every flight for the
     * sake of the rare one that is under a cliff. Being stuck is the signal that the cheap answer has run
     * out — so that is when the expensive one runs, at most {@link #MAX_DETOURS} times per leg so a ghast in a
     * maze cannot search for ever.
     */
    private void stuck(Flight flight, HappyGhast ghast, Vector position, ScheduledTask scheduled) {
        if (flight.detourCount() < MAX_DETOURS) {
            List<Vector> way = Escape.route(freeSpaceAround(ghast), position);
            if (!way.isEmpty()) {
                flight.detour(way);
                aboardOnly(flight, ghast, Text.info("Finding a way out …"));
                return;
            }
        }
        scheduled.cancel();
        end(flight, "It could not find a way through.");
        setDown(flight, ghast);
    }

    /**
     * Flies the next waypoint of a detour. Answers false when the detour is over and the leg can resume.
     * <p>
     * Waypoints are flown as approaches rather than as cruises: they are a few blocks apart, inside terrain,
     * and there is no cruise line to hold — the whole point is to go exactly where the search said.
     */
    private boolean flyDetour(Flight flight, HappyGhast ghast, Vector position, Surroundings around) {
        Vector waypoint = flight.nextWaypoint();
        if (waypoint == null) {
            return false;
        }
        if (position.distance(waypoint) <= WAYPOINT_RADIUS) {
            flight.reachedWaypoint();
            return flight.isDetouring();
        }
        if (flight.stalled(position)) {
            // Stuck on the way out as well. The search was wrong, or something moved; drop it and let the
            // ordinary stall handling have another go from where the ghast now is.
            flight.abandonDetour();
            return false;
        }
        double airspeed = airspeed(ghast);
        flight.blocksPerTick(airspeed);
        fly(ghast, Steering.velocity(Steering.Phase.APPROACH, position, waypoint, waypoint.getY(),
                airspeed / Steering.APPROACH_SPEED_FACTOR, around));
        return true;
    }

    /**
     * Sets the velocity, turning rather than pivoting, and points the ghast where it is going.
     * <p>
     * The current velocity is read back off the entity rather than remembered: drag has already been applied
     * to it, so it is what the ghast is actually doing, which is the only honest thing to turn away from.
     */
    private static void fly(HappyGhast ghast, Vector wanted) {
        Vector turned = Steering.smooth(ghast.getVelocity(), wanted, Steering.MAX_TURN_PER_TICK);
        ghast.setVelocity(turned);
        face(ghast, turned);
    }

    /**
     * The world around the ghast, as the escape search wants it.
     * <p>
     * Bounded to the chunks this flight is keeping loaded, for the reason given on {@link #look}: inside that
     * square a block read is both loaded and this thread's business. Anything outside it is reported as solid,
     * so the search will not route the ghast through a wall it has not actually looked at.
     */
    private Escape.Space freeSpaceAround(HappyGhast ghast) {
        World world = ghast.getWorld();
        Location where = ghast.getLocation();
        int limit = TICKET_RADIUS * 16 - 8;
        return (x, y, z) -> {
            if (Math.abs(x - where.getBlockX()) > limit || Math.abs(z - where.getBlockZ()) > limit) {
                return false;
            }
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
                return false;
            }
            return !world.getBlockAt(x, y, z).getType().isSolid();
        };
    }

    /**
     * Brings whatever is on the ghast's leads along with it.
     *
     * <h2>Why the plugin has to do this at all</h2>
     * Leash a boat to a happy ghast, get in, and the ghast flies off without you. Vanilla's rope is elastic
     * within ten blocks and snaps at sixteen — {@code HappyGhast.leashElasticDistance} and
     * {@code leashSnapDistance}, read out of the server's own class — and that elastic pull is not enough to
     * lift a boat with a passenger in it off the ground. The rope stays attached and the boat stays where it
     * was, which is the whole of "a happy ghast cannot carry you in a boat".
     *
     * <p>So the cargo is carried rather than dragged, and {@link Tow} is how: gravity off, hanging under the
     * ghast on a spring rather than pinned to a point, keeping its own momentum so it swings and trails, and
     * turned into the direction it is being pulled. Gravity comes back on at a stop and when the flight ends
     * — {@link #releaseCargo} is called from {@link #end}, on every ending there is, for the same reason the
     * chunk tickets are.
     *
     * @param airborne whether the ghast is flying; false at a stop, where the cargo should settle
     */
    private void carry(Flight flight, HappyGhast ghast, boolean airborne) {
        if ((ghast.getTicksLived() % CARGO_RESCAN_TICKS) == 0) {
            rescanCargo(flight, ghast);
        }
        if (flight.cargo().isEmpty()) {
            return;
        }
        Vector carrier = ghast.getVelocity();
        Vector anchor = Tow.anchor(ghast.getLocation().toVector());

        // The anchor hangs three blocks under the ghast, and on a descent that puts it under the ground —
        // where the spring would haul the boat straight into the floor, which is exactly what it did. So the
        // rope shortens as the ghast comes down: the load is never asked to be below standing height.
        World world = ghast.getWorld();
        double floor = world.getHighestBlockYAt(anchor.getBlockX(), anchor.getBlockZ()) + FLOOR_CLEARANCE;
        anchor.setY(Math.max(anchor.getY(), floor));

        // No room left to hang at all: the ghast is at ground level, so the load is simply put down rather
        // than held somewhere it does not fit.
        boolean roomToHang = ghast.getLocation().getY() - anchor.getY() >= MINIMUM_HANG;

        for (UUID id : List.copyOf(flight.cargo())) {
            Entity cargo = Bukkit.getEntity(id);
            if (cargo == null || !cargo.isValid()) {
                // Neither of these means gone. {@code isValid} is false for an entity whose chunk is not
                // loaded this instant, and unresolvable is the same thing seen from the other side — a load
                // between hands for a tick. Both used to strike it off the flight's books, and a ghast does
                // not go back for what it has forgotten: that is a boat left hanging in the air, gravity
                // still off, while the flight carries on without it. Only the lead decides — see
                // {@link #rescanCargo}.
                continue;
            }
            if (!airborne || !roomToHang) {
                cargo.setGravity(true);
                continue;
            }
            cargo.setGravity(false);

            Vector where = cargo.getLocation().toVector();
            if (Tow.tooFar(where, anchor)) {
                // Further behind than the rope would tolerate. Passengers are retained explicitly, because
                // the default is to drop them, and dropping somebody mid-flight is the worst outcome here.
                cargo.teleportAsync(anchor.toLocation(cargo.getWorld()),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
                continue;
            }

            Vector velocity = Tow.velocity(where, anchor, cargo.getVelocity(), carrier);
            // Never pushed downward into whatever it is standing on: the spring is allowed to hold the load
            // up and to pull it along, but the ground decides how low it goes.
            double ownFloor = world.getHighestBlockYAt(cargo.getLocation()) + FLOOR_CLEARANCE;
            if (cargo.getLocation().getY() <= ownFloor && velocity.getY() < 0) {
                velocity.setY(0);
            }
            cargo.setVelocity(velocity);
            // The load points along the way it is being pulled, and turns into it rather than snapping —
            // without this the boat kept whatever heading it started with while the ghast flew off sideways.
            aim(cargo, Tow.yaw(cargo.getLocation().getYaw(), velocity));
        }
    }

    /**
     * How far off the heading a ridden load may be before it is turned by force, in degrees.
     * <p>
     * Wider than one tick of {@link Tow#MAX_TURN_PER_TICK}, so a load already pointing where it is going is
     * left alone entirely and a straight leg costs nothing.
     */
    private static final float YAW_TOLERANCE = 3.0f;

    /**
     * Points the load along the tow.
     *
     * <h2>Why a passenger changes the method</h2>
     * A vehicle's rider is authoritative for it: their client simulates the boat and sends its position
     * <em>and its rotation</em> up every tick, and the server takes them. So {@link Entity#setRotation} on a
     * boat with somebody in it is overwritten before anybody sees it — which is exactly the bug: the heading
     * froze at whatever it was the moment a player sat down, and the ghast flew off around it. An empty boat
     * has no such owner and turns perfectly well on its own.
     *
     * <p>A teleport is the one thing a rider's client does accept, because it arrives as a vehicle move it
     * did not predict. It is sent to the position the boat is already at — the position that client itself
     * reported — so nothing moves and only the heading changes, and only when the heading is actually wrong.
     * On a straight leg that is never.
     */
    private void aim(Entity cargo, float yaw) {
        Location where = cargo.getLocation();
        if (cargo.getPassengers().isEmpty()) {
            cargo.setRotation(yaw, where.getPitch());
            return;
        }
        if (Math.abs(Tow.wrapDegrees(yaw - where.getYaw())) < YAW_TOLERANCE) {
            return;
        }
        where.setYaw(yaw);
        cargo.teleportAsync(where, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /**
     * Looks for what is tied to the ghast now — a boat can be hitched on at a stop halfway through a line.
     *
     * <h2>Proximity finds cargo; the lead is what keeps it</h2>
     * This used to be one search doing both jobs, and the second job it did badly: anything that had fallen
     * outside the twenty-block sweep was struck off, and since a ghast does not go back for it, struck off
     * meant abandoned. A load that dropped behind for a moment — a passenger logging out, a chunk arriving
     * late — was left hanging wherever it happened to be while the flight carried on without it. So the
     * sweep only adds now. What is already on the books stays there as long as the lead is still tied, and
     * being far behind is exactly the situation {@link Tow#tooFar} exists to recover from.
     */
    private void rescanCargo(Flight flight, HappyGhast ghast) {
        Set<UUID> found = new HashSet<>();
        for (Entity nearby : ghast.getNearbyEntities(CARGO_SEARCH, CARGO_SEARCH, CARGO_SEARCH)) {
            if (tiedTo(nearby, ghast)) {
                found.add(nearby.getUniqueId());
            }
        }
        // Anything that has been untied gets its gravity back before it is forgotten about.
        for (UUID id : List.copyOf(flight.cargo())) {
            if (found.contains(id)) {
                continue;
            }
            Entity gone = Bukkit.getEntity(id);
            if (gone == null) {
                // Out of reach rather than untied — the search above only sees what is loaded near the
                // ghast. Kept on the books, because dropping it here drops the one record of a load that
                // is owed its gravity back.
                continue;
            }
            if (tiedTo(gone, ghast)) {
                // Behind the sweep but still on the lead: still cargo, and it gets pulled back in.
                continue;
            }
            gone.setGravity(true);
            flight.cargo().remove(id);
        }
        flight.cargo().addAll(found);
    }

    /** Whether that lead is tied to this ghast. False for anything that cannot be on a lead at all. */
    private static boolean tiedTo(Entity entity, HappyGhast ghast) {
        if (!(entity instanceof Leashable leashable) || !leashable.isLeashed()) {
            return false;
        }
        try {
            return ghast.equals(leashable.getLeashHolder());
        } catch (IllegalStateException noLongerLeashed) {
            // Documented to throw when the leash has gone between the two calls above.
            return false;
        }
    }

    /** Gives every carried entity its gravity back. Called from the one exit, like the chunk tickets. */
    private void releaseCargo(Flight flight) {
        for (UUID id : List.copyOf(flight.cargo())) {
            Entity cargo = Bukkit.getEntity(id);
            if (cargo != null) {
                cargo.setGravity(true);
            }
        }
        flight.cargo().clear();
    }

    /** Holds the ghast still at a stop. A hovering happy ghast needs no help staying up. */
    private void hover(HappyGhast ghast) {
        ghast.setVelocity(new Vector());
    }

    /** How close counts as having reached a detour waypoint. Wider than a stop: it is a gap, not a landing. */
    private static final double WAYPOINT_RADIUS = 2.5;

    /** How many times one leg may be talked out of somewhere before the flight is given up on. */
    private static final int MAX_DETOURS = 4;

    /** How far down its heading the ghast looks, so its head and body agree about where "forward" is. */
    private static final double LOOK_AHEAD_BLOCKS = 12.0;

    /** How far above the ground the load is kept, so a descent cannot press it into the floor. */
    private static final double FLOOR_CLEARANCE = 1.2;

    /** Below this much room under the ghast, the load is set down instead of hung. */
    private static final double MINIMUM_HANG = 1.5;

    /** How often the ghast is asked what is tied to it. A boat can be hitched on at any stop. */
    private static final int CARGO_RESCAN_TICKS = 20;

    /** How far around the ghast that question is asked. Vanilla's rope cannot be longer than this. */
    private static final double CARGO_SEARCH = 20.0;

    private void arrive(Flight flight, HappyGhast ghast) {
        if (passengers(ghast).isEmpty()) {
            hover(ghast);
        }
        int seconds = plugin.options().boardingSeconds();
        flight.startBoarding(seconds);
        aboardOnly(flight, ghast, Text.success(
                "<ghast> has arrived at <where> — <seconds>s to get on or off.",
                Text.part("ghast", liveName(ghast, flight)),
                Text.arg("where", flight.heading()), Text.num("seconds", seconds)));
    }

    private void leaveStop(Flight flight, HappyGhast ghast, ScheduledTask scheduled) {
        if (flight.purpose() == Flight.Purpose.SUMMON || !flight.advanceLeg()) {
            scheduled.cancel();
            end(flight, null);
            aboardOnly(flight, ghast, Text.success("<ghast> is waiting at <where>.",
                    Text.part("ghast", liveName(ghast, flight)), Text.arg("where", flight.heading())));
            return;
        }
        beginLeg(flight, ghast);
        aboardOnly(flight, ghast, Text.info("Departing for <where>.",
                Text.arg("where", flight.heading())));
    }

    /**
     * Crosses into another world, once, at the start of a leg that ends in one.
     *
     * <h2>Why this is a teleport and not a flight</h2>
     * There is no route between two worlds for a ghast to fly: a nether portal is not something a four-block
     * animal steers itself through, and the End has no doors at all. So a cross-world leg begins with the
     * ghast — and everyone aboard it — appearing high above the destination, and the rest of the leg is flown
     * down normally. That is the whole reason {@code allow-cross-world} is off by default: it is the one part
     * of this plugin where the ghast does not really make the journey, and a server that would rather it
     * always did should say no.
     */
    private boolean hop(Flight flight, HappyGhast ghast, Location target) {
        if (!plugin.options().allowCrossWorld()) {
            return false;
        }
        World world = target.getWorld();
        double height = Math.min(target.getY() + plugin.options().clearance(),
                world.getMaxHeight() - Steering.CEILING_MARGIN);
        Location above = new Location(world, target.getX(), height, target.getZ());
        // Passengers are retained explicitly: the default is to drop them, and dropping somebody from cruise
        // altitude over another world is the single worst thing this plugin could do to a player.
        ghast.teleportAsync(above, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        // Whatever is on the leads comes too, or the rope snaps across a world boundary and the boat is left
        // in the world the ghast has just left.
        for (UUID id : List.copyOf(flight.cargo())) {
            Entity cargo = Bukkit.getEntity(id);
            if (cargo != null) {
                cargo.teleportAsync(above.clone().subtract(0, Tow.HANG_BELOW, 0),
                        TeleportFlag.EntityState.RETAIN_PASSENGERS);
            }
        }
        releaseTickets(flight);
        flight.resetStall();
        flight.phase(Steering.Phase.APPROACH);
        return true;
    }

    // ------------------------------------------------------------------ progress, tickets, tidying up

    /**
     * Everything that does not have to happen every single tick.
     *
     * <h2>Why a finished flight is refused here</h2>
     * This is the orphan-boss-bar bug, and it left a bar on somebody's screen for every flight they ever
     * completed. The order was: the boarding wait ends, {@code leaveStop} finishes the journey, {@link #end}
     * takes the bar down and forgets the flight — and then this line ran, a few statements later in the same
     * tick, and built a <em>new</em> bar for a flight that no longer exists. Nothing was left to ever hide it
     * again, so it hung there, frozen on "Boarding", one per completed trip.
     */
    private void refresh(Flight flight, HappyGhast ghast, boolean force) {
        if (flight.isFinished()) {
            return;
        }
        if (!force && (ghast.getTicksLived() % REFRESH_TICKS) != 0) {
            return;
        }
        holdChunksAround(flight, ghast);
        plugin.store().claimOf(flight.ghast())
                .ifPresent(claim -> plugin.claims().sawAt(claim, ghast));
        showProgress(flight, ghast);
    }

    private void holdChunksAround(Flight flight, HappyGhast ghast) {
        int centreX = ghast.getLocation().getBlockX() >> 4;
        int centreZ = ghast.getLocation().getBlockZ() >> 4;
        Set<Long> want = new HashSet<>();
        for (int x = -TICKET_RADIUS; x <= TICKET_RADIUS; x++) {
            for (int z = -TICKET_RADIUS; z <= TICKET_RADIUS; z++) {
                want.add(Tickets.key(centreX + x, centreZ + z));
            }
        }
        tickets.reconcile(ghast.getWorld(), want, flight.tickets());
    }

    /**
     * Puts the flight on a boss bar for everybody who has a stake in it.
     * <p>
     * A boss bar because progress is a bar: it is at the top of the screen, it is legible while flying, and
     * it does not scroll away. The action bar is the fallback for a server that would rather not have one,
     * and it is where the text version goes.
     */
    private void showProgress(Flight flight, HappyGhast ghast) {
        if (flight.isFinished()) {
            // Belt as well as braces: nothing may create a bar for a flight that has ended. See refresh().
            return;
        }
        List<UUID> interested = flight.interested(passengers(ghast));
        Component line = flight.describe(liveName(ghast, flight));
        float progress = flight.progress();

        if (!plugin.options().bossBar()) {
            hideBar(flight);
            Component text = Text.raw("<" + Text.MUTED + "><bar> ",
                    Text.part("bar", bar(progress))).append(line);
            interested.stream().map(Bukkit::getPlayer).filter(java.util.Objects::nonNull)
                    .forEach(player -> Text.status(player, text));
            return;
        }

        BossBar shown = flight.bar();
        if (shown == null) {
            shown = BossBar.bossBar(line, progress, colourOf(flight), BossBar.Overlay.PROGRESS);
            flight.bar(shown);
        } else {
            shown.name(line);
            shown.progress(progress);
            shown.color(colourOf(flight));
        }
        for (UUID id : interested) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && flight.watchers().add(id)) {
                player.showBossBar(shown);
            }
        }
        // Somebody who got off, or logged out, keeps a bar that never moves again otherwise.
        List<UUID> gone = flight.watchers().stream().filter(id -> !interested.contains(id)).toList();
        for (UUID id : gone) {
            flight.watchers().remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.hideBossBar(shown);
            }
        }
    }

    /**
     * The text form of the bar, for the action-bar fallback and the status board.
     * <p>
     * The empty half is a different character and not just a dimmer one, so the bar still reads where the
     * colour does not survive: a server log, a Discord bridge, a colour-blind player.
     */
    public static Component bar(float progress) {
        int filled = Math.round(progress * 10);
        return Text.raw("<" + Text.SKY + ">" + "▉".repeat(filled)
                + "<" + Text.MUTED + ">" + "░".repeat(10 - filled)
                + " <" + Text.TEXT + ">" + Math.round(progress * 100) + "%");
    }

    private static BossBar.Color colourOf(Flight flight) {
        return switch (flight.phase()) {
            case BOARDING -> BossBar.Color.GREEN;
            case CRUISE -> BossBar.Color.BLUE;
            case CLIMB, APPROACH -> BossBar.Color.YELLOW;
        };
    }

    private void hideBar(Flight flight) {
        BossBar shown = flight.bar();
        if (shown == null) {
            return;
        }
        for (UUID id : List.copyOf(flight.watchers())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.hideBossBar(shown);
            }
        }
        flight.watchers().clear();
        flight.bar(null);
    }

    // ------------------------------------------------------------------ ending

    /** Stops a flight and says why. Safe to call for a ghast that is not flying. */
    public boolean cancelFor(UUID ghast, String why) {
        Flight flight = flying.get(ghast);
        if (flight == null) {
            return false;
        }
        if (flight.task() != null) {
            flight.task().cancel();
        }
        end(flight, why);
        return true;
    }

    /**
     * The one exit. Called from every ending there is — arrival, refusal, cancellation, the ghast dying and
     * the plugin shutting down — because each of them has to give the chunk tickets back and put the AI on.
     */
    private void end(Flight flight, String why) {
        // The bar comes down first, before anything here can return early. It used to be the last line, and
        // a flight that ended twice — a cancel racing the entity scheduler's retired callback — left a boss
        // bar on somebody's screen for the rest of the session, showing a flight that had stopped.
        hideBar(flight);

        if (flying.remove(flight.ghast()) == null) {
            // Already ended: the entity scheduler's retired callback and an explicit cancel can both arrive.
            return;
        }
        flight.finish();
        // The cargo first, and the chunks it is in second. The other way round, the tickets went back before
        // anything had given the load its gravity again — and a load whose chunk has just been let go cannot
        // be found to be given anything. That is a boat left hanging in the air for good, with the ghast
        // still tied to it and pulling.
        releaseCargo(flight);
        releaseTickets(flight);
        land(flight);
        // Where it stopped is worth a file, once. During the flight the position is only kept in memory —
        // see Claims#sawAt — so this is what makes the ghast findable again without a chunk having to
        // unload first.
        plugin.store().flushSeen();
        if (why != null) {
            announceTo(flight, List.of(), Text.warn("<why>", Text.arg("why", why)));
        }
    }

    private void releaseTickets(Flight flight) {
        World world = Bukkit.getWorld(flight.world());
        tickets.releaseAll(world, flight.tickets());
    }

    /**
     * Hands the ghast back to itself: its own AI on, and not still carrying the last velocity we gave it.
     *
     * <h2>Why the AI comes back on rather than staying off</h2>
     * A ghast frozen where it landed would be easier to find again — and it would be a statue. A happy
     * ghast that bobs and drifts is what the animal looks like, and a server full of motionless ones parked
     * at stops looks like a bug even though it would be a feature. Finding it again is solved where the
     * problem actually is instead: {@link #onEntitiesUnload} writes down where it is at the last instant
     * anybody can ask, and an entity cannot move while its chunk is unloaded, so that record is exact.
     */
    private void land(Flight flight) {
        plugin.claims().loaded(flight.ghast()).ifPresent(ghast -> {
            Runnable handBack = () -> {
                if (passengers(ghast).isEmpty()) {
                    ghast.setVelocity(new Vector());
                }
                ghast.setAware(true);
            };
            // A disabled plugin cannot schedule anything, and every flight is ended by the shutdown — so
            // on the way out this threw before the AI was ever handed back, leaving every ghast that had
            // been flying saved with its AI off. Shutdown is on the server thread, so it is done here.
            if (!plugin.isEnabled()) {
                handBack.run();
                return;
            }
            ghast.getScheduler().run(plugin, ignored -> handBack.run(), null);
        });
    }

    /**
     * Puts a ghast down somewhere sensible when its flight could not be finished.
     *
     * <h2>Why a failed flight lands instead of stopping</h2>
     * A ghast abandoned in mid-air over unfamiliar terrain is a ghast its owner has to go and look for, and
     * "it could not get through" tells them nothing about where to look. Setting it down on the ground it is
     * over, and saying exactly where, turns a failure into a diversion: the ghast is somewhere, that somewhere
     * is safe to stand, and the coordinates are in the message.
     *
     * <h2>Why this is not another flight</h2>
     * Because the flight is what just failed. This is a short descent on the ghast's own scheduler with a
     * hard time limit, holding no chunk tickets and no place in {@link #flying} — so it cannot fail in the
     * same way, cannot be recalled, and cannot leave anything behind if the chunk unloads under it.
     */
    private void setDown(Flight flight, HappyGhast ghast) {
        Location spot = safeSpotNear(ghast);
        if (spot == null) {
            announceTo(flight, passengers(ghast), Text.warn("<ghast> has stopped in the air at <where> — "
                            + "there was nowhere under it to set down.",
                    Text.part("ghast", liveName(ghast, flight)),
                    Text.arg("where", roundedCoordinates(ghast.getLocation()))));
            return;
        }
        Vector aim = new Vector(spot.getX(), spot.getY() + HOVER_ABOVE_STOP, spot.getZ());
        int[] ticksLeft = {SETTLE_TIMEOUT_TICKS};

        ghast.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!ghast.isValid() || --ticksLeft[0] <= 0) {
                scheduled.cancel();
                return;
            }
            Vector position = ghast.getLocation().toVector();
            if (position.distance(aim) <= Steering.LANDED_RADIUS) {
                scheduled.cancel();
                ghast.setVelocity(new Vector());
                ghast.setAware(true);
                announceTo(flight, passengers(ghast), Text.info("<ghast> has set down at <where>.",
                        Text.part("ghast", liveName(ghast, flight)),
                        Text.arg("where", roundedCoordinates(spot))));
                return;
            }
            ghast.setVelocity(Steering.velocity(Steering.Phase.APPROACH, position, aim, aim.getY(),
                    airspeed(ghast), Surroundings.open(spot.getY())));
        }, () -> {
        }, 1L, 1L);
    }

    /**
     * Somewhere near the ghast a four-block animal can stand.
     * <p>
     * Straight down first, because that is where it already is; then a ring of nearby columns, because the
     * ghast may have got stuck precisely <em>because</em> what is under it is a wall or a lava lake. Answers
     * null when none of them will do, which is the honest answer over an ocean of lava or the void.
     */
    private static Location safeSpotNear(HappyGhast ghast) {
        World world = ghast.getWorld();
        Location where = ghast.getLocation();
        for (int[] offset : SETTLE_OFFSETS) {
            int x = where.getBlockX() + offset[0];
            int z = where.getBlockZ() + offset[1];
            int ground = world.getHighestBlockYAt(x, z);
            if (ground <= world.getMinHeight()) {
                continue;
            }
            Material floor = world.getBlockAt(x, ground, z).getType();
            if (!floor.isSolid() || DANGEROUS_FLOOR.contains(floor)) {
                continue;
            }
            boolean roomAbove = true;
            for (int up = 1; up <= GHAST_HEIGHT + 1; up++) {
                if (world.getBlockAt(x, ground + up, z).getType().isSolid()) {
                    roomAbove = false;
                    break;
                }
            }
            if (roomAbove) {
                return new Location(world, x + 0.5, ground + 1, z + 0.5);
            }
        }
        return null;
    }

    private static String roundedCoordinates(Location where) {
        return Math.round(where.getX()) + ", " + Math.round(where.getY()) + ", " + Math.round(where.getZ());
    }

    /** Called from {@code onDisable}: a flight outliving the plugin holds chunks loaded for ever. */
    public void cancelAll() {
        standby.stop();
        for (Flight flight : List.copyOf(flying.values())) {
            if (flight.task() != null) {
                flight.task().cancel();
            }
            end(flight, null);
        }
        flying.clear();
    }

    // ------------------------------------------------------------------ talking

    /**
     * Tells the people on board, and nobody else.
     *
     * <h2>Why an owner does not hear every stop</h2>
     * A loop with four stops and an eight-second wait announces itself nine times a minute, for as long as the
     * line is running. Somebody riding it wants to know where it has stopped — that is the announcement at a bus
     * stop. Its owner, mining two thousand blocks away, wants to know that it left and would like to be told if
     * it goes wrong, and nothing else. So a stop is announced to the harness, and a departure, a failure and the
     * end of the journey go to whoever asked for it. The boss bar is there the whole time for anybody who does
     * want to watch.
     *
     * <p>The one exception is a summons, which is a flight <em>to</em> somebody: they are told it arrived,
     * because being told is the entire point of having called it.
     */
    private void aboardOnly(Flight flight, HappyGhast ghast, Component message) {
        List<UUID> riders = new ArrayList<>(passengers(ghast));
        if (flight.purpose() == Flight.Purpose.SUMMON && flight.requestedBy() != null
                && !riders.contains(flight.requestedBy())) {
            riders.add(flight.requestedBy());
        }
        for (UUID id : riders) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Text.status(player, message);
            }
        }
    }

    /** Tells everybody with a stake in the flight: the endings, and where the ghast ended up. */
    private void announceTo(Flight flight, List<UUID> passengers, Component message) {
        for (UUID id : flight.interested(passengers)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Text.tell(player, message);
            }
        }
    }

    private static List<UUID> passengers(HappyGhast ghast) {
        List<UUID> riders = new ArrayList<>();
        for (Entity passenger : ghast.getPassengers()) {
            if (passenger instanceof Player player) {
                riders.add(player.getUniqueId());
            }
        }
        return riders;
    }

    private Component nameOf(GhastClaim claim) {
        return plugin.claims().displayName(claim);
    }

    /** The ghast's name as it is right now, which is the name tag if it has one. */
    private Component liveName(HappyGhast ghast, Flight flight) {
        Component named = ghast.customName();
        if (named != null) {
            return named;
        }
        return plugin.store().claimOf(flight.ghast()).map(this::nameOf)
                .orElse(Component.text(Claims.UNNAMED));
    }

    // ------------------------------------------------------------------ geometry that needs a world

    /** The point the ghast actually aims at: just above the stop, where a player can reach the harness. */
    private static Vector aimAt(Location target) {
        return new Vector(target.getX(), target.getY() + HOVER_ABOVE_STOP, target.getZ());
    }

    /**
     * The height for this tick's crossing.
     * <p>
     * The stop's own Y stands in for the ground at the far end, which is exactly what it is — somebody
     * stood there to make the stop — and never a block read at the destination: on Folia that may belong
     * to another region, and reading a block there from this thread is not allowed. What the ghast is
     * about to fly over comes from {@link #look}.
     */
    private double cruiseHeightFor(HappyGhast ghast, Location target, Surroundings around) {
        Location where = ghast.getLocation();
        double groundBelow = ghast.getWorld().getHighestBlockYAt(where);
        return Steering.cruiseY(groundBelow, around.groundAhead(), target.getY(), where.getY(),
                plugin.options().clearance(), ghast.getWorld().getMaxHeight());
    }

    /**
     * Reads the three things about the world that the steering cannot work out from coordinates.
     *
     * <h2>Why this only ever looks a short way ahead</h2>
     * Every block read here has to be in a chunk this flight is keeping loaded, and — on Folia — in the
     * ghast's own region. Those are the same condition: Folia groups <em>adjacent loaded</em> chunks into
     * one region, and {@link #holdChunksAround} keeps a square of {@link #TICKET_RADIUS} chunks either
     * side of the ghast loaded, so anything inside that square is both loaded and this thread's business.
     * {@link #LOOKAHEAD} is that square, less a chunk for the ghast's own drift.
     *
     * <p>At a happy ghast's own speed that is several seconds of warning, which is what the ghast needs to
     * be over a hill rather than into it.
     */
    private Surroundings look(HappyGhast ghast, Location target) {
        World world = ghast.getWorld();
        Location where = ghast.getLocation();

        Vector along = new Vector(target.getX() - where.getX(), 0, target.getZ() - where.getZ());
        double distance = along.length();
        Vector step = distance <= 1.0e-6 ? new Vector() : along.multiply(1 / distance);

        // The ground it is about to be over. The stretch is sampled rather than swept: a hill is many
        // blocks wide, and four heightmap reads per tick is nothing next to being right about it. It starts
        // at the ground underneath rather than at the bottom of the world, so a ghast hovering over its stop
        // — with no distance left to sample — is not told the terrain has fallen away beneath it.
        double groundAhead = world.getHighestBlockYAt(where);
        double reach = Math.min(LOOKAHEAD, Math.max(0, distance));
        for (double forward = SAMPLE_SPACING; forward <= reach; forward += SAMPLE_SPACING) {
            int x = (int) Math.floor(where.getX() + step.getX() * forward);
            int z = (int) Math.floor(where.getZ() + step.getZ() * forward);
            groundAhead = Math.max(groundAhead, world.getHighestBlockYAt(x, z));
        }

        boolean blocked = blockedAhead(world, where, step);
        // The sides are only probed when something is in the way: two more block reads a tick for a question
        // that has no bearing on a flight through open sky.
        Vector left = new Vector(-step.getZ(), 0, step.getX());
        boolean clearLeft = !blocked || !blockedAhead(world, where, left);
        boolean clearRight = !blocked || !blockedAhead(world, where, left.clone().multiply(-1));

        return new Surroundings(clearAbove(world, where), blocked, clearLeft, clearRight, groundAhead);
    }

    /**
     * Whether climbing would achieve anything.
     * <p>
     * A happy ghast is four blocks tall, so the ceiling that stops it is the one above <em>that</em>, not
     * the one above its feet. Checked over the whole probe rather than one block up, because a one-block
     * gap under a roof is not room to climb into.
     */
    private static boolean clearAbove(World world, Location where) {
        int x = where.getBlockX();
        int z = where.getBlockZ();
        for (int up = GHAST_HEIGHT; up <= GHAST_HEIGHT + CLIMB_PROBE; up++) {
            int y = where.getBlockY() + up;
            if (y >= world.getMaxHeight()) {
                return false;
            }
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether something solid is straight ahead at the ghast's own height.
     * <p>
     * Two heights are probed, at its middle and its shoulders, and two distances out — a wall is worth
     * noticing a couple of blocks early, and a fence post is not worth climbing over.
     */
    private static boolean blockedAhead(World world, Location where, Vector step) {
        if (step.lengthSquared() <= 1.0e-6) {
            return false;
        }
        for (double forward : AHEAD_PROBES) {
            int x = (int) Math.floor(where.getX() + step.getX() * forward);
            int z = (int) Math.floor(where.getZ() + step.getZ() * forward);
            for (int up : BODY_HEIGHTS) {
                int y = where.getBlockY() + up;
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                if (world.getBlockAt(x, y, z).getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How fast this ghast flies — its own attribute, not a number this plugin made up.
     *
     * <h2>How the attribute becomes a speed</h2>
     * By a measured ratio, not by arithmetic on vanilla's constants — see
     * {@link #BLOCKS_PER_TICK_PER_FLYING_SPEED}, which explains why the arithmetic gave an answer five times
     * too fast. A stock happy ghast comes out at about 3.8 blocks a second, which is what one flies at.
     *
     * <p>{@code ghasts.speed-percent} scales the result, so a server can make the network faster or slower
     * without anybody having to decide what a ghast's speed is in blocks per second. A ghast carrying a speed
     * modifier flies faster for free, which is the other reason to read the attribute rather than a constant.
     */
    private double airspeed(HappyGhast ghast) {
        AttributeInstance flying = ghast.getAttribute(Attribute.FLYING_SPEED);
        double attribute = flying == null ? FALLBACK_FLYING_SPEED : flying.getValue();
        return attribute * BLOCKS_PER_TICK_PER_FLYING_SPEED * plugin.options().speedPercent() / 100.0;
    }

    /**
     * Points the ghast where it is going.
     *
     * <h2>Why both the body and the head</h2>
     * {@code setRotation} turns the body; a mob also has a head yaw of its own, and setting only the first
     * leaves a ghast flying along with its face pointing where it was looking before — which is what "it flies
     * sideways" looks like. {@code lookAt} handles the head, aimed a good way down the current heading so the
     * two agree rather than fighting over a point a block away.
     *
     * <p>Pitch is deliberately left level. A ghast is a balloon with a face on it; tipping it nose-down on a
     * descent makes it look like it is falling.
     */
    private static void face(HappyGhast ghast, Vector velocity) {
        if (velocity.lengthSquared() < 1.0e-4) {
            return;
        }
        Location where = ghast.getLocation();
        Location facing = where.clone().setDirection(velocity);
        ghast.setRotation(facing.getYaw(), 0);

        Vector heading = velocity.clone();
        heading.setY(0);
        if (heading.lengthSquared() > 1.0e-6) {
            ghast.lookAt(where.clone().add(heading.normalize().multiply(LOOK_AHEAD_BLOCKS)));
        }
    }

    // ------------------------------------------------------------------ what else can end a flight

    /**
     * A summons whose passenger-to-be has logged out.
     * <p>
     * Also the boss bar: a player who logs out while watching one and comes back has no bar and no way to get
     * it, so they are dropped from the watchers and picked up again by the next refresh if they return.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Flight flight : List.copyOf(flying.values())) {
            if (flight.watchers().remove(id) && flight.bar() != null) {
                event.getPlayer().hideBossBar(flight.bar());
            }
            if (flight.currentLeg().isFollowing() && id.equals(flight.currentLeg().follow())) {
                cancelFor(flight.ghast(), "The player it was coming to has logged out.");
            }
        }
        if (summonCooldownRemaining(event.getPlayer()) <= 0) {
            // Kept while it is still running: dropping it on quit would make logging out the way to skip it.
            lastSummon.remove(id);
        }
    }

    /**
     * Keeps a lead from snapping when what it is holding is a ghast's cargo.
     *
     * <h2>The thing this fixes</h2>
     * Leash a boat to a happy ghast, step into the boat, and vanilla breaks the lead: the boat gains a
     * passenger, its position and movement change in the same tick, the distance check fires, and the lead
     * you just tied comes off — which is exactly the moment you needed it. Carrying somebody in a boat is one
     * of the two things this plugin exists to make possible, so the distance rule does not get to end it.
     *
     * <h2>Why two reasons and not one</h2>
     * The honest answer is that the exact reason vanilla gives could not be pinned down: a headless test client
     * cannot tie a lead through the protocol translation this server is tested behind, so the event was never
     * observed firing. {@code DISTANCE} is the reason that fits what happens — a boat that gains a passenger
     * moves and is moved in the same tick — and {@code UNKNOWN} is what an unattributed vanilla break arrives
     * as. Both are refused when the holder is a happy ghast; the ones that are somebody's decision or a missing
     * end are not:
     * <ul>
     *   <li>{@code PLAYER_UNLEASH} — a lead a player unties is untied.</li>
     *   <li>{@code HOLDER_GONE}, {@code LEASHED_GONE} — one end no longer exists; there is nothing to hold.</li>
     * </ul>
     * Nothing about leads anywhere else on the server changes: the holder has to be a happy ghast.
     */
    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent event) {
        if (event.getReason() != EntityUnleashEvent.UnleashReason.DISTANCE
                && event.getReason() != EntityUnleashEvent.UnleashReason.UNKNOWN) {
            return;
        }
        if (!(event.getEntity() instanceof Leashable leashed) || !leashed.isLeashed()) {
            return;
        }
        Entity holder;
        try {
            holder = leashed.getLeashHolder();
        } catch (IllegalStateException notLeashedAfterAll) {
            // Documented to throw when the leash has already gone; nothing to protect.
            return;
        }
        if (holder instanceof HappyGhast) {
            event.setCancelled(true);
        }
    }

    /**
     * Writes down where a claimed ghast was, at the last moment anybody can ask it.
     *
     * <h2>Why this event and not a timer</h2>
     * A summons has to load the chunk the ghast is in, which means knowing which chunk that is. The position
     * in the claim is only refreshed while a flight is in progress, so a ghast that was parked, ridden by
     * hand, pushed by a piston or moved by another plugin would be recorded wherever it last flew to. This
     * is the exact instant its position stops being observable, so it is the right instant to save it.
     */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof HappyGhast ghast)) {
                continue;
            }
            plugin.store().claimOf(ghast.getUniqueId())
                    .ifPresent(claim -> plugin.claims().sawAtNow(claim, ghast));
        }
    }

    /**
     * Gives a stranded load its gravity back.
     *
     * <h2>Why this exists rather than only the careful bookkeeping</h2>
     * A load is carried with its gravity switched off, and every way a flight can end switches it back on.
     * That is one class of bug away from a boat hanging in the sky for good — and it was: a load the server
     * could not resolve for a moment was struck off the flight's books, and nothing was left that knew it
     * was owed anything. The bookkeeping is fixed, but "fixed" is a claim about code and this is a claim
     * about the world: anything hanging with its gravity off, tied to a ghast that is not flying it, is put
     * right the moment it loads. Somebody's boat is not the place to find out the bookkeeping was wrong
     * again, and this also unsticks the ones that are already up there.
     *
     * <p>Safe by construction: a load that really is being carried has its gravity switched off again on the
     * very next tick of {@link #carry}, so the worst this can do to a live flight is one tick of gravity.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity.hasGravity() || !(entity instanceof Leashable leashable) || !leashable.isLeashed()) {
                continue;
            }
            try {
                if (!(leashable.getLeashHolder() instanceof HappyGhast holder)) {
                    continue;
                }
                Flight flight = flying.get(holder.getUniqueId());
                if (flight == null || !flight.cargo().contains(entity.getUniqueId())) {
                    entity.setGravity(true);
                }
            } catch (IllegalStateException noLongerLeashed) {
                // Documented to throw when the leash has gone between the two calls above.
            }
        }
    }

    /** A ghast that dies mid-flight. The claim goes with it — there is nothing left to summon. */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof HappyGhast ghast)) {
            return;
        }
        cancelFor(ghast.getUniqueId(), null);
        plugin.store().claimOf(ghast.getUniqueId()).ifPresent(claim -> {
            plugin.store().removeClaim(claim.ghast());
            Player owner = Bukkit.getPlayer(claim.owner());
            if (owner != null) {
                Text.tell(owner, Text.error("<ghast> has died. The claim on it is gone.",
                        Text.part("ghast", nameOf(claim))));
            }
        });
    }

    /**
     * A ghast that goes away without dying — despawned, removed by a command, taken by another plugin.
     * <p>
     * The claim is deliberately kept: unlike death this is usually temporary or somebody else's doing, and
     * throwing away the claim would mean an admin reloading a world costs everybody their ghasts.
     */
    @EventHandler
    public void onRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast) {
            cancelFor(ghast.getUniqueId(), null);
        }
    }
}
