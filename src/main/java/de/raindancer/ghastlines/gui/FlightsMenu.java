package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Claims;
import de.raindancer.ghastlines.Flight;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Permissions;
import de.raindancer.ghastlines.Steering;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The departures board: every ghast in the air, how far along it is and where it is going.
 *
 * <h2>Why everybody's flights and not only your own</h2>
 * The same reason {@code /ghast status} shows them all. A public transit network with a private timetable is
 * not a network, and "there is a ghast circling over my roof" is a question this answers without anybody
 * having to ask its owner.
 *
 * <h2>Why there is a refresh button</h2>
 * An open inventory does not repaint itself, and a progress bar that is frozen is worse than no progress bar:
 * it looks like the flight has stopped. The boss bar is the live view — this screen is the list — so the
 * button says what it is for rather than pretending to be live.
 */
public final class FlightsMenu extends Menu {

    private static final int SLOT_REFRESH = BUTTON_ROW + 4;

    public FlightsMenu(GhastLines plugin, Player viewer) {
        this(plugin, viewer, null);
    }

    public FlightsMenu(GhastLines plugin, Player viewer, Menu parent) {
        super(plugin, viewer, parent);
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">In the air");
    }

    @Override
    protected void render() {
        List<Flight> flights = slice(plugin.flights().active());
        if (flights.isEmpty()) {
            set(13, Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    Text.itemName("<" + Text.MUTED + ">Nothing is flying"),
                    List.of(Text.itemLore("Summon a ghast, or put one on a route."))));
        }
        for (int index = 0; index < flights.size(); index++) {
            Flight flight = flights.get(index);
            set(index, icon(flight), ignored -> recall(flight));
        }
        set(SLOT_REFRESH, Items.of(Material.CLOCK,
                Text.itemName("<" + Text.SKY + ">Refresh"),
                List.of(Text.itemLore("This list is a snapshot. The boss bar is the live one."))),
                ignored -> repaint());
    }

    private org.bukkit.inventory.ItemStack icon(Flight flight) {
        Component name = plugin.store().claimOf(flight.ghast())
                .map(plugin.claims()::displayName)
                .orElse(Component.text(Claims.UNNAMED));
        String owner = plugin.store().nameOf(flight.owner());

        List<Component> lore = new ArrayList<>();
        lore.add(Text.itemLore("<bar>", Text.part("bar",
                de.raindancer.ghastlines.FlightService.bar(flight.progress()))));
        lore.add(Text.itemLore("<phase> → <where>", Text.arg("phase", flight.phase().label()),
                Text.arg("where", flight.heading())));
        lore.add(Text.itemLore("stop <n> of <of> · about <eta>s to go",
                Text.num("n", flight.legNumber()), Text.num("of", flight.legCount()),
                Text.num("eta", Steering.etaSeconds(flight.blocksLeft(),
                        plugin.options().blocksPerTick()))));
        if (flight.routeName() != null) {
            lore.add(Text.itemLore("on the '<route>' line (<kind>)",
                    Text.arg("route", flight.routeName()),
                    Text.arg("kind", flight.isLoop() ? "loop" : "one way")));
        }
        lore.add(Text.itemLore("<purpose>, in <world>, for <who>",
                Text.arg("purpose", flight.purpose().label()),
                Text.arg("world", flight.world()),
                Text.arg("who", owner.isBlank() ? "somebody" : owner)));
        if (mayRecall(flight)) {
            lore.add(Text.gap());
            lore.add(Text.itemLore("<" + Text.WARN + ">Click<reset><" + Text.MUTED
                    + "> to call the flight off"));
        }
        Material material = switch (flight.phase()) {
            case BOARDING -> Material.LIME_STAINED_GLASS_PANE;
            case CRUISE -> Material.SNOWBALL;
            case CLIMB, APPROACH -> Material.WHITE_HARNESS;
        };
        return Items.of(material, Text.itemName("<" + Text.SKY + "><name>", Text.part("name", name)), lore);
    }

    private boolean mayRecall(Flight flight) {
        return flight.owner().equals(viewer.getUniqueId()) || viewer.hasPermission(Permissions.ADMIN);
    }

    private void recall(Flight flight) {
        if (!mayRecall(flight)) {
            Text.tell(viewer, Text.warn("That is somebody else's ghast."));
            return;
        }
        boolean mine = flight.owner().equals(viewer.getUniqueId());
        plugin.flights().cancelFor(flight.ghast(), mine
                ? "Called off — it is waiting where it is."
                : "Stopped by " + viewer.getName() + ".");
        if (!mine) {
            Player owner = Bukkit.getPlayer(flight.owner());
            if (owner != null) {
                Text.tell(viewer, Text.success("Stopped it, and told <who>.",
                        Text.arg("who", owner.getName())));
            }
        }
        repaint();
    }
}
