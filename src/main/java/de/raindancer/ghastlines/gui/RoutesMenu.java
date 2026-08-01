package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Flight;
import de.raindancer.ghastlines.GhastClaim;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Route;
import de.raindancer.ghastlines.Text;
import de.raindancer.ghastlines.Limits;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines: yours to edit and run, everybody else's public ones to run.
 *
 * <p>Somebody else's public line can be started with your own ghast on purpose. A published timetable that
 * only its author can put a ghast on is a timetable with one bus; letting anybody run the line is what makes
 * it a line, and the stops are still the author's, so it goes where they said it goes.
 */
public final class RoutesMenu extends Menu {

    private static final int SLOT_CREATE = BUTTON_ROW + 4;

    private List<Route> shown = List.of();

    public RoutesMenu(GhastLines plugin, Player viewer) {
        this(plugin, viewer, null);
    }

    public RoutesMenu(GhastLines plugin, Player viewer, Menu parent) {
        super(plugin, viewer, parent);
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">Routes");
    }

    @Override
    protected void render() {
        List<Route> all = new ArrayList<>(plugin.store().routesOf(viewer.getUniqueId()));
        plugin.store().sharedRoutes().stream()
                .filter(route -> !route.owner().equals(viewer.getUniqueId()))
                .forEach(all::add);

        shown = slice(all);
        for (int index = 0; index < shown.size(); index++) {
            Route route = shown.get(index);
            set(index, icon(route), event -> clicked(route, event.getClick()));
        }

        int have = plugin.store().routeCount(viewer.getUniqueId());
        boolean room = !Limits.reached(viewer, have, plugin.options().maxRoutes());
        set(SLOT_CREATE, Items.of(room ? Material.RAIL : Material.BARRIER,
                Text.itemName(room ? "<" + Text.OK + ">Create a route"
                        : "<" + Text.BAD + ">No room for another route"),
                room
                        ? List.of(Text.itemLore("<n> of <limit> used", Text.num("n", have),
                                Text.arg("limit", Limits.describe(viewer,
                                        plugin.options().maxRoutes()))),
                                Text.gap(),
                                Text.itemLore("You will be asked for a name in chat."))
                        : List.of(Text.itemLore("Delete one first — shift + right-click it."))),
                ignored -> {
                    if (!room) {
                        return;
                    }
                    closeThen(() -> NamePrompt.ask(plugin, viewer, "What is this route called?",
                            name -> viewer.performCommand("groute create " + name)));
                });
    }

    private org.bukkit.inventory.ItemStack icon(Route route) {
        boolean mine = route.owner().equals(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Text.itemLore("<kind>, <n> stops", Text.arg("kind", route.kind()),
                Text.num("n", route.stops().size())));
        lore.add(Text.itemLore(route.stops().isEmpty() ? "no stops yet"
                : String.join(" → ", route.stops())));
        if (!mine) {
            lore.add(Text.itemLore("a public line by <who>",
                    Text.arg("who", plugin.store().nameOf(route.owner()))));
        } else if (route.shared()) {
            lore.add(Text.itemLore("<" + Text.OK + ">public"));
        }
        long running = plugin.flights().active().stream()
                .filter(flight -> route.name().equals(flight.routeName())).count();
        if (running > 0) {
            lore.add(Text.itemLore("<" + Text.SKY + "><n> ghast(s) working it now", Text.num("n", running)));
        }
        lore.add(Text.gap());
        if (mine) {
            lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to edit the stops"));
            lore.add(Text.itemLore("<" + Text.SKY + ">Right-click<reset><" + Text.MUTED
                    + "> to put a ghast on it"));
            lore.add(Text.itemLore("<" + Text.BAD + ">Shift + right-click<reset><" + Text.MUTED
                    + "> to delete it"));
        } else {
            lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED
                    + "> to put your ghast on it"));
        }
        Material material = route.loop() ? Material.POWERED_RAIL
                : route.isFlyable() ? Material.RAIL : Material.ACTIVATOR_RAIL;
        return Items.of(material, Text.itemName("<" + Text.TEXT + "><name>",
                Text.arg("name", route.name())), lore);
    }

    private void clicked(Route route, ClickType click) {
        boolean mine = route.owner().equals(viewer.getUniqueId());
        if (!mine) {
            start(route);
            return;
        }
        switch (click) {
            case SHIFT_RIGHT -> closeThen(() -> viewer.performCommand("groute delete " + route.name()));
            case RIGHT -> start(route);
            default -> new RouteMenu(plugin, viewer, this, route.name()).open();
        }
    }

    /** Puts a ghast on the line, asking which only when the answer is not obvious. */
    private void start(Route route) {
        List<GhastClaim> mine = plugin.store().claimsOf(viewer.getUniqueId());
        if (mine.isEmpty()) {
            Text.tell(viewer, Text.error("You have no ghasts. Stand next to a happy ghast and use "
                    + "/ghast claim."));
            return;
        }
        List<GhastClaim> free = mine.stream()
                .filter(claim -> plugin.flights().flightOf(claim.ghast()).map(Flight::isFinished)
                        .orElse(true))
                .toList();
        List<GhastClaim> candidates = free.isEmpty() ? mine : free;
        if (candidates.size() == 1) {
            closeThen(() -> plugin.flights().runRoute(viewer, candidates.getFirst(), route));
            return;
        }
        new GhastsMenu(plugin, viewer, this,
                claim -> closeThen(() -> plugin.flights().runRoute(viewer, claim, route))).open();
    }
}
