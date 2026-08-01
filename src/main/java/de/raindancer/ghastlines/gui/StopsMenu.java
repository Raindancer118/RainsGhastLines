package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.GhastClaim;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Destination;
import de.raindancer.ghastlines.Destinations;
import de.raindancer.ghastlines.Stop;
import de.raindancer.ghastlines.Text;
import de.raindancer.ghastlines.Limits;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * Your stops: making them, sharing them, deleting them, and sending a ghast to one.
 *
 * <p>"Send a ghast here" is a click on the stop rather than a page of its own, because that is what a stop is
 * for. Which ghast is only asked when the answer is not obvious — one ghast is not a choice worth a screen.
 */
public final class StopsMenu extends Menu {

    private static final int SLOT_ADD = BUTTON_ROW + 4;

    private List<Stop> shown = List.of();

    public StopsMenu(GhastLines plugin, Player viewer) {
        this(plugin, viewer, null);
    }

    public StopsMenu(GhastLines plugin, Player viewer, Menu parent) {
        super(plugin, viewer, parent);
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">Your stops");
    }

    @Override
    protected void render() {
        shown = slice(plugin.store().stopsOf(viewer.getUniqueId()));
        for (int index = 0; index < shown.size(); index++) {
            Stop stop = shown.get(index);
            set(index, icon(stop), event -> clicked(stop, event.getClick()));
        }

        int have = plugin.store().stopCount(viewer.getUniqueId());
        boolean room = !Limits.reached(viewer, have, plugin.options().maxStops());
        set(SLOT_ADD, Items.of(room ? Material.LODESTONE : Material.BARRIER,
                Text.itemName(room ? "<" + Text.OK + ">Add a stop where you are standing"
                        : "<" + Text.BAD + ">No room for another stop"),
                room
                        ? List.of(Text.itemLore("<n> of <limit> used", Text.num("n", have),
                                Text.arg("limit", Limits.describe(viewer,
                                        plugin.options().maxStops()))),
                                Text.gap(),
                                Text.itemLore("You will be asked for a name in chat."))
                        : List.of(Text.itemLore("Delete one first — shift + right-click it."))),
                ignored -> {
                    if (!room) {
                        return;
                    }
                    // Through the command rather than straight into the store: the command already refuses
                    // an impossible name, a name that is taken and a limit that has since been reached, and
                    // says which. Two front ends, one set of rules.
                    closeThen(() -> NamePrompt.ask(plugin, viewer, "What is this stop called?",
                            name -> viewer.performCommand("gstop add " + name)));
                });
    }

    private org.bukkit.inventory.ItemStack icon(Stop stop) {
        List<Component> lore = new ArrayList<>();
        if (stop.isReachable()) {
            lore.add(Text.itemLore("<world>, <where>", Text.arg("world", stop.world()),
                    Text.arg("where", stop.coordinates())));
        } else {
            lore.add(Text.itemLore("<" + Text.BAD + ">the world '<world>' is not loaded",
                    Text.arg("world", stop.world())));
        }
        lore.add(Text.itemLore(stop.shared() ? "<" + Text.OK + ">public — anybody may fly here" : "private"));
        lore.add(Text.gap());
        lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to send a ghast here"));
        lore.add(Text.itemLore("<" + Text.SKY + ">Right-click<reset><" + Text.MUTED + "> to "
                + (stop.shared() ? "make it private" : "make it public")));
        lore.add(Text.itemLore("<" + Text.BAD + ">Shift + right-click<reset><" + Text.MUTED + "> to delete it"));
        return Items.of(stop.shared() ? Material.BELL : Material.LODESTONE,
                Text.itemName("<" + Text.TEXT + "><name>", Text.arg("name", stop.name())), lore);
    }

    private void clicked(Stop stop, ClickType click) {
        switch (click) {
            case SHIFT_RIGHT -> closeThen(() -> viewer.performCommand("gstop remove " + stop.name()));
            case RIGHT -> {
                plugin.store().putStop(viewer.getUniqueId(), viewer.getName(),
                        stop.withShared(!stop.shared()));
                repaint();
            }
            default -> sendAGhast(stop);
        }
    }

    /**
     * Sends a ghast to this stop, asking which one only when there is more than one.
     * <p>
     * The list of ghasts is offered as the ghasts page rather than as a third screen: it already knows how to
     * show what each one is doing, which is exactly what somebody choosing between two of them wants to see.
     */
    private void sendAGhast(Stop stop) {
        List<GhastClaim> mine = plugin.store().claimsOf(viewer.getUniqueId());
        if (mine.isEmpty()) {
            Text.tell(viewer, Text.error("You have no ghasts. Stand next to a happy ghast and use "
                    + "/ghast claim."));
            return;
        }
        Destination destination = Destination.fromStop(stop, stop.name(), Destinations.KIND_OWN);
        if (mine.size() == 1) {
            closeThen(() -> plugin.flights().send(viewer, mine.getFirst(), destination));
            return;
        }
        new GhastsMenu(plugin, viewer, this,
                claim -> closeThen(() -> plugin.flights().send(viewer, claim, destination))).open();
    }
}
