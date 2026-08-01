package de.raindancer.ghastlines;

/**
 * The permission nodes, in one place, so a command and the {@code paper-plugin.yml} that documents it
 * cannot come to disagree about the spelling.
 *
 * <h2>Why there are so few</h2>
 * A transit network is a thing players build, not a thing they are granted piecemeal. One node to use it,
 * one to be exempt from the counting, and one to reach into somebody else's — that is the whole surface.
 * Reading {@code /ghast status} needs none at all: a departures board that only some people can read is
 * not a departures board.
 */
public final class Permissions {

    /** Claim a ghast, keep stops and routes, summon and fly. */
    public static final String USE = "ghastlines.use";

    /** Exempt from the limits on ghasts, stops and routes. */
    public static final String UNLIMITED = "ghastlines.unlimited";

    /** Manage anybody's ghasts, stops and routes, and stop anybody's flight. */
    public static final String ADMIN = "ghastlines.admin";

    private Permissions() {
    }
}
