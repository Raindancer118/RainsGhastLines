package de.raindancer.ghastlines.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes clicks to the menu that was clicked.
 *
 * <p>Recognising our own windows by their {@link org.bukkit.inventory.InventoryHolder} rather than by keeping
 * a registry of who has what open: a registry would hold {@link org.bukkit.entity.Player} references, and a
 * leaked one pins that player's chunks — and the whole world around them — in the heap until the server
 * restarts.
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handle(event);
        }
    }

    /** A drag can move an item into a window a click never could. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
