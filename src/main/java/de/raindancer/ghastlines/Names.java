package de.raindancer.ghastlines;

import java.util.Locale;

/**
 * What a stop, a route or a ghast may be called.
 *
 * <h2>Why names are folded to lower case</h2>
 * {@code /ghast send Base} and {@code /ghast send base} are the same request as far as the player who
 * typed them is concerned. Storing both means somebody makes a stop, cannot find it, makes it again,
 * and has spent two of their slots on the same place. The typed case is not kept: a display name that
 * differs from the key is a second name for the same thing, and it would have to be matched
 * case-insensitively anyway.
 *
 * <h2>Why the character set is this narrow</h2>
 * These names are typed into a command, tab-completed, written into a YAML path and shown inside a
 * MiniMessage template. Letters, digits, dash and underscore is the set that cannot be any of those
 * things by accident: a name with a space is two arguments, a name with {@code <} is markup, and a name
 * with a dot or a colon is a YAML path that writes itself into the wrong place in the file.
 *
 * <p>A ghast's <em>displayed</em> name is a different thing entirely — that comes from its name tag and
 * may be anything a player can put on one; see {@link Claims#displayName}. This is only about the names
 * used as keys.
 */
public final class Names {

    public static final int MAX_LENGTH = 16;

    private Names() {
    }

    /**
     * Turns typed text into the name it will be stored under.
     *
     * @return the normalised name, or {@code null} when it is not one this plugin will store
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_';
            if (!allowed) {
                return null;
            }
        }
        return trimmed;
    }

    /** Why {@link #normalise} would refuse, phrased for a player rather than for a log. */
    public static String requirement() {
        return "letters, digits, - and _, up to " + MAX_LENGTH + " characters";
    }
}
