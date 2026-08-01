package de.raindancer.ghastlines;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everywhere a given player may ask to be flown — and the seam that lets homes be among them.
 *
 * <h2>The three kinds, and the tokens for them</h2>
 * <ul>
 *   <li>{@code base} — a stop of the player's own.</li>
 *   <li>{@code raindancer:market} — a stop somebody else has shared. Owner, colon, stop.</li>
 *   <li>{@code home:base} — one of the player's homes.</li>
 * </ul>
 * A colon can never appear in a name ({@link Names}), which is what makes the three forms tell
 * themselves apart with no guessing and no ambiguity to resolve in favour of one of them.
 *
 * <h2>Why homes arrive through a supplier rather than an import</h2>
 * "Fly me home" is the obvious first thing anybody will ask for, and the homes it means belong to a
 * different plugin. Inside Rain's SMP Core that plugin is the homes module, sitting in the same jar;
 * standing on its own this plugin has no homes at all, and a hard reference to
 * {@code de.raindancer.homes} would not link. So the host installs a supplier at startup and the
 * standalone jar installs nothing — the same arrangement, and for the same reason, as {@link Chrome}.
 * {@code GhastLinesPlugin} stays the only file that differs between the two builds.
 *
 * <p>Deliberately a {@link Function} of the {@link Player} rather than of a UUID: whoever fills it in may
 * want to ask about permissions, and an offline player has no destinations worth listing.
 */
public final class Destinations {

    /** {@link Destination#kind()} for one of the player's own stops. */
    public static final String KIND_OWN = "stop";

    /** {@link Destination#kind()} for a home, filled in by whoever installs the supplier. */
    public static final String KIND_HOME = "home";

    /** The prefix that makes a token mean a home. */
    public static final String HOME_PREFIX = KIND_HOME + ":";

    private static volatile Function<Player, List<Destination>> foreign = player -> List.of();

    private Destinations() {
    }

    /**
     * Installed once, at startup, by the plugin's main class.
     *
     * @param supplier destinations this plugin does not own — homes, in Rain's SMP Core; null keeps the
     *                 default, which is none
     */
    public static void configure(Function<Player, List<Destination>> supplier) {
        if (supplier != null) {
            foreign = supplier;
        }
    }

    /** Whether anything at all has been plugged into the seam — the GUI hides an empty section. */
    public static boolean hasForeign(Player player) {
        return !fromElsewhere(player).isEmpty();
    }

    /**
     * Everywhere this player can be flown, in the order a menu should show them: their own stops first,
     * then their homes, then everybody else's shared stops.
     * <p>
     * Own stops lead because they are the ones the player made for this, and shared stops trail because
     * there can be any number of them and they are somebody else's idea of a useful place.
     */
    public static List<Destination> available(TransitStore store, Player player) {
        List<Destination> all = new ArrayList<>();
        for (Stop stop : store.stopsOf(player.getUniqueId())) {
            all.add(Destination.fromStop(stop, stop.name(), KIND_OWN));
        }
        all.addAll(fromElsewhere(player));
        for (Stop stop : store.sharedStops()) {
            if (stop.owner().equals(player.getUniqueId())) {
                // Already listed above, as their own; a shared stop is still yours.
                continue;
            }
            String owner = ownerLabel(store, stop);
            all.add(Destination.fromStop(stop, owner.toLowerCase(Locale.ROOT) + ":" + stop.name(), owner));
        }
        return List.copyOf(all);
    }

    /**
     * The destination a typed token means.
     * <p>
     * Matched against {@link #available} rather than parsed, so there is exactly one answer to "what can
     * this player fly to" and a token that is not on that list is refused for the right reason.
     */
    public static Optional<Destination> resolve(TransitStore store, Player player, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String wanted = token.trim().toLowerCase(Locale.ROOT);
        return available(store, player).stream()
                .filter(destination -> destination.key().equalsIgnoreCase(wanted))
                .findFirst();
    }

    /** Tab completion: every token, filtered by what has been typed so far. */
    public static List<String> suggest(TransitStore store, Player player, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return available(store, player).stream()
                .map(Destination::key)
                .filter(key -> key.startsWith(prefix))
                .toList();
    }

    private static List<Destination> fromElsewhere(Player player) {
        List<Destination> supplied = foreign.apply(player);
        return supplied == null ? List.of() : supplied;
    }

    /** Whose stop this is, as a word: their last known name, or a short form of their id. */
    private static String ownerLabel(TransitStore store, Stop stop) {
        String known = store.nameOf(stop.owner());
        return known.isBlank() ? stop.owner().toString().substring(0, 8) : known;
    }
}
