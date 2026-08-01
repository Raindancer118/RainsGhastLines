package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Destinations;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What {@code /ghast} with nothing after it opens: the four doors, and the state of each behind them.
 *
 * <p>A hub rather than dropping straight into the ghast list, because the four things here are four different
 * jobs and three of them are not about a ghast at all. The counts on the icons are the reason it is worth a
 * screen of its own — "you have two ghasts, six stops, one line and nothing in the air" is most of what
 * somebody opens this to find out.
 */
public final class HubMenu extends Menu {

    private static final int SLOT_GHASTS = 11;
    private static final int SLOT_STOPS = 13;
    private static final int SLOT_ROUTES = 15;
    private static final int SLOT_FLIGHTS = 22;

    public HubMenu(GhastLines plugin, Player viewer) {
        super(plugin, viewer, null);
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">Ghast lines");
    }

    @Override
    protected void render() {
        int ghasts = plugin.store().claimCount(viewer.getUniqueId());
        int stops = plugin.store().stopCount(viewer.getUniqueId());
        int routes = plugin.store().routeCount(viewer.getUniqueId());
        int flying = plugin.flights().active().size();
        int destinations = Destinations.available(plugin.store(), viewer).size();

        set(SLOT_GHASTS, Items.of(Material.SNOWBALL,
                Text.itemName("<" + Text.SKY + ">Your ghasts"),
                List.of(Text.itemLore("<n> of <limit> claimed", Text.num("n", ghasts),
                                Text.arg("limit", String.valueOf(plugin.options().maxGhasts()))),
                        Text.gap(),
                        Text.itemLore("Claim one, call it to you, send it somewhere."))),
                ignored -> new GhastsMenu(plugin, viewer, this).open());

        set(SLOT_STOPS, Items.of(Material.LODESTONE,
                Text.itemName("<" + Text.TEXT + ">Stops"),
                List.of(Text.itemLore("<n> of <limit> kept", Text.num("n", stops),
                                Text.arg("limit", String.valueOf(plugin.options().maxStops()))),
                        Text.itemLore("<n> places you can fly to in all", Text.num("n", destinations)),
                        Text.gap(),
                        Text.itemLore("The places a ghast can be sent."))),
                ignored -> new StopsMenu(plugin, viewer, this).open());

        set(SLOT_ROUTES, Items.of(Material.RAIL,
                Text.itemName("<" + Text.TEXT + ">Routes"),
                List.of(Text.itemLore("<n> of <limit> yours", Text.num("n", routes),
                                Text.arg("limit", String.valueOf(plugin.options().maxRoutes()))),
                        Text.itemLore("<n> public lines", Text.num("n", plugin.store().sharedRoutes().size())),
                        Text.gap(),
                        Text.itemLore("Two or more stops, one way or round and round."))),
                ignored -> new RoutesMenu(plugin, viewer, this).open());

        set(SLOT_FLIGHTS, Items.of(flying == 0 ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : Material.CLOCK,
                Text.itemName("<" + Text.TEXT + ">In the air"),
                List.of(Text.itemLore(flying == 0 ? "nothing is flying" : flying + " ghast(s) on their way"),
                        Text.gap(),
                        Text.itemLore("Every flight on the server, and how far along it is."))),
                ignored -> new FlightsMenu(plugin, viewer, this).open());
    }
}
