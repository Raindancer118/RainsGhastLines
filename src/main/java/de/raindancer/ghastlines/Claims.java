package de.raindancer.ghastlines;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Whose ghast is whose: claiming one, letting it go, naming it, and finding it again.
 *
 * <h2>Why a claim is not stored on the entity</h2>
 * A {@link org.bukkit.persistence.PersistentDataContainer} on the ghast would be the tidy answer, and it
 * cannot be read at all while the ghast is in an unloaded chunk — which is the state a parked ghast is in
 * nearly all of the time, and exactly the state {@code /ghast list} and {@code /ghast summon} have to work
 * in. So the claim lives in this plugin's own file, keyed by the entity's UUID, and the entity is only
 * consulted for the things only it knows: whether it still exists, and what its name tag says.
 *
 * <h2>Why the name comes from the name tag</h2>
 * Because that is the name the player gave it, it is visible floating over the ghast, and it needs no
 * command to change. {@link #displayName} reads it live wherever the entity can be reached and falls back
 * to what was written down last time it was seen, so a menu listing four parked ghasts still has four
 * names in it.
 */
public final class Claims {

    /** How far from a player a ghast may be and still be the one they mean. */
    public static final double CLAIM_RADIUS = 12.0;

    /** Chunks either side of where a ghast was last seen that a summons will look in. */
    private static final int SEARCH_RADIUS = 2;

    /** What a ghast with no name tag is called. */
    public static final String UNNAMED = "Unnamed ghast";

    /** Why a claim did not happen, or that it did. */
    public enum Outcome {
        CLAIMED,
        ALREADY_YOURS,
        /** Somebody else got there first. */
        TAKEN,
        AT_LIMIT
    }

    private final GhastLines plugin;

    public Claims(GhastLines plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ claiming

    /**
     * The happy ghast a player means when they do not name one.
     * <p>
     * The one they are riding wins, because somebody sitting on a ghast has already picked it. Otherwise
     * the nearest within {@link #CLAIM_RADIUS}, which is generous on purpose: a happy ghast is four blocks
     * wide and hovers, so "the one in front of me" is rarely within touching distance.
     */
    public Optional<HappyGhast> beside(Player player) {
        if (player.getVehicle() instanceof HappyGhast ridden) {
            return Optional.of(ridden);
        }
        return player.getNearbyEntities(CLAIM_RADIUS, CLAIM_RADIUS, CLAIM_RADIUS).stream()
                .filter(HappyGhast.class::isInstance)
                .map(HappyGhast.class::cast)
                .min(Comparator.comparingDouble(ghast ->
                        ghast.getLocation().distanceSquared(player.getLocation())));
    }

    public Outcome claim(Player player, HappyGhast ghast) {
        Optional<GhastClaim> existing = plugin.store().claimOf(ghast.getUniqueId());
        if (existing.isPresent()) {
            return existing.get().owner().equals(player.getUniqueId())
                    ? Outcome.ALREADY_YOURS : Outcome.TAKEN;
        }
        TransitOptions options = plugin.options();
        if (plugin.store().claimCount(player.getUniqueId()) >= options.maxGhasts()
                && !player.hasPermission(Permissions.UNLIMITED)) {
            return Outcome.AT_LIMIT;
        }

        // A claimed ghast is infrastructure: it has to still be there tomorrow, whether or not anybody
        // has been near it, so it stops counting as scenery the server may tidy away.
        ghast.setPersistent(true);
        ghast.setRemoveWhenFarAway(false);

        plugin.store().putClaim(GhastClaim.of(ghast.getUniqueId(), player.getUniqueId(),
                plainName(ghast), ghast.getLocation(), System.currentTimeMillis()), player.getName());
        return Outcome.CLAIMED;
    }

    /** Gives a ghast up. The entity is left exactly as it is — it is somebody's animal, not an item. */
    public Optional<GhastClaim> release(UUID ghast) {
        plugin.flights().cancelFor(ghast, "Released by its owner.");
        return plugin.store().removeClaim(ghast);
    }

    /**
     * Renames a ghast by writing its name tag, which is the only place the name lives.
     * <p>
     * Setting {@code customName} is the same field a name tag writes, so the new name floats over the
     * ghast for everybody and survives without this plugin. The stored copy is only the fallback for when
     * the entity cannot be reached.
     */
    public void rename(GhastClaim claim, String name) {
        String trimmed = name == null ? "" : name.trim();
        found(claim, ghast -> {
            if (trimmed.isEmpty()) {
                ghast.customName(null);
                ghast.setCustomNameVisible(false);
            } else {
                ghast.customName(Text.raw("<" + Text.SKY + ">" + escape(trimmed)));
                ghast.setCustomNameVisible(true);
            }
        });
        plugin.store().refreshClaim(claim.withName(trimmed));
    }

    // ------------------------------------------------------------------ naming

    /** A claim's name as a component: the live name tag where it can be read, the stored one otherwise. */
    public Component displayName(GhastClaim claim) {
        Entity entity = Bukkit.getEntity(claim.ghast());
        Component live = entity == null ? null : entity.customName();
        if (live != null) {
            return live;
        }
        return claim.name().isBlank()
                ? Text.raw("<" + Text.MUTED + ">" + UNNAMED)
                : Text.raw("<" + Text.SKY + ">" + escape(claim.name()));
    }

    /** The same, flattened, for a log line or a placeholder. */
    public String plainName(GhastClaim claim) {
        return PlainTextComponentSerializer.plainText().serialize(displayName(claim));
    }

    /** A ghast's name as plain text, or empty when it has no name tag. */
    public static String plainName(HappyGhast ghast) {
        Component named = ghast.customName();
        return named == null ? "" : PlainTextComponentSerializer.plainText().serialize(named);
    }

    /**
     * The word a player types to mean this ghast.
     * <p>
     * A name tag may hold anything, including spaces and colour codes, and a command argument may not.
     * So the token is the name with everything that is not a letter or a digit turned into an underscore
     * — {@code "Bus 12"} becomes {@code bus_12} — and a ghast with no name at all answers to the first
     * eight characters of its id, which is also always accepted as well as the name. Two ghasts named the
     * same are therefore ambiguous, {@code /ghast list} shows the tokens so it can be seen, and renaming
     * one fixes it; the id form is the way out in the meantime.
     */
    public String token(GhastClaim claim) {
        return tokenOf(plainName(claim), claim.ghast());
    }

    /**
     * The rule itself, without a server: a name and an id in, a typeable word out.
     * <p>
     * Separate from {@link #token} so that it can be tested, because the name it works on is the one thing in
     * this plugin a player can write anything at all into.
     */
    public static String tokenOf(String plainName, UUID id) {
        String plain = plainName == null ? "" : plainName;
        if (plain.isBlank() || plain.equals(UNNAMED)) {
            return shortId(id);
        }
        StringBuilder token = new StringBuilder();
        for (char character : plain.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean plainCharacter = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9');
            token.append(plainCharacter ? character : '_');
            if (token.length() >= 24) {
                break;
            }
        }
        String cleaned = token.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return cleaned.isEmpty() ? shortId(id) : cleaned;
    }

    public static String shortId(GhastClaim claim) {
        return shortId(claim.ghast());
    }

    /** The eight characters of an id a ghast always answers to, whatever its name tag says. */
    public static String shortId(UUID ghast) {
        return ghast.toString().substring(0, 8);
    }

    /** Which of this player's ghasts a typed token means. */
    public Optional<GhastClaim> byToken(Player player, String typed) {
        List<GhastClaim> mine = plugin.store().claimsOf(player.getUniqueId());
        if (typed == null || typed.isBlank()) {
            // One ghast and no argument is not ambiguous, and making somebody name it would be a
            // command that refuses to do the only thing it could have done.
            return mine.size() == 1 ? Optional.of(mine.getFirst()) : Optional.empty();
        }
        String wanted = typed.trim().toLowerCase(Locale.ROOT);
        return mine.stream()
                .filter(claim -> token(claim).equals(wanted) || shortId(claim).equals(wanted))
                .findFirst();
    }

    public List<String> tokens(Player player) {
        return plugin.store().claimsOf(player.getUniqueId()).stream().map(this::token).toList();
    }

    // ------------------------------------------------------------------ finding the entity

    /** The ghast, if it happens to be loaded right now. */
    public Optional<HappyGhast> loaded(UUID ghast) {
        Entity entity = Bukkit.getEntity(ghast);
        return entity instanceof HappyGhast happy ? Optional.of(happy) : Optional.empty();
    }

    /** Runs {@code action} on the ghast if it is loaded, and does nothing at all if it is not. */
    public void found(GhastClaim claim, Consumer<HappyGhast> action) {
        loaded(claim.ghast()).ifPresent(action);
    }

    /**
     * Finds a claimed ghast, loading the chunk it was last seen in if that is what it takes.
     *
     * <h2>Why this is not simply {@code Bukkit.getEntity}</h2>
     * That answers only for loaded entities, and a ghast parked at somebody's base is in an unloaded chunk
     * within a minute of them walking away — which is the exact situation a summons exists for. So when
     * the direct lookup fails, the chunk it was last seen in is loaded and the lookup is tried again there.
     *
     * <p>{@code missing} is called for the two cases that are not a loading problem: the world is gone, or
     * the chunk came back without the ghast in it, which means it died or was taken somewhere else while
     * nobody was watching. Both are permanent, and the caller says so rather than waiting.
     */
    public void locate(GhastClaim claim, Consumer<HappyGhast> found, Runnable missing) {
        Optional<HappyGhast> alreadyHere = loaded(claim.ghast());
        if (alreadyHere.isPresent()) {
            found.accept(alreadyHere.get());
            return;
        }
        Location lastSeen = claim.lastSeen();
        World world = lastSeen == null ? null : lastSeen.getWorld();
        if (world == null) {
            missing.run();
            return;
        }

        // A square of chunks and not the one it was last seen in. The recorded position is where it was
        // when its chunk unloaded, and a ghast that was pushed, leashed, or claimed before this plugin
        // started parking them can be a little way off. Twenty-five chunks is a lot to load at once, and
        // this happens once per summons, by hand, because somebody asked for it.
        int centreX = lastSeen.getBlockX() >> 4;
        int centreZ = lastSeen.getBlockZ() >> 4;
        List<CompletableFuture<Chunk>> loading = new ArrayList<>();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                loading.add(world.getChunkAtAsync(centreX + x, centreZ + z));
            }
        }

        CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
                .thenRun(() -> onRegionThread(lastSeen, () -> {
                    // The chunks are deliberately not ticketed here: a summons that fails must not leave
                    // twenty-five chunks loaded for the rest of the server's life. Loading them is enough
                    // to make the entity real, and by the time they unload again the flight has started
                    // and taken its own tickets out.
                    Optional<HappyGhast> nowHere = loaded(claim.ghast());
                    if (nowHere.isPresent()) {
                        found.accept(nowHere.get());
                        return;
                    }
                    // Entities live in their own storage, which a chunk load does not always walk; asking
                    // each chunk what is in it is the reliable second look.
                    for (CompletableFuture<Chunk> chunk : loading) {
                        for (Entity entity : chunk.getNow(null) == null
                                ? new Entity[0] : chunk.getNow(null).getEntities()) {
                            if (entity.getUniqueId().equals(claim.ghast())
                                    && entity instanceof HappyGhast happy) {
                                found.accept(happy);
                                return;
                            }
                        }
                    }
                    missing.run();
                }));
    }

    /** Notes where a ghast is, so a summons can find it after the chunk unloads. */
    public void sawAt(GhastClaim claim, HappyGhast ghast) {
        plugin.store().refreshClaim(claim.seenAt(plainName(ghast), ghast.getLocation()));
    }

    /**
     * Runs on the thread that owns {@code where}.
     * <p>
     * The regionised scheduler is used directly rather than through the host's {@code Scheduling} helper
     * because this package also builds as a standalone jar, which has no host to borrow from. It is the
     * same API the helper wraps — {@code Bukkit.getScheduler()} is never touched, so this is Folia-safe.
     */
    void onRegionThread(Location where, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, where, task);
    }

    /** MiniMessage-proofs text that came from a player, for the one place it is built into a template. */
    private static String escape(String raw) {
        return raw.replace("<", "\\<");
    }
}
