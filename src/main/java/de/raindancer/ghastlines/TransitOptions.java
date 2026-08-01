package de.raindancer.ghastlines;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything the ghast lines can be told to do.
 *
 * <h2>Why this is a record and not a set of config lookups</h2>
 * The standalone plugin reads these from its own {@code config.yml}; folded into Rain's SMP Core it
 * reads them from the host's {@code /smpadmin} catalogue instead. Both produce one of these, so nothing
 * downstream knows or cares which, and {@code GhastLinesPlugin} stays the only file that differs
 * between the two builds. See MODULES.md in Rain's SMP Core.
 *
 * <h2>Why the speed is a percentage and not a number of blocks</h2>
 * Because a happy ghast already has a speed, and inventing a second one made the flight feel like a
 * plugin rather than like the animal: the first version flew at a flat twelve blocks a second whatever the
 * ghast's own attributes said. {@link FlightService} reads {@code FLYING_SPEED} off the entity and this
 * scales it, so 100 means "as fast as a happy ghast flies" and a server can still tune the network.
 *
 * @param maxGhasts        claimed ghasts one player may hold
 * @param maxStops         stops one player may keep
 * @param maxRoutes        routes one player may keep
 * @param speedPercent     cruising speed as a percentage of the ghast's own flying speed
 * @param clearance        blocks of air kept between the ghast and the ground below it
 * @param boardingSeconds  how long a ghast hovers at a stop so people can get on or off
 * @param summonCooldownSeconds seconds before the same player may summon again
 * @param maxDistance      the longest flight that will be started, in blocks
 * @param allowCrossWorld  whether a destination in another world is offered at all
 * @param bossBar          whether flight progress goes on a boss bar rather than the action bar
 */
public record TransitOptions(int maxGhasts, int maxStops, int maxRoutes, int speedPercent, int clearance,
                             int boardingSeconds, int summonCooldownSeconds, int maxDistance,
                             boolean allowCrossWorld, boolean bossBar) {

    /** Ticks per second, i.e. how the per-second settings become per-tick numbers. */
    public static final int TICKS_PER_SECOND = 20;

    public static TransitOptions defaults() {
        return new TransitOptions(2, 12, 5, 100, 12, 8, 30, 1200, false, true);
    }

    /** Reads the standalone plugin's own {@code config.yml}, or the host's {@code ghasts:} section. */
    public static TransitOptions from(ConfigurationSection config) {
        if (config == null) {
            return defaults();
        }
        TransitOptions fallback = defaults();
        return new TransitOptions(
                clamp(config.getInt("max-per-player", fallback.maxGhasts()), 0, 20),
                clamp(config.getInt("max-stops", fallback.maxStops()), 0, 100),
                clamp(config.getInt("max-routes", fallback.maxRoutes()), 0, 50),
                clamp(config.getInt("speed-percent", fallback.speedPercent()), 25, 400),
                clamp(config.getInt("cruise-clearance", fallback.clearance()), 4, 64),
                clamp(config.getInt("boarding-seconds", fallback.boardingSeconds()), 1, 60),
                clamp(config.getInt("summon-cooldown-seconds", fallback.summonCooldownSeconds()), 0, 3600),
                clamp(config.getInt("max-distance", fallback.maxDistance()), 50, 20000),
                config.getBoolean("allow-cross-world", fallback.allowCrossWorld()),
                config.getBoolean("progress-in-boss-bar", fallback.bossBar()));
    }

    /**
     * Clamped rather than rejected: a speed of -5 is a typo, and refusing to start the plugin over it
     * would be a worse answer than flying at the slowest speed that makes sense.
     */
    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public boolean hasSummonCooldown() {
        return summonCooldownSeconds > 0;
    }
}
