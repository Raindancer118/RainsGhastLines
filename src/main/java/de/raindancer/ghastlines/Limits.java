package de.raindancer.ghastlines;

import org.bukkit.permissions.Permissible;

/**
 * How many ghasts, stops and routes a given player may keep.
 *
 * <h2>Why this is three lines in a class of its own</h2>
 * Because the answer is asked for in five places — three commands and two menus — and each of them shows the
 * number as well as enforcing it. A menu that greys out "add a stop" while {@code /gstop add} still allows one
 * is the sort of disagreement that only ever gets noticed by the player it happens to, so the rule and the
 * words for it live together and nothing computes either for itself.
 *
 * <p>{@code ghastlines.unlimited} is the only way past a limit, and it lifts all three at once. Deliberately
 * one node rather than three: "this rank is not counted" is the decision an admin is actually making, and
 * three nodes would mostly be granted together and occasionally, accidentally, not.
 */
public final class Limits {

    private Limits() {
    }

    /** Whether one more would be too many. */
    public static boolean reached(Permissible who, int have, int limit) {
        return have >= limit && !who.hasPermission(Permissions.UNLIMITED);
    }

    /** The limit as a player should read it. */
    public static String describe(Permissible who, int limit) {
        return who.hasPermission(Permissions.UNLIMITED) ? "∞" : String.valueOf(limit);
    }
}
