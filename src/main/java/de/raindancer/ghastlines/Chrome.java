package de.raindancer.ghastlines;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * How this plugin signs and delivers what it says — and the one seam between the standalone jar and the
 * module inside Rain's SMP Core.
 *
 * <h2>Why a seam rather than two copies</h2>
 * Folded into Rain's SMP Core, every window in the jar has to wear the host's brand and every personal
 * message has to obey the host's {@code messages.personal-in-action-bar} setting, or a player sees one
 * plugin behaving like two. Standing on its own, this plugin has neither of those to ask. Putting the
 * three decisions behind suppliers means {@code GhastLinesPlugin} is the only file that differs between
 * the two builds — the same arrangement the homes and coloured-names modules use.
 *
 * <h2>Why there is no brand in here</h2>
 * This class holds the seam, not a policy: the fallbacks below are "no tag" and "no brand", not this
 * plugin's own. The identity is chosen in the main class — the file that is already allowed to differ —
 * because a default written here would be a second chat tag living in the jar, which is exactly what
 * folding these plugins together was meant to end.
 *
 * <p>Static, and installed once at startup, because the alternative is threading a reference through
 * every menu, command and listener for something none of them has an opinion about.
 */
public final class Chrome {

    private static volatile Supplier<String> chatPrefix = () -> "";
    private static volatile UnaryOperator<Component> titler = page ->
            page == null ? Component.empty() : page;
    private static volatile Sender sender = Audience::sendMessage;

    /** How a message that concerns only its recipient is delivered. */
    @FunctionalInterface
    public interface Sender {
        void send(Audience recipient, Component message);
    }

    private Chrome() {
    }

    /**
     * Installed once, at startup, by the plugin's main class.
     *
     * @param prefix   what every chat message starts with, as MiniMessage; null keeps the default
     * @param title    wraps a page name into a finished window title; null keeps the default
     * @param personal delivers a message that concerns only its recipient; null keeps the default
     */
    public static void configure(Supplier<String> prefix, UnaryOperator<Component> title,
                                 Sender personal) {
        if (prefix != null) {
            chatPrefix = prefix;
        }
        if (title != null) {
            titler = title;
        }
        if (personal != null) {
            sender = personal;
        }
    }

    /** What every chat message from this plugin starts with, as MiniMessage. */
    public static String prefix() {
        String configured = chatPrefix.get();
        return configured == null ? "" : configured;
    }

    /** A finished window title for a page. */
    public static Component title(Component page) {
        return titler.apply(page);
    }

    /** Handy for the many places that build a page name from plain text. */
    public static Component title(String page) {
        return title(MiniMessage.miniMessage().deserialize(page));
    }

    /**
     * Sends a message that concerns nobody but its recipient.
     * <p>
     * Inside Rain's SMP Core this is the action bar, when the message is short enough for one; on its own
     * it is chat, because a standalone plugin has no setting to obey and quietly moving messages somewhere
     * the admin did not ask for would be a surprise rather than a feature.
     */
    public static void personal(Audience recipient, Component message) {
        sender.send(recipient, message);
    }
}
