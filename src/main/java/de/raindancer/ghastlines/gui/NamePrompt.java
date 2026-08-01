package de.raindancer.ghastlines.gui;

import de.raindancer.ghastlines.GhastLines;
import de.raindancer.ghastlines.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * How a menu asks for a name.
 *
 * <h2>Why the chat and not an anvil</h2>
 * An anvil screen is the prettier way to type into a GUI and it costs a real anvil's worth of complexity:
 * a fake inventory, a result item that has to be rebuilt on every keystroke, and a click that has to be
 * distinguished from a click that means "take the item". Naming a stop happens once per stop. The chat is
 * two messages and cannot go wrong, and it is the same thing the player would have typed at
 * {@code /gstop add} anyway — which is rather the point of the two front ends being equal.
 *
 * <h2>Why the message is swallowed</h2>
 * Cancelling the chat event is what stops "base" appearing in everybody's chat as though it had been said.
 * The answer is then handled on the player's own scheduler, because a chat event is asynchronous and
 * everything it leads to — writing a stop, opening a menu — is not.
 *
 * <p>The waiting prompts are keyed by UUID and never by {@link Player}: a leaked player reference pins that
 * player's chunks, and the world around them, in the heap until the server restarts.
 */
public final class NamePrompt implements Listener {

    /** What a player types to change their mind. */
    private static final String CANCEL = "cancel";

    private record Pending(GhastLines plugin, Consumer<String> then) {
    }

    private static final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    /**
     * Asks a question and calls {@code then} with the answer.
     * <p>
     * Closes nothing: the caller is expected to have closed its menu already, because a player cannot type
     * while an inventory is open. {@link Menu#closeThen} is the usual way in.
     */
    public static void ask(GhastLines plugin, Player player, String question, Consumer<String> then) {
        waiting.put(player.getUniqueId(), new Pending(plugin, then));
        player.sendMessage(Text.info(question));
        player.sendMessage(Text.raw("<" + Text.MUTED + "><!italic>Type it in chat, or '" + CANCEL
                + "' to forget it."));
    }

    /** Whether this player is being asked something — so a second prompt does not talk over the first. */
    public static boolean isWaiting(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending pending = waiting.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String answer = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        player.getScheduler().run(pending.plugin(), ignored -> {
            if (answer.equalsIgnoreCase(CANCEL)) {
                Text.tell(player, Text.warn("Forgotten."));
                return;
            }
            pending.then().accept(answer);
        }, null);
    }

    /** A prompt that will never be answered, so the map cannot grow without bound. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
