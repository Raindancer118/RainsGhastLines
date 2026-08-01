package de.raindancer.ghastlines;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * All of this plugin's user-facing text.
 *
 * <p>Everything is MiniMessage; anything a player supplied — a stop name, a world, a ghast's name tag —
 * goes in as {@link Placeholder#unparsed} so a ghast called {@code <red>} is five characters rather than
 * a colour change. Stop and route names are validated by {@link Names}, but a name tag is not and cannot
 * be: it is whatever somebody wrote on an anvil, which is exactly the input that has to be unparsed.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final String OK = "#7BE07B";
    public static final String WARN = "#FFD166";
    public static final String BAD = "#FF7B7B";
    public static final String TEXT = "#D7DCE0";
    public static final String MUTED = "#8B949E";

    /** The colour of a ghast in flight, used by the boss bar's title and the status list. */
    public static final String SKY = "#8FD3FF";

    private Text() {
    }

    public static Component raw(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    public static Component info(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + TEXT + ">" + miniMessage, resolvers);
    }

    public static Component success(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + OK + ">" + miniMessage, resolvers);
    }

    public static Component warn(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + WARN + ">" + miniMessage, resolvers);
    }

    public static Component error(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(Chrome.prefix() + "<" + BAD + ">" + miniMessage, resolvers);
    }

    /** Wraps untrusted text so it can be dropped into a message safely. */
    public static TagResolver arg(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "—" : value);
    }

    public static TagResolver num(String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    /** A name tag, which may be any component at all, dropped into a template as itself. */
    public static TagResolver part(String name, Component value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }

    /** An item's name: not italic, because Minecraft italicises custom names by default. */
    public static Component itemName(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic>" + miniMessage, resolvers);
    }

    public static Component itemLore(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize("<!italic><" + MUTED + ">" + miniMessage, resolvers)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** A blank lore line, for separating what a thing is from what clicking it does. */
    public static Component gap() {
        return Component.empty();
    }

    /** Sends something only this recipient cares about — the action bar, where that is configured. */
    public static void tell(Audience recipient, Component message) {
        Chrome.personal(recipient, message);
    }

    /**
     * Sends something to the action bar whatever the host's setting says.
     * <p>
     * Used for the flight progress line, and only for it. Progress is a status display that replaces
     * itself several times a second: in chat it would be a wall of scrolling text, so this is the one
     * message in the plugin that is not the recipient's — or the admin's — choice.
     */
    public static void status(Audience recipient, Component message) {
        recipient.sendActionBar(message);
    }
}
