package de.raindancer.ghastlines.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Builds the items a menu is made of.
 *
 * <p>One place, because a display name that is not explicitly un-italicised comes out italic — Minecraft does
 * that to every custom name — and getting that wrong in one screen out of six is exactly the sort of thing
 * nobody notices until it is in a screenshot. {@code Text.itemName} and {@code Text.itemLore} handle it, and
 * this makes sure everything goes through them.
 */
final class Items {

    private Items() {
    }

    static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
