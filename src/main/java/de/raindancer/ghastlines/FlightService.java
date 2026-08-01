package de.raindancer.ghastlines;

import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    /** Chunks either side of the ghast that are held loaded — two is roughly four seconds of flying. */
    private static final int TICKET_RADIUS = 2;

    private final GhastLines plugin;
    private final Tickets tickets;

    /** Flights in progress, keyed by the ghast: one ghast can only be going to one place. */
    private final Map<UUID, Flight> flying = new ConcurrentHashMap<>();

    /** When each player last had a ghast come to them, for the summon cooldown. */
    private final Map<UUID, Long> lastSummon = new ConcurrentHashMap<>();

    public FlightService(GhastLines plugin) {
        this.plugin = plugin;
        this.tickets = new Tickets(plugin);
    }

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
        flight.phase(target == null ? Steering.Phase.BOARDING : Steering.initial(
                ghast.getLocation().toVector(), aimAt(target),
                cruiseHeightFor(ghast, target)));
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
            return;
        }

        Vector position = ghast.getLocation().toVector();
        Vector aim = aimAt(target);
        double cruiseY = cruiseHeightFor(ghast, target);

        if (flight.isBoarding()) {
            hover(ghast);
            if (flight.boardingTick()) {
                leaveStop(flight, ghast, scheduled);
            }
            refresh(flight, ghast, false);
            return;
        }

        Steering.Phase next = Steering.next(flight.phase(), position, aim, cruiseY);
        flight.phase(next);
        flight.blocksLeft(position.distance(aim));

        if (next == Steering.Phase.BOARDING) {
            arrive(flight, ghast);
            refresh(flight, ghast, true);
            return;
        }

        Vector velocity = Steering.velocity(next, position, aim, cruiseY, plugin.options().blocksPerTick());
        ghast.setVelocity(velocity);
        face(ghast, velocity);

        if (flight.stalled(position)) {
            scheduled.cancel();
            end(flight, "It could not get through, so it has stopped where it is.");
            return;
        }
        refresh(flight, ghast, false);
    }

    /** Holds the ghast still at a stop. A hovering happy ghast needs no help staying up. */
    private void hover(HappyGhast ghast) {
        ghast.setVelocity(new Vector());
    }

    private void arrive(Flight flight, HappyGhast ghast) {
        hover(ghast);
        int seconds = plugin.options().boardingSeconds();
        flight.startBoarding(seconds);
        announce(flight, ghast, Text.success(
                "<ghast> has arrived at <where> — <seconds>s to get on or off.",
                Text.part("ghast", liveName(ghast, flight)),
                Text.arg("where", flight.heading()), Text.num("seconds", seconds)));
    }

    private void leaveStop(Flight flight, HappyGhast ghast, ScheduledTask scheduled) {
        if (flight.purpose() == Flight.Purpose.SUMMON || !flight.advanceLeg()) {
            scheduled.cancel();
            end(flight, null);
            announce(flight, ghast, Text.success("<ghast> is done — it is waiting for you at <where>.",
                    Text.part("ghast", liveName(ghast, flight)), Text.arg("where", flight.heading())));
            return;
        }
        beginLeg(flight, ghast);
        announce(flight, ghast, Text.info("Departing for <where>.", Text.arg("where", flight.heading())));
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
        releaseTickets(flight);
        flight.resetStall();
        flight.phase(Steering.Phase.APPROACH);
        return true;
    }

    // ------------------------------------------------------------------ progress, tickets, tidying up

    /** Everything that does not have to happen every single tick. */
    private void refresh(Flight flight, HappyGhast ghast, boolean force) {
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
        if (flying.remove(flight.ghast()) == null) {
            // Already ended: the entity scheduler's retired callback and an explicit cancel can both arrive.
            return;
        }
        flight.finish();
        releaseTickets(flight);
        land(flight);
        if (why != null) {
            announceTo(flight, List.of(), Text.warn("<why>", Text.arg("why", why)));
        }
        hideBar(flight);
    }

    private void releaseTickets(Flight flight) {
        World world = Bukkit.getWorld(flight.world());
        tickets.releaseAll(world, flight.tickets());
    }

    /** Hands the ghast back to its own AI, and stops it drifting off with the last velocity we gave it. */
    private void land(Flight flight) {
        plugin.claims().loaded(flight.ghast()).ifPresent(ghast ->
                ghast.getScheduler().run(plugin, ignored -> {
                    ghast.setVelocity(new Vector());
                    ghast.setAware(true);
                }, null));
    }

    /** Called from {@code onDisable}: a flight outliving the plugin holds chunks loaded for ever. */
    public void cancelAll() {
        for (Flight flight : List.copyOf(flying.values())) {
            if (flight.task() != null) {
                flight.task().cancel();
            }
            end(flight, null);
        }
        flying.clear();
    }

    // ------------------------------------------------------------------ talking

    private void announce(Flight flight, HappyGhast ghast, Component message) {
        announceTo(flight, passengers(ghast), message);
    }

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
     * Only the ground under the ghast is measured, never the ground at the far end: on Folia the destination
     * may belong to another region, and reading a block there from this thread is not allowed. The stop's own
     * Y stands in for the ground at the far end, which is exactly what it is — somebody stood there to make
     * it. Rising terrain is handled as it arrives: the clearance is twelve blocks by default and the ghast
     * covers less than one per tick, so it has plenty of warning.
     */
    private double cruiseHeightFor(HappyGhast ghast, Location target) {
        Location where = ghast.getLocation();
        double groundBelow = ghast.getWorld().getHighestBlockYAt(where);
        return Steering.cruiseY(groundBelow, target.getY(), where.getY(),
                plugin.options().clearance(), ghast.getWorld().getMaxHeight());
    }

    /** Points the ghast where it is going, because a ghast flying backwards looks like a bug. */
    private static void face(HappyGhast ghast, Vector velocity) {
        if (velocity.lengthSquared() < 1.0e-4) {
            return;
        }
        Location facing = ghast.getLocation().setDirection(velocity);
        ghast.setRotation(facing.getYaw(), 0);
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
