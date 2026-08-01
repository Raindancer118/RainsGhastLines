package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Destination;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pick a place.
 *
 * <p>Used twice — for "send this ghast somewhere" and for "add a stop to this route" — with a different list
 * and a different thing to do with the answer, which is why it takes both. A second screen that looked
 * identical and did one of those two things would be the same screen written twice.
 *
 * <p>The list already has the player's homes in it wherever the host has plugged them into
 * {@link de.raindancer.ghastlines.Destinations}, so nothing here knows or cares that homes exist.
 */
public final class DestinationMenu extends Menu {

    private final String what;
    private final List<Destination> offered;
    private final Consumer<Destination> chosen;

    private List<Destination> shown = List.of();

    public DestinationMenu(GhastLines plugin, Player viewer, Menu parent, String what,
                           List<Destination> offered, Consumer<Destination> chosen) {
        super(plugin, viewer, parent);
        this.what = what;
        this.offered = List.copyOf(offered);
        this.chosen = chosen;
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + "><what>", Text.arg("what", what));
    }

    @Override
    protected void render() {
        if (offered.isEmpty()) {
            set(13, Items.of(Material.BARRIER, Text.itemName("<" + Text.BAD + ">Nowhere to go"),
                    List.of(Text.itemLore("Stand somewhere and use /gstop add <name>, or the Stops page."))));
            return;
        }
        shown = slice(offered);
        for (int index = 0; index < shown.size(); index++) {
            Destination destination = shown.get(index);
            set(index, icon(destination), ignored -> chosen.accept(destination));
        }
    }

    private org.bukkit.inventory.ItemStack icon(Destination destination) {
        List<Component> lore = new ArrayList<>();
        lore.add(Text.itemLore("<kind>", Text.arg("kind", destination.kind())));
        if (destination.isReachable()) {
            lore.add(Text.itemLore("<world>, <where>", Text.arg("world", destination.world()),
                    Text.arg("where", destination.coordinates())));
        } else {
            lore.add(Text.itemLore("<" + Text.BAD + ">the world '<world>' is not loaded",
                    Text.arg("world", destination.world())));
        }
        lore.add(Text.gap());
        lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to choose this"));
        lore.add(Text.itemLore("typed: <key>", Text.arg("key", destination.key())));
        return Items.of(destination.icon(), Text.itemName("<" + Text.TEXT + "><name>",
                Text.arg("name", destination.label())), lore);
    }
}
