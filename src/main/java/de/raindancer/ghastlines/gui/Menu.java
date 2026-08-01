package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.Chrome;
import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The little menu framework this plugin's screens are built on.
 *
 * <h2>Why a framework of its own and not the host's</h2>
 * Rain's SMP Core has a perfectly good one, and this package cannot use it: these sources also build as a
 * standalone jar, which has no host to borrow from. It is deliberately the smallest thing that removes the
 * duplication — six rows, a content area that pages, a row for buttons and a row of chrome — rather than a
 * second general-purpose framework.
 *
 * <h2>The layout, and the one rule</h2>
 * <pre>
 *   rows 0-3   slots  0-35   content, paged
 *   row  4     slots 36-44   this screen's own buttons
 *   row  5     slots 45-53   Back, page arrows, a counter, Close — painted by the framework
 * </pre>
 * {@link #set} refuses the chrome row, quietly, exactly as the host's framework does: a button written there
 * would never appear, and the framework paints over it after {@link #render()} regardless. Chrome is painted
 * last for the same reason.
 *
 * <h2>Why every click is cancelled first</h2>
 * A menu made of buttons has nowhere to put an item. Without cancelling the click before anything else, a
 * shift-click from the player's own inventory posts an item into the window and loses it when it closes.
 */
public abstract class Menu implements InventoryHolder {

    protected static final int COLUMNS = 9;
    protected static final int SIZE = 54;

    /** Slots 0-35: the content area. */
    protected static final int PER_PAGE = 36;

    /** Slots 36-44: this screen's own buttons. */
    protected static final int BUTTON_ROW = 36;

    /** Slots 45-53: the framework's. */
    protected static final int CHROME_ROW = 45;

    private static final int SLOT_BACK = CHROME_ROW;
    private static final int SLOT_PREVIOUS = CHROME_ROW + 3;
    private static final int SLOT_COUNTER = CHROME_ROW + 4;
    private static final int SLOT_NEXT = CHROME_ROW + 5;
    private static final int SLOT_CLOSE = CHROME_ROW + 8;

    protected final GhastLines plugin;
    protected final Player viewer;
    private final Menu parent;

    private Inventory inventory;
    protected int page;
    private int pages = 1;

    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(GhastLines plugin, Player viewer, Menu parent) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.parent = parent;
    }

    /** What this page is called, under the plugin's brand. */
    protected abstract Component heading();

    /** Fills the content area and the button row, with {@link #set}. Called again on every repaint. */
    protected abstract void render();

    // ------------------------------------------------------------------ opening and painting

    public void open() {
        paint();
        viewer.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, SIZE, Chrome.title(heading()));
        }
        return inventory;
    }

    /** Repaints in place — what an action that changed something calls when the screen stays open. */
    protected void repaint() {
        paint();
    }

    private void paint() {
        Inventory view = getInventory();
        view.clear();
        actions.clear();
        render();
        paintChrome(view);
    }

    private void paintChrome(Inventory view) {
        ItemStack filler = Items.of(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = CHROME_ROW; slot < SIZE; slot++) {
            view.setItem(slot, filler);
        }
        view.setItem(SLOT_BACK, Items.of(Material.ARROW,
                Text.itemName("<" + Text.WARN + ">" + (parent == null ? "Close" : "Back")), List.of()));
        actions.put(SLOT_BACK, ignored -> back());

        if (page > 0) {
            view.setItem(SLOT_PREVIOUS, Items.of(Material.SPECTRAL_ARROW,
                    Text.itemName("<" + Text.WARN + ">Previous page"), List.of()));
            actions.put(SLOT_PREVIOUS, ignored -> {
                page--;
                repaint();
            });
        }
        if (page < pages - 1) {
            view.setItem(SLOT_NEXT, Items.of(Material.SPECTRAL_ARROW,
                    Text.itemName("<" + Text.WARN + ">Next page"), List.of()));
            actions.put(SLOT_NEXT, ignored -> {
                page++;
                repaint();
            });
        }
        view.setItem(SLOT_COUNTER, Items.of(Material.PAPER,
                Text.itemName("<" + Text.TEXT + ">Page <n> of <of>",
                        Text.num("n", page + 1L), Text.num("of", pages)),
                List.of()));
        view.setItem(SLOT_CLOSE, Items.of(Material.BARRIER,
                Text.itemName("<" + Text.BAD + ">Close"), List.of()));
        actions.put(SLOT_CLOSE, ignored -> viewer.closeInventory());
    }

    // ------------------------------------------------------------------ what render() calls

    /**
     * Puts an item in a slot, with what clicking it does.
     * <p>
     * A slot on the chrome row is refused: the framework owns that row and would paint over it a moment
     * later anyway.
     */
    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= CHROME_ROW) {
            return;
        }
        getInventory().setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /**
     * The slice of {@code all} that belongs on this page, and the page count that goes with it.
     * <p>
     * Also clamps the page: deleting the last thing on page three has to land somewhere, and page three no
     * longer exists.
     */
    protected <T> List<T> slice(List<T> all) {
        pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PER_PAGE;
        return all.subList(from, Math.min(all.size(), from + PER_PAGE));
    }

    /** Goes back where this screen was opened from, or closes when it was opened from a command. */
    protected void back() {
        if (parent == null) {
            viewer.closeInventory();
            return;
        }
        parent.open();
    }

    /**
     * Closes this screen and runs something once it is gone.
     * <p>
     * For anything that needs the chat — asking for a name — because a player cannot type while an inventory
     * is open, and for anything that starts a flight, because watching a boss bar through an open menu is not
     * why they clicked.
     */
    protected void closeThen(Runnable then) {
        viewer.closeInventory();
        then.run();
    }

    // ------------------------------------------------------------------ clicking

    /**
     * Every click in this window, including the ones in the player's own inventory below it.
     * <p>
     * Cancelled unconditionally and first; see the class comment.
     */
    void handle(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(getInventory())) {
            return;
        }
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) {
            action.accept(event);
        }
    }
}
