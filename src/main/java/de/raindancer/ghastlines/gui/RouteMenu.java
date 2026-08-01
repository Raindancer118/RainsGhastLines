package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Destination;
import de.raindancer.ghastlines.Destinations;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Route;
import de.raindancer.ghastlines.Stop;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The route editor: the stops in the order they will be flown, and the four things a line is.
 *
 * <h2>Why the route is looked up again on every repaint rather than held</h2>
 * Every button here writes to the store, and so does {@code /groute} in the other window a player may have
 * open. Holding a copy would mean the screen showing what the route was when it was opened; holding the name
 * and reading it back means the screen is always showing the route. It also means the route being deleted
 * from under this screen is a case that answers itself.
 *
 * <h2>Why the order is edited with clicks and not dragged</h2>
 * A drag is how you move an item, and every click in these windows is cancelled precisely so that items
 * cannot be moved. Left is up, right is down; the position is written on every icon so it is never a guess.
 */
public final class RouteMenu extends Menu {

    private static final int SLOT_ADD = BUTTON_ROW;
    private static final int SLOT_LOOP = BUTTON_ROW + 2;
    private static final int SLOT_SHARE = BUTTON_ROW + 4;
    private static final int SLOT_START = BUTTON_ROW + 6;
    private static final int SLOT_STOP = BUTTON_ROW + 8;

    private final String routeName;

    public RouteMenu(GhastLines plugin, Player viewer, String routeName) {
        this(plugin, viewer, null, routeName);
    }

    public RouteMenu(GhastLines plugin, Player viewer, Menu parent, String routeName) {
        super(plugin, viewer, parent);
        this.routeName = routeName;
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">Route: <name>", Text.arg("name", routeName));
    }

    @Override
    protected void render() {
        Optional<Route> found = plugin.store().findRoute(viewer.getUniqueId(), routeName);
        if (found.isEmpty()) {
            set(13, Items.of(Material.BARRIER, Text.itemName("<" + Text.BAD + ">This route is gone"),
                    List.of(Text.itemLore("It was deleted while this was open."))));
            return;
        }
        Route route = found.get();

        List<String> stops = route.stops();
        for (int index = 0; index < Math.min(stops.size(), PER_PAGE); index++) {
            int position = index;
            set(index, stopIcon(route, position), event -> clickedStop(route, position, event.getClick()));
        }

        set(SLOT_ADD, Items.of(route.isFull() ? Material.BARRIER : Material.LODESTONE,
                Text.itemName(route.isFull()
                        ? "<" + Text.BAD + ">This route is full"
                        : "<" + Text.OK + ">Add a stop to the end"),
                route.isFull()
                        ? List.of(Text.itemLore("A route may call at <n> stops.",
                                Text.num("n", Route.MAXIMUM_STOPS)))
                        : List.of(Text.itemLore("Only your own stops — a line goes where you decide."))),
                ignored -> {
                    if (!route.isFull()) {
                        addStop(route);
                    }
                });

        set(SLOT_LOOP, Items.of(route.loop() ? Material.POWERED_RAIL : Material.RAIL,
                Text.itemName("<" + Text.TEXT + ">" + (route.loop() ? "Loop" : "One way")),
                List.of(Text.itemLore(route.loop()
                                ? "After the last stop it returns to the first and goes round again."
                                : "It stops at the last stop and waits there."),
                        Text.gap(),
                        Text.itemLore("<" + Text.SKY + ">Click<reset><" + Text.MUTED + "> to change"))),
                ignored -> {
                    plugin.store().putRoute(viewer.getUniqueId(), viewer.getName(),
                            route.withLoop(!route.loop()));
                    repaint();
                });

        set(SLOT_SHARE, Items.of(route.shared() ? Material.BELL : Material.IRON_BARS,
                Text.itemName("<" + Text.TEXT + ">" + (route.shared() ? "Public line" : "Private line")),
                List.of(Text.itemLore(route.shared()
                                ? "Everybody can see it and put their own ghast on it."
                                : "Only you can see it."),
                        Text.gap(),
                        Text.itemLore("<" + Text.SKY + ">Click<reset><" + Text.MUTED + "> to change"))),
                ignored -> {
                    plugin.store().putRoute(viewer.getUniqueId(), viewer.getName(),
                            route.withShared(!route.shared()));
                    repaint();
                });

        boolean flyable = route.isFlyable();
        set(SLOT_START, Items.of(flyable ? Material.SNOWBALL : Material.BARRIER,
                Text.itemName(flyable ? "<" + Text.OK + ">Put a ghast into service"
                        : "<" + Text.BAD + ">Not enough stops yet"),
                flyable
                        ? List.of(Text.itemLore("It will call at each stop for <n>s.",
                                Text.num("n", plugin.options().boardingSeconds())))
                        : List.of(Text.itemLore("A route needs at least <n> stops.",
                                Text.num("n", Route.MINIMUM_STOPS)))),
                ignored -> {
                    if (flyable) {
                        closeThen(() -> viewer.performCommand("groute start " + route.name()));
                    }
                });

        long working = plugin.flights().active().stream()
                .filter(flight -> route.name().equals(flight.routeName())).count();
        set(SLOT_STOP, Items.of(working > 0 ? Material.REDSTONE_TORCH : Material.LEVER,
                Text.itemName(working > 0 ? "<" + Text.WARN + ">Take it out of service"
                        : "<" + Text.MUTED + ">Nothing is working it"),
                List.of(Text.itemLore(working > 0
                        ? working + " ghast(s) will stop where they are."
                        : "No ghast is on this line right now."))),
                ignored -> {
                    if (working > 0) {
                        closeThen(() -> viewer.performCommand("groute stop " + route.name()));
                    }
                });
    }

    private org.bukkit.inventory.ItemStack stopIcon(Route route, int position) {
        String name = route.stops().get(position);
        Optional<Stop> stop = plugin.store().findStop(viewer.getUniqueId(), name);
        List<Component> lore = new ArrayList<>();
        lore.add(Text.itemLore("stop <n> of <of>", Text.num("n", position + 1L),
                Text.num("of", route.stops().size())));
        if (stop.isEmpty()) {
            lore.add(Text.itemLore("<" + Text.BAD + ">this stop no longer exists — the route will refuse "
                    + "to fly"));
        } else {
            lore.add(Text.itemLore("<world>, <where>", Text.arg("world", stop.get().world()),
                    Text.arg("where", stop.get().coordinates())));
        }
        if (route.loop() && position == route.stops().size() - 1) {
            lore.add(Text.itemLore("<" + Text.SKY + ">then back to <first>",
                    Text.arg("first", route.stops().getFirst())));
        }
        lore.add(Text.gap());
        lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to move it earlier"));
        lore.add(Text.itemLore("<" + Text.SKY + ">Right-click<reset><" + Text.MUTED + "> to move it later"));
        lore.add(Text.itemLore("<" + Text.BAD + ">Shift + right-click<reset><" + Text.MUTED
                + "> to take it out"));
        return Items.of(stop.isEmpty() ? Material.BARRIER : Material.LODESTONE,
                Text.itemName("<" + Text.TEXT + "><n>. <name>", Text.num("n", position + 1L),
                        Text.arg("name", name)), lore);
    }

    private void clickedStop(Route route, int position, ClickType click) {
        Route changed = switch (click) {
            case SHIFT_RIGHT -> route.minus(position);
            case RIGHT -> route.moveDown(position);
            default -> route.moveUp(position);
        };
        if (changed == route) {
            // Nothing to do — the first stop cannot move up, the last cannot move down. Saying so would be
            // three messages for a click that already looks like it did nothing.
            return;
        }
        plugin.store().putRoute(viewer.getUniqueId(), viewer.getName(), changed);
        repaint();
    }

    /**
     * Adds a stop, chosen from the player's own.
     * <p>
     * Deliberately not from the shared ones: a line whose stops belong to other people would change route
     * whenever one of them moved or unshared a stop, and its owner would have no way to stop that happening.
     */
    private void addStop(Route route) {
        List<Destination> own = plugin.store().stopsOf(viewer.getUniqueId()).stream()
                .map(stop -> Destination.fromStop(stop, stop.name(), Destinations.KIND_OWN))
                .toList();
        if (own.isEmpty()) {
            Text.tell(viewer, Text.error("You have no stops yet — stand somewhere and use "
                    + "/gstop add <name>."));
            return;
        }
        new DestinationMenu(plugin, viewer, this, "Add a stop to " + route.name(), own,
                destination -> {
                    // Read back rather than using the captured route: the chooser was open while this was
                    // not, and /groute may have changed the line in between.
                    Route now = currentRoute().orElse(route);
                    plugin.store().putRoute(viewer.getUniqueId(), viewer.getName(),
                            now.plus(destination.label()));
                    // This same screen again — not a new one with this as its parent, or Back would walk
                    // back through one editor per stop added.
                    open();
                }).open();
    }

    private Optional<Route> currentRoute() {
        return plugin.store().findRoute(viewer.getUniqueId(), routeName);
    }
}
