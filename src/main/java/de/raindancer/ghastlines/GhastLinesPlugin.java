package de.raindancer.ghastlines;

import de.raindancer.ghastlines.gui.MenuListener;
import de.raindancer.ghastlines.gui.NamePrompt;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Rain's Ghast Lines.
 *
 * <p>Claim a happy ghast, and it will come when you call it — flying the route to wherever you are, coming
 * down and waiting while you get on. Send it to a stop, or put it on a line: two or more stops, one way or
 * round and round, private or published for the whole server to ride. Everything is a command and everything
 * is a menu; neither can do something the other cannot.
 *
 * <p>Wiring only. The flight model is in {@link Steering} and the engine in {@link FlightService}; what a line
 * is lives in {@link Route}, what may be flown to in {@link Destinations}, and the file is
 * {@link TransitStore} — all but the engine testable without a server.
 *
 * <p>This class is the only file in the package that differs between this jar and the {@code ghasts} module of
 * Rain's SMP Core. Two seams make that true: {@link Chrome}, which decides how the plugin signs and delivers
 * what it says, and {@link Destinations}, which is where somewhere-else's places — the homes module, in that
 * build — get plugged in as destinations. Here neither is filled in with anything but this plugin's own
 * identity, and there are no homes to offer.
 */
public final class GhastLinesPlugin extends JavaPlugin implements GhastLines {

    /** The plugin's own accent, matched to the rest of the Rain's family of plugins. */
    private static final String ACCENT = "#8FD3FF";
    private static final String ACCENT_DIM = "#3F7FBF";
    private static final String TAG = "Ghasts";

    /**
     * Volatile because the commands read it from whichever region thread the player is on, while a reload
     * writes it from the thread that ran the command.
     */
    private volatile TransitOptions options = TransitOptions.defaults();

    private TransitStore store;
    private Claims claims;
    private FlightService flights;

    @Override
    public void onEnable() {
        // Before anything can draw a window or send a message. This is the plugin's identity, and it is set
        // here rather than in Chrome because Chrome is the seam the module replaces — a tag written into it
        // would be a second identity travelling with the vendored copy.
        Chrome.configure(GhastLinesPlugin::tag, GhastLinesPlugin::windowTitle, null);

        saveDefaultConfig();
        reload();

        store = new TransitStore(getDataFolder().toPath().resolve("transit.yml"), getLogger());
        store.load();

        claims = new Claims(this);
        flights = new FlightService(this);

        getServer().getPluginManager().registerEvents(flights, this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new NamePrompt(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register("ghast", "Claim, call and send your happy ghasts",
                    List.of("ghasts", "ghastlines"), TransitCommands.ghast(this));
            commands.register("gstop", "The places a ghast can be sent",
                    List.of("ghaststop"), TransitCommands.stop(this));
            commands.register("groute", "Lines a ghast can work",
                    List.of("ghastroute"), TransitCommands.route(this));
        });

        getLogger().info("Loaded " + store.totalClaims() + " claimed ghast(s), "
                + store.totalStops() + " stop(s) and " + store.totalRoutes() + " route(s).");
    }

    @Override
    public void onDisable() {
        if (flights != null) {
            // A flight that outlived the plugin would hold its chunk tickets for the rest of the server's
            // life, and its ghast would be left with its AI switched off.
            flights.cancelAll();
        }
        if (store != null) {
            store.close();
        }
    }

    @Override
    public TransitOptions options() {
        return options;
    }

    @Override
    public TransitStore store() {
        return store;
    }

    @Override
    public Claims claims() {
        return claims;
    }

    @Override
    public FlightService flights() {
        return flights;
    }

    @Override
    public void reload() {
        reloadConfig();
        options = TransitOptions.from(getConfig().getConfigurationSection("ghasts"));
    }

    /** The tag in front of every chat message, as MiniMessage. */
    private static String tag() {
        return gradient() + " <dark_gray>»</dark_gray> ";
    }

    /** A window title: the tag, then what the page is. */
    private static Component windowTitle(Component page) {
        Component brand = MiniMessage.miniMessage().deserialize(gradient());
        if (page == null) {
            return brand;
        }
        return brand.append(MiniMessage.miniMessage().deserialize("<dark_gray> » ")).append(page);
    }

    private static String gradient() {
        return "<gradient:" + ACCENT + ":" + ACCENT_DIM + "><bold>" + TAG + "</bold></gradient>";
    }
}
