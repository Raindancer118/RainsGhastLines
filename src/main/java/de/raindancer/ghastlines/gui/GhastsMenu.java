package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Claims;
import de.raindancer.ghastlines.Destinations;
import de.raindancer.ghastlines.Flight;
import de.raindancer.ghastlines.GhastClaim;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Your ghasts: which you have, what each is doing, and the four things you can do to one.
 *
 * <p>The four are on the lore of every icon rather than on a row of buttons, because they are all "do this to
 * <em>that</em> ghast" and a button would need a ghast selected first. A click is the selection.
 */
public final class GhastsMenu extends Menu {

    private static final int SLOT_CLAIM = BUTTON_ROW + 4;

    /**
     * Set when this screen is being used to answer "which ghast?" for somebody else.
     * <p>
     * The alternative was a second screen listing the same ghasts, which would have had to grow the same
     * "what is each one doing" lore — which is exactly what somebody choosing between two of them needs to
     * see. So the list is one screen and a click means either "call this one" or "this one", depending on
     * whether anybody asked.
     */
    private final java.util.function.Consumer<GhastClaim> picking;

    private List<GhastClaim> shown = List.of();

    public GhastsMenu(GhastLines plugin, Player viewer) {
        this(plugin, viewer, null);
    }

    public GhastsMenu(GhastLines plugin, Player viewer, Menu parent) {
        this(plugin, viewer, parent, null);
    }

    public GhastsMenu(GhastLines plugin, Player viewer, Menu parent,
                      java.util.function.Consumer<GhastClaim> picking) {
        super(plugin, viewer, parent);
        this.picking = picking;
    }

    @Override
    protected Component heading() {
        return Text.raw("<" + Text.TEXT + ">" + (picking == null ? "Your ghasts" : "Which ghast?"));
    }

    @Override
    protected void render() {
        shown = slice(plugin.store().claimsOf(viewer.getUniqueId()));
        for (int index = 0; index < shown.size(); index++) {
            GhastClaim claim = shown.get(index);
            set(index, icon(claim), event -> clicked(claim, event.getClick()));
        }

        Optional<HappyGhast> beside = plugin.claims().beside(viewer);
        set(SLOT_CLAIM, Items.of(beside.isPresent() ? Material.LEAD : Material.STRING,
                Text.itemName(beside.isPresent()
                        ? "<" + Text.OK + ">Claim the ghast beside you"
                        : "<" + Text.MUTED + ">No ghast within reach"),
                beside.isPresent()
                        ? List.of(Text.itemLore("Stand by a happy ghast — or ride it — and click."))
                        : List.of(Text.itemLore("Get within <n> blocks of a happy ghast first.",
                                Text.num("n", Math.round(Claims.CLAIM_RADIUS))))),
                ignored -> closeThen(() -> viewer.performCommand("ghast claim")));
    }

    private org.bukkit.inventory.ItemStack icon(GhastClaim claim) {
        Optional<Flight> flight = plugin.flights().flightOf(claim.ghast());
        List<Component> lore = new ArrayList<>();
        lore.add(Text.itemLore("answers to '<token>'", Text.arg("token", plugin.claims().token(claim))));
        if (flight.isPresent()) {
            lore.add(Text.itemLore("<" + Text.SKY + "><phase> → <where> (<percent>%)",
                    Text.arg("phase", flight.get().phase().label()),
                    Text.arg("where", flight.get().heading()),
                    Text.num("percent", Math.round(flight.get().progress() * 100))));
        } else if (claim.lastSeen() == null) {
            lore.add(Text.itemLore("<" + Text.BAD + ">last seen in <world>, which is not loaded",
                    Text.arg("world", claim.world())));
        } else {
            lore.add(Text.itemLore("parked in <world> at <where>", Text.arg("world", claim.world()),
                    Text.arg("where", claim.coordinates())));
        }
        lore.add(Text.gap());
        if (picking != null) {
            lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to use this one"));
            return Items.of(flight.isPresent() ? Material.SNOWBALL : Material.WHITE_HARNESS,
                    Text.itemName("<" + Text.SKY + "><name>",
                            Text.part("name", plugin.claims().displayName(claim))), lore);
        }
        lore.add(Text.itemLore("<" + Text.OK + ">Click<reset><" + Text.MUTED + "> to call it to you"));
        lore.add(Text.itemLore("<" + Text.SKY + ">Right-click<reset><" + Text.MUTED + "> to send it somewhere"));
        if (flight.isPresent()) {
            lore.add(Text.itemLore("<" + Text.WARN + ">Shift + left-click<reset><" + Text.MUTED
                    + "> to call the flight off"));
        }
        lore.add(Text.itemLore("<" + Text.BAD + ">Shift + right-click<reset><" + Text.MUTED + "> to release it"));

        Material material = flight.isPresent() ? Material.SNOWBALL : Material.WHITE_HARNESS;
        return Items.of(material, Text.itemName("<" + Text.SKY + "><name>",
                Text.part("name", plugin.claims().displayName(claim))), lore);
    }

    private void clicked(GhastClaim claim, ClickType click) {
        if (picking != null) {
            picking.accept(claim);
            return;
        }
        switch (click) {
            case SHIFT_RIGHT -> {
                plugin.claims().release(claim.ghast());
                Text.tell(viewer, Text.success("<ghast> is no longer yours.",
                        Text.part("ghast", plugin.claims().displayName(claim))));
                repaint();
            }
            case SHIFT_LEFT -> {
                if (!plugin.flights().cancelFor(claim.ghast(), "Called off — it is waiting where it is.")) {
                    Text.tell(viewer, Text.warn("That one is not flying anywhere."));
                }
                repaint();
            }
            case RIGHT -> new DestinationMenu(plugin, viewer, this,
                    "Send " + plugin.claims().plainName(claim) + " to",
                    Destinations.available(plugin.store(), viewer),
                    destination -> closeThen(() ->
                            plugin.flights().send(viewer, claim, destination))).open();
            default -> closeThen(() -> plugin.flights().summon(viewer, claim));
        }
    }
}
