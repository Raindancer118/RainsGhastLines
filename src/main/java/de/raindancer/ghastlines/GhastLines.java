package de.raindancer.ghastlines;

import org.bukkit.plugin.Plugin;

/**
 * What the commands, the menus and the listeners are allowed to ask the plugin for.
 *
 * <p>An interface rather than the class, because the class is the one file that differs between the
 * standalone jar and the module inside Rain's SMP Core — everything else in the package is written
 * against this and therefore does not have to know which build it is in.
 */
public interface GhastLines extends Plugin {

    /** The current settings: this plugin's own config, or the host's catalogue when folded in. */
    TransitOptions options();

    TransitStore store();

    /** Claiming, releasing and finding a player's ghasts. */
    Claims claims();

    /** Flights in progress, and the engine that flies them. */
    FlightService flights();

    /** Re-reads the settings. */
    void reload();
}
