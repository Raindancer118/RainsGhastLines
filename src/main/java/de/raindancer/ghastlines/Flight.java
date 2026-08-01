package de.raindancer.ghastlines;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One ghast, in the air, on its way somewhere — everything that changes while it is.
 *
 * <p>Mutable and owned by {@link FlightService}, which is the only thing that touches it, always from the
 * region thread that owns the ghast. Nothing here is synchronised for that reason; a flight is read from
 * other threads only through {@link FlightService#snapshot()}, which copies what it needs.
 *
 * <h2>Why the legs are a list and not a queue</h2>
 * Because {@code /ghast status} has to be able to say "stop 3 of 7", and a loop has to be able to start the
 * same list again without having to be told what was in it. Consuming the legs would throw away the
 * timetable and keep only the next departure.
 */
public final class Flight {

    /** Why this ghast is flying, which is the only thing that differs about how it ends. */
    public enum Purpose {

        /** Coming to fetch somebody, and waiting for them when it arrives. */
        SUMMON("Summoned"),

        /** Taking whoever is aboard to one place, once. */
        TRANSFER("En route"),

        /** Working a line, stop after stop, round again if it is a loop. */
        ROUTE("Service");

        private final String label;

        Purpose(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final UUID ghast;
    private final UUID owner;
    private final Purpose purpose;
    private final List<Leg> legs;
    private final boolean loop;

    /** The route being worked, or {@code null}; only for what the status line says. */
    private final String routeName;

    /** Who asked, so they hear about it wherever they are. Never held as a Player; see {@link Leg}. */
    private final UUID requestedBy;

    private final String world;
    private final long startedAt;

    private int leg;
    private Steering.Phase phase;

    /** The distance the current leg started at, so progress is a fraction of something. */
    private double legLength;
    private double blocksLeft;

    private int boardingTicksLeft;
    private boolean finished;

    /** How long a ghast may make no headway before the flight is given up on. */
    private static final int STALL_WINDOW_TICKS = 5 * TransitOptions.TICKS_PER_SECOND;

    /** How far it has to have moved in that window to count as still flying. */
    private static final double STALL_MINIMUM_MOVEMENT = 2.0;

    private org.bukkit.util.Vector stallFrom;
    private int stallTicks;

    /** The chunks this flight is keeping loaded, as chunk keys. Owned by {@link FlightService}. */
    private final Set<Long> tickets = new HashSet<>();

    /** Who is currently being shown the progress, so it can be taken off them again. */
    private final Set<UUID> watchers = new HashSet<>();

    private BossBar bar;
    private ScheduledTask task;

    Flight(UUID ghast, UUID owner, Purpose purpose, List<Leg> legs, boolean loop, String routeName,
           UUID requestedBy, String world, long startedAt) {
        this.ghast = ghast;
        this.owner = owner;
        this.purpose = purpose;
        this.legs = List.copyOf(legs);
        this.loop = loop;
        this.routeName = routeName;
        this.requestedBy = requestedBy;
        this.world = world;
        this.startedAt = startedAt;
        this.phase = Steering.Phase.CLIMB;
    }

    // ------------------------------------------------------------------ what it is

    public UUID ghast() {
        return ghast;
    }

    public UUID owner() {
        return owner;
    }

    public Purpose purpose() {
        return purpose;
    }

    public boolean isLoop() {
        return loop;
    }

    public String routeName() {
        return routeName;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public String world() {
        return world;
    }

    public long startedAt() {
        return startedAt;
    }

    public int legCount() {
        return legs.size();
    }

    /** The leg being flown, 1-based, for a person to read. */
    public int legNumber() {
        return leg + 1;
    }

    public Leg currentLeg() {
        return legs.get(Math.min(leg, legs.size() - 1));
    }

    public Steering.Phase phase() {
        return phase;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isBoarding() {
        return phase == Steering.Phase.BOARDING;
    }

    public double blocksLeft() {
        return blocksLeft;
    }

    public int boardingSecondsLeft() {
        return (int) Math.ceil((double) boardingTicksLeft / TransitOptions.TICKS_PER_SECOND);
    }

    // ------------------------------------------------------------------ how it is going

    /** How far through the whole journey, 0 to 1: whole legs plus the fraction of the one in progress. */
    public float progress() {
        if (legs.isEmpty()) {
            return 1f;
        }
        float withinLeg = Steering.progress(blocksLeft, legLength);
        return Math.min(1f, (leg + withinLeg) / legs.size());
    }

    /** Where it is going, as a phrase: the leg's name, and which of how many it is. */
    public String heading() {
        return legs.isEmpty() ? "nowhere" : currentLeg().label();
    }

    void phase(Steering.Phase now) {
        this.phase = now;
    }

    void beginLeg(double distance) {
        this.legLength = Math.max(distance, 1.0e-6);
        this.blocksLeft = distance;
    }

    void blocksLeft(double left) {
        this.blocksLeft = left;
    }

    /** Moves on to the next leg. Answers false when the journey is over. */
    boolean advanceLeg() {
        if (leg + 1 < legs.size()) {
            leg++;
            return true;
        }
        if (!loop) {
            return false;
        }
        // A loop's last leg ends where the first began — see Route#journey — so starting again is simply
        // going back to the top of the same list.
        leg = 0;
        return true;
    }

    void startBoarding(int seconds) {
        this.phase = Steering.Phase.BOARDING;
        this.boardingTicksLeft = Math.max(1, seconds) * TransitOptions.TICKS_PER_SECOND;
    }

    /** Counts a tick of boarding down. Answers true when the wait is over. */
    boolean boardingTick() {
        return --boardingTicksLeft <= 0;
    }

    void finish() {
        this.finished = true;
    }

    // ------------------------------------------------------------------ noticing a flight that is stuck

    /**
     * Whether the ghast has stopped getting anywhere.
     *
     * <h2>Why this is measured by how far the ghast has moved, not by the distance left</h2>
     * A summons chases a player, so the distance to the target goes up and down for reasons that have
     * nothing to do with the flight being in trouble. Whether the ghast itself has moved is the honest
     * question, and it has one answer: a ghast wedged under an overhang or against a wall it cannot climb
     * over stays where it is, and a flight that will never arrive has to end and say so rather than hover
     * there holding chunk tickets for ever.
     */
    boolean stalled(org.bukkit.util.Vector now) {
        if (stallFrom == null) {
            stallFrom = now.clone();
            stallTicks = 0;
            return false;
        }
        if (++stallTicks < STALL_WINDOW_TICKS) {
            return false;
        }
        boolean stuck = stallFrom.distance(now) < STALL_MINIMUM_MOVEMENT;
        stallFrom = now.clone();
        stallTicks = 0;
        return stuck;
    }

    /** Called when a leg starts, so the check measures this leg and not the one before it. */
    void resetStall() {
        stallFrom = null;
        stallTicks = 0;
    }

    // ------------------------------------------------------------------ the bits the service owns

    Set<Long> tickets() {
        return tickets;
    }

    Set<UUID> watchers() {
        return watchers;
    }

    BossBar bar() {
        return bar;
    }

    void bar(BossBar created) {
        this.bar = created;
    }

    ScheduledTask task() {
        return task;
    }

    void task(ScheduledTask running) {
        this.task = running;
    }

    /**
     * Everybody who should be watching this flight's progress: whoever asked for it, whoever owns the
     * ghast, and whoever is riding it.
     * <p>
     * Passengers are the reason this is recomputed rather than fixed at departure — somebody who gets on
     * at stop two has just as much interest in where the thing is going as the person who started it.
     */
    List<UUID> interested(List<UUID> passengers) {
        List<UUID> watching = new ArrayList<>();
        if (requestedBy != null) {
            watching.add(requestedBy);
        }
        if (!watching.contains(owner)) {
            watching.add(owner);
        }
        passengers.stream().filter(rider -> !watching.contains(rider)).forEach(watching::add);
        return watching;
    }

    /** A one-line description for a boss bar or the status board. */
    Component describe(Component ghastName) {
        String route = routeName == null ? "" : " <" + Text.MUTED + ">(<route>)";
        return Text.raw("<" + Text.SKY + "><ghast> <" + Text.MUTED + ">→ <" + Text.TEXT + "><where>"
                        + " <" + Text.MUTED + ">· <phase> · stop <leg>/<of>" + route,
                Text.part("ghast", ghastName),
                Text.arg("where", heading()),
                Text.arg("phase", phase.label()),
                Text.num("leg", legNumber()),
                Text.num("of", legCount()),
                Text.arg("route", routeName == null ? "" : routeName));
    }
}
