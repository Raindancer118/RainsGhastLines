package de.raindancer.ghastlines;

import de.raindancer.ghastlines.gui.FlightsMenu;
import de.raindancer.ghastlines.gui.GhastsMenu;
import de.raindancer.ghastlines.gui.HubMenu;
import de.raindancer.ghastlines.gui.RouteMenu;
import de.raindancer.ghastlines.gui.RoutesMenu;
import de.raindancer.ghastlines.gui.StopsMenu;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /ghast}, {@code /gstop} and {@code /groute}.
 *
 * <h2>Why three commands and not one</h2>
 * They are three different jobs done at three different times: claiming and flying a ghast is something you
 * do while standing next to one, keeping stops is something you do as you explore, and writing a line is
 * something you sit down and do once. Folding them into {@code /ghast stop add} would make the two things a
 * player does most often the two longest things to type.
 *
 * <h2>Why every one of them opens a menu when given nothing</h2>
 * Because the ask was that everything be reachable both ways, and the honest way to do that is to make the
 * bare command the door to the GUI rather than a wall of usage text. The usage is still there, under
 * {@code help}, for whoever prefers to type.
 *
 * <p>The shared work — finding out which ghast somebody means, refusing with a reason, checking a limit —
 * lives here once and all three use it.
 */
public final class TransitCommands {

    /** How long a stop's alias may be. Long enough for a sentence, short enough for a menu row. */
    private static final int MAX_LABEL_LENGTH = 32;

    private TransitCommands() {
    }

    // ------------------------------------------------------------------ /ghast

    public static BasicCommand ghast(GhastLines plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                // The departures board is the one thing here that is not about a ghast of your own, so it is
                // answered before anything asks the sender to be a player standing next to one.
                if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                    status(plugin, source.getSender());
                    return;
                }
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    new HubMenu(plugin, player).open();
                    return;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "claim" -> claim(plugin, player);
                    case "release" -> release(plugin, player, rest(args, 1));
                    case "rename" -> rename(plugin, player, args);
                    case "summon", "call" -> summon(plugin, player, rest(args, 1));
                    case "send", "fly" -> send(plugin, player, args);
                    case "recall", "abort" -> recall(plugin, player, rest(args, 1));
                    case "list" -> list(plugin, player);
                    case "flights" -> new FlightsMenu(plugin, player).open();
                    case "help" -> ghastHelp(player);
                    default -> Text.tell(player, Text.error(
                            "No such thing as /ghast <what>. Try /ghast help.",
                            Text.arg("what", args[0])));
                }
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                      String @NotNull [] args) {
                if (!(source.getSender() instanceof Player player)) {
                    return List.of();
                }
                if (args.length <= 1) {
                    return startingWith(List.of("claim", "release", "rename", "summon", "send", "recall",
                            "list", "status", "flights", "help"), args.length == 0 ? "" : args[0]);
                }
                String verb = args[0].toLowerCase(Locale.ROOT);
                if (verb.equals("send") || verb.equals("fly")) {
                    return args.length == 2
                            ? Destinations.suggest(plugin.store(), player, args[1])
                            : startingWith(plugin.claims().tokens(player), args[2]);
                }
                if (args.length == 2 && List.of("release", "rename", "summon", "call", "recall", "abort")
                        .contains(verb)) {
                    return startingWith(plugin.claims().tokens(player), args[1]);
                }
                return List.of();
            }

            @Override
            public String permission() {
                return Permissions.USE;
            }
        };
    }

    // ------------------------------------------------------------------ /gstop

    public static BasicCommand stop(GhastLines plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    new StopsMenu(plugin, player).open();
                    return;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "add", "set" -> addStop(plugin, player, rest(args, 1));
                    case "remove", "delete" -> removeStop(plugin, player, rest(args, 1));
                    case "share" -> shareStop(plugin, player, rest(args, 1));
                    case "label", "alias" -> labelStop(plugin, player, args);
                    case "rename" -> renameStop(plugin, player, args);
                    case "list" -> listStops(plugin, player);
                    case "help" -> stopHelp(player);
                    default -> Text.tell(player, Text.error(
                            "No such thing as /gstop <what>. Try /gstop help.",
                            Text.arg("what", args[0])));
                }
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                      String @NotNull [] args) {
                if (!(source.getSender() instanceof Player player)) {
                    return List.of();
                }
                if (args.length <= 1) {
                    return startingWith(List.of("add", "remove", "share", "label", "rename", "list",
                            "help"), args.length == 0 ? "" : args[0]);
                }
                if (args.length == 2 && !args[0].equalsIgnoreCase("add")) {
                    return startingWith(plugin.store().stopNames(player.getUniqueId()), args[1]);
                }
                return List.of();
            }

            @Override
            public String permission() {
                return Permissions.USE;
            }
        };
    }

    // ------------------------------------------------------------------ /groute

    public static BasicCommand route(GhastLines plugin) {
        return new BasicCommand() {
            @Override
            public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
                Player player = playerOrRefuse(source.getSender());
                if (player == null) {
                    return;
                }
                if (args.length == 0) {
                    new RoutesMenu(plugin, player).open();
                    return;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "create", "new" -> createRoute(plugin, player, rest(args, 1));
                    case "delete", "remove" -> deleteRoute(plugin, player, rest(args, 1));
                    case "add" -> addToRoute(plugin, player, args);
                    case "drop" -> dropFromRoute(plugin, player, args);
                    case "loop" -> loopRoute(plugin, player, rest(args, 1));
                    case "share" -> shareRoute(plugin, player, rest(args, 1));
                    case "list" -> listRoutes(plugin, player, rest(args, 1));
                    case "edit" -> editRoute(plugin, player, rest(args, 1));
                    case "start", "run" -> startRoute(plugin, player, args);
                    case "stop" -> stopRoute(plugin, player, rest(args, 1));
                    case "help" -> routeHelp(player);
                    default -> Text.tell(player, Text.error(
                            "No such thing as /groute <what>. Try /groute help.",
                            Text.arg("what", args[0])));
                }
            }

            @Override
            public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                                      String @NotNull [] args) {
                if (!(source.getSender() instanceof Player player)) {
                    return List.of();
                }
                if (args.length <= 1) {
                    return startingWith(List.of("create", "delete", "add", "drop", "loop", "share",
                            "list", "edit", "start", "stop", "help"), args.length == 0 ? "" : args[0]);
                }
                String verb = args[0].toLowerCase(Locale.ROOT);
                if (args.length == 2 && !verb.equals("create") && !verb.equals("new")) {
                    return startingWith(plugin.store().routeNames(player.getUniqueId()), args[1]);
                }
                if (args.length == 3 && verb.equals("add")) {
                    return startingWith(plugin.store().stopNames(player.getUniqueId()), args[2]);
                }
                if (args.length == 3 && (verb.equals("start") || verb.equals("run"))) {
                    return startingWith(plugin.claims().tokens(player), args[2]);
                }
                return List.of();
            }

            @Override
            public String permission() {
                return Permissions.USE;
            }
        };
    }

    // ------------------------------------------------------------------ the ghast verbs

    private static void claim(GhastLines plugin, Player player) {
        Optional<HappyGhast> beside = plugin.claims().beside(player);
        if (beside.isEmpty()) {
            Text.tell(player, Text.error("No happy ghast within <radius> blocks. Stand next to the one "
                    + "you mean, or ride it.", Text.num("radius", Math.round(Claims.CLAIM_RADIUS))));
            return;
        }
        HappyGhast ghast = beside.get();
        switch (plugin.claims().claim(player, ghast)) {
            case CLAIMED -> {
                String named = Claims.plainName(ghast);
                Text.tell(player, Text.success(named.isBlank()
                        ? "Claimed. It has no name tag, so it answers to '<token>' — put a name tag on it "
                                + "and it will answer to that instead."
                        : "Claimed <name>.",
                        Text.arg("name", named),
                        Text.arg("token", plugin.store().claimOf(ghast.getUniqueId())
                                .map(plugin.claims()::token).orElse(""))));
            }
            case ALREADY_YOURS -> Text.tell(player, Text.warn("That one is already yours."));
            case TAKEN -> Text.tell(player, Text.error("That ghast belongs to somebody else."));
            case AT_LIMIT -> Text.tell(player, Text.error(
                    "You already have <limit> ghast(s). Use /ghast release to make room.",
                    Text.num("limit", plugin.options().maxGhasts())));
        }
    }

    private static void release(GhastLines plugin, Player player, String token) {
        Optional<GhastClaim> mine = which(plugin, player, token);
        if (mine.isEmpty()) {
            return;
        }
        plugin.claims().release(mine.get().ghast());
        Text.tell(player, Text.success("<ghast> is no longer yours.",
                Text.part("ghast", plugin.claims().displayName(mine.get()))));
    }

    private static void rename(GhastLines plugin, Player player, String[] args) {
        if (args.length < 2) {
            Text.tell(player, Text.error("Usage: /ghast rename <ghast> <new name>"));
            return;
        }
        Optional<GhastClaim> mine = which(plugin, player, args[1]);
        if (mine.isEmpty()) {
            return;
        }
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        plugin.claims().rename(mine.get(), name);
        Text.tell(player, name.isBlank()
                ? Text.success("Its name tag is gone; it answers to its id now.")
                : Text.success("Renamed to <name>.", Text.arg("name", name)));
    }

    private static void summon(GhastLines plugin, Player player, String token) {
        which(plugin, player, token).ifPresent(claim -> plugin.flights().summon(player, claim));
    }

    private static void send(GhastLines plugin, Player player, String[] args) {
        if (args.length < 2) {
            Text.tell(player, Text.error("Usage: /ghast send <destination> [ghast]"));
            return;
        }
        Optional<Destination> destination = Destinations.resolve(plugin.store(), player, args[1]);
        if (destination.isEmpty()) {
            List<String> known = Destinations.suggest(plugin.store(), player, "");
            Text.tell(player, known.isEmpty()
                    ? Text.error("You have nowhere to send it yet — stand somewhere and use /gstop add.")
                    : Text.error("Nowhere called '<where>'. You can send it to: <list>",
                            Text.arg("where", args[1]), Text.arg("list", String.join(", ", known))));
            return;
        }
        which(plugin, player, args.length > 2 ? args[2] : "")
                .ifPresent(claim -> plugin.flights().send(player, claim, destination.get()));
    }

    private static void recall(GhastLines plugin, Player player, String token) {
        // An admin may stop anything in the air, by the short id the status board shows, because a ghast
        // stuck in somebody's build is a problem for whoever has to look at it rather than for its owner.
        if (!token.isBlank() && player.hasPermission(Permissions.ADMIN)) {
            Optional<Flight> anybodys = plugin.flights().active().stream()
                    .filter(flight -> flight.ghast().toString().startsWith(token.toLowerCase(Locale.ROOT)))
                    .findFirst();
            if (anybodys.isPresent()) {
                plugin.flights().cancelFor(anybodys.get().ghast(), "Stopped by " + player.getName() + ".");
                Text.tell(player, Text.success("Stopped it."));
                return;
            }
        }
        Optional<GhastClaim> mine = which(plugin, player, token);
        if (mine.isEmpty()) {
            return;
        }
        if (!plugin.flights().cancelFor(mine.get().ghast(), "Called off — it is waiting where it is.")) {
            Text.tell(player, Text.warn("That one is not flying anywhere."));
        }
    }

    private static void list(GhastLines plugin, Player player) {
        List<GhastClaim> mine = plugin.store().claimsOf(player.getUniqueId());
        if (mine.isEmpty()) {
            Text.tell(player, Text.info("You have no ghasts. Stand next to a happy ghast and use "
                    + "/ghast claim."));
            return;
        }
        player.sendMessage(Text.info("Your ghasts (<n>/<limit>):", Text.num("n", mine.size()),
                Text.arg("limit", Limits.describe(player, plugin.options().maxGhasts()))));
        for (GhastClaim claim : mine) {
            Optional<Flight> flight = plugin.flights().flightOf(claim.ghast());
            player.sendMessage(Text.raw("  <" + Text.SKY + "><ghast> <" + Text.MUTED + ">(<token>) — <what>",
                    Text.part("ghast", plugin.claims().displayName(claim)),
                    Text.arg("token", plugin.claims().token(claim)),
                    Text.arg("what", flight.map(in -> in.phase().label() + " → " + in.heading())
                            .orElse("parked in " + claim.world() + " at " + claim.coordinates()))));
        }
    }

    /**
     * The departures board.
     * <p>
     * Deliberately open to everybody, including the console, and deliberately showing every ghast in the air
     * rather than only the sender's: a transit network whose timetable is private is not a network, and "why
     * is there a ghast over my roof" is a question anybody can now answer for themselves.
     */
    private static void status(GhastLines plugin, CommandSender sender) {
        List<Flight> flights = plugin.flights().active();
        if (flights.isEmpty()) {
            sender.sendMessage(Text.info("Nothing in the air."));
            return;
        }
        sender.sendMessage(Text.info("In the air (<n>):", Text.num("n", flights.size())));
        for (Flight flight : flights) {
            Component name = plugin.store().claimOf(flight.ghast())
                    .map(plugin.claims()::displayName)
                    .orElse(Component.text(Claims.UNNAMED));
            sender.sendMessage(Text.raw("  <bar> ", Text.part("bar", FlightService.bar(flight.progress())))
                    .append(flight.describe(name))
                    .append(Text.raw(" <" + Text.MUTED + ">· <eta>s · <id>",
                            Text.num("eta", Steering.etaSeconds(flight.blocksLeft(), flight.blocksPerTick())),
                            Text.arg("id", flight.ghast().toString().substring(0, 8)))));
        }
    }

    // ------------------------------------------------------------------ the stop verbs

    private static void addStop(GhastLines plugin, Player player, String raw) {
        String name = Names.normalise(raw);
        if (name == null) {
            Text.tell(player, Text.error("A stop's name is <rule>.", Text.arg("rule", Names.requirement())));
            return;
        }
        TransitOptions options = plugin.options();
        boolean replacing = plugin.store().findStop(player.getUniqueId(), name).isPresent();
        if (!replacing && Limits.reached(player, plugin.store().stopCount(player.getUniqueId()), options.maxStops())) {
            Text.tell(player, options.maxStops() == 0
                    ? Text.error("Stops are switched off on this server.")
                    : Text.error("You already have <limit> stops. Use /gstop remove to make room.",
                            Text.num("limit", options.maxStops())));
            return;
        }
        boolean wasShared = plugin.store().findStop(player.getUniqueId(), name)
                .map(Stop::shared).orElse(false);
        plugin.store().putStop(player.getUniqueId(), player.getName(),
                Stop.of(name, player.getUniqueId(), player.getLocation(), wasShared,
                        System.currentTimeMillis()));
        Text.tell(player, Text.success(replacing ? "Moved stop '<name>' here." : "Stop '<name>' added.",
                Text.arg("name", name)));
    }

    private static void removeStop(GhastLines plugin, Player player, String raw) {
        Optional<Stop> removed = plugin.store().removeStop(player.getUniqueId(), raw);
        if (removed.isEmpty()) {
            unknownStop(plugin, player, raw);
            return;
        }
        List<String> affected = plugin.store().routesOf(player.getUniqueId()).stream()
                .filter(route -> route.stops().contains(removed.get().name()))
                .map(Route::name).toList();
        Text.tell(player, Text.success("Stop '<name>' removed.", Text.arg("name", removed.get().name())));
        if (!affected.isEmpty()) {
            // The route keeps naming it on purpose — see Route — so this is a warning, not a repair.
            Text.tell(player, Text.warn("These routes still call at it and will refuse to fly: <list>",
                    Text.arg("list", String.join(", ", affected))));
        }
    }

    private static void shareStop(GhastLines plugin, Player player, String raw) {
        Optional<Stop> stop = plugin.store().findStop(player.getUniqueId(), raw);
        if (stop.isEmpty()) {
            unknownStop(plugin, player, raw);
            return;
        }
        Stop flipped = stop.get().withShared(!stop.get().shared());
        plugin.store().putStop(player.getUniqueId(), player.getName(), flipped);
        Text.tell(player, flipped.shared()
                ? Text.success("'<name>' is now a public stop — anybody can fly to it.",
                        Text.arg("name", flipped.name()))
                : Text.success("'<name>' is private again.", Text.arg("name", flipped.name())));
    }

    /**
     * Gives a stop an alias — the free-text name it is shown under.
     * <p>
     * Separate from {@link #renameStop} on purpose: this changes what a stop is <em>called</em> and nothing
     * else, so no route, no tab completion and no other player's destination list has to be touched. Renaming
     * the key is the operation that has consequences, and it is a different word.
     */
    private static void labelStop(GhastLines plugin, Player player, String[] args) {
        if (args.length < 2) {
            Text.tell(player, Text.error("Usage: /gstop label <stop> <what to call it>  "
                    + "— with nothing after the stop, the alias is removed."));
            return;
        }
        Optional<Stop> stop = plugin.store().findStop(player.getUniqueId(), args[1]);
        if (stop.isEmpty()) {
            unknownStop(plugin, player, args[1]);
            return;
        }
        String alias = rest(args, 2);
        if (alias.length() > MAX_LABEL_LENGTH) {
            Text.tell(player, Text.error("That is longer than <n> characters.",
                    Text.num("n", MAX_LABEL_LENGTH)));
            return;
        }
        plugin.store().putStop(player.getUniqueId(), player.getName(), stop.get().withLabel(alias));
        Text.tell(player, alias.isBlank()
                ? Text.success("'<name>' is shown by its name again.", Text.arg("name", stop.get().name()))
                : Text.success("'<name>' is now shown as '<alias>'. You still type '<name>'.",
                        Text.arg("name", stop.get().name()), Text.arg("alias", alias)));
    }

    /**
     * Renames a stop, and every route that calls at it.
     * <p>
     * The routes are the reason this is a command rather than "delete it and make a new one": a route holds
     * stop names, so renaming a stop out from under one would leave a line that refuses to fly and a player
     * with no idea why. Both halves happen here, or neither does.
     */
    private static void renameStop(GhastLines plugin, Player player, String[] args) {
        if (args.length < 3) {
            Text.tell(player, Text.error("Usage: /gstop rename <stop> <new name>"));
            return;
        }
        Optional<Stop> stop = plugin.store().findStop(player.getUniqueId(), args[1]);
        if (stop.isEmpty()) {
            unknownStop(plugin, player, args[1]);
            return;
        }
        String wanted = Names.normalise(args[2]);
        if (wanted == null) {
            Text.tell(player, Text.error("A stop's name is <rule>.", Text.arg("rule", Names.requirement())));
            return;
        }
        if (wanted.equals(stop.get().name())) {
            Text.tell(player, Text.warn("That is already its name."));
            return;
        }
        if (plugin.store().findStop(player.getUniqueId(), wanted).isPresent()) {
            Text.tell(player, Text.error("You already have a stop called '<name>'.",
                    Text.arg("name", wanted)));
            return;
        }

        plugin.store().removeStop(player.getUniqueId(), stop.get().name());
        plugin.store().putStop(player.getUniqueId(), player.getName(), stop.get().withName(wanted));

        List<String> touched = new ArrayList<>();
        for (Route route : plugin.store().routesOf(player.getUniqueId())) {
            if (!route.stops().contains(stop.get().name())) {
                continue;
            }
            List<String> renamed = route.stops().stream()
                    .map(called -> called.equals(stop.get().name()) ? wanted : called)
                    .toList();
            plugin.store().putRoute(player.getUniqueId(), player.getName(),
                    new Route(route.name(), route.owner(), renamed, route.loop(), route.shared(),
                            route.createdAt()));
            touched.add(route.name());
        }

        Text.tell(player, Text.success("'<old>' is now '<new>'.",
                Text.arg("old", stop.get().name()), Text.arg("new", wanted)));
        if (!touched.isEmpty()) {
            Text.tell(player, Text.info("Also fixed the routes that call at it: <list>",
                    Text.arg("list", String.join(", ", touched))));
        }
    }

    private static void listStops(GhastLines plugin, Player player) {
        List<Stop> mine = plugin.store().stopsOf(player.getUniqueId());
        if (mine.isEmpty()) {
            Text.tell(player, Text.info("You have no stops. Stand somewhere and use /gstop add <name>."));
            return;
        }
        player.sendMessage(Text.info("Your stops (<n>/<limit>):", Text.num("n", mine.size()),
                Text.arg("limit", Limits.describe(player, plugin.options().maxStops()))));
        for (Stop stop : mine) {
            player.sendMessage(Text.raw("  <" + Text.TEXT + "><name><alias> <" + Text.MUTED
                            + ">— <world>, <where><shared>",
                    Text.arg("name", stop.name()),
                    Text.arg("alias", stop.label().isBlank() ? "" : " \"" + stop.label() + "\""),
                    Text.arg("world", stop.world()),
                    Text.arg("where", stop.coordinates()),
                    Text.arg("shared", stop.shared() ? " (public)" : "")));
        }
    }

    private static void unknownStop(GhastLines plugin, Player player, String typed) {
        List<String> names = plugin.store().stopNames(player.getUniqueId());
        Text.tell(player, names.isEmpty()
                ? Text.error("You have no stops yet — stand somewhere and use /gstop add <name>.")
                : Text.error("No stop called '<name>'. You have: <list>", Text.arg("name", typed),
                        Text.arg("list", String.join(", ", names))));
    }

    // ------------------------------------------------------------------ the route verbs

    private static void createRoute(GhastLines plugin, Player player, String raw) {
        String name = Names.normalise(raw);
        if (name == null) {
            Text.tell(player, Text.error("A route's name is <rule>.", Text.arg("rule", Names.requirement())));
            return;
        }
        if (plugin.store().findRoute(player.getUniqueId(), name).isPresent()) {
            Text.tell(player, Text.error("You already have a route called '<name>'.",
                    Text.arg("name", name)));
            return;
        }
        if (Limits.reached(player, plugin.store().routeCount(player.getUniqueId()), plugin.options().maxRoutes())) {
            Text.tell(player, plugin.options().maxRoutes() == 0
                    ? Text.error("Routes are switched off on this server.")
                    : Text.error("You already have <limit> routes. Use /groute delete to make room.",
                            Text.num("limit", plugin.options().maxRoutes())));
            return;
        }
        plugin.store().putRoute(player.getUniqueId(), player.getName(),
                Route.empty(name, player.getUniqueId(), System.currentTimeMillis()));
        Text.tell(player, Text.success("Route '<name>' created — now add at least <n> stops with "
                + "/groute add <name> <stop>.", Text.arg("name", name), Text.num("n", Route.MINIMUM_STOPS)));
    }

    private static void deleteRoute(GhastLines plugin, Player player, String raw) {
        Optional<Route> removed = plugin.store().removeRoute(player.getUniqueId(), raw);
        if (removed.isEmpty()) {
            unknownRoute(plugin, player, raw);
            return;
        }
        plugin.flights().active().stream()
                .filter(flight -> removed.get().name().equals(flight.routeName())
                        && flight.owner().equals(player.getUniqueId()))
                .forEach(flight -> plugin.flights().cancelFor(flight.ghast(),
                        "Its route was deleted."));
        Text.tell(player, Text.success("Route '<name>' deleted.", Text.arg("name", removed.get().name())));
    }

    private static void addToRoute(GhastLines plugin, Player player, String[] args) {
        if (args.length < 3) {
            Text.tell(player, Text.error("Usage: /groute add <route> <stop>"));
            return;
        }
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), args[1]);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, args[1]);
            return;
        }
        Optional<Stop> stop = plugin.store().findStop(player.getUniqueId(), args[2]);
        if (stop.isEmpty()) {
            unknownStop(plugin, player, args[2]);
            return;
        }
        Route longer = route.get().plus(stop.get().name());
        if (longer.stops().size() == route.get().stops().size()) {
            Text.tell(player, route.get().isFull()
                    ? Text.error("'<route>' already has <n> stops, which is as many as a route may have.",
                            Text.arg("route", route.get().name()), Text.num("n", Route.MAXIMUM_STOPS))
                    : Text.warn("'<stop>' is already the last stop on '<route>'.",
                            Text.arg("stop", stop.get().name()), Text.arg("route", route.get().name())));
            return;
        }
        plugin.store().putRoute(player.getUniqueId(), player.getName(), longer);
        Text.tell(player, Text.success("'<route>' now calls at <list>.",
                Text.arg("route", longer.name()), Text.arg("list", String.join(" → ", longer.stops()))));
    }

    private static void dropFromRoute(GhastLines plugin, Player player, String[] args) {
        if (args.length < 3) {
            Text.tell(player, Text.error("Usage: /groute drop <route> <position>  (1 is the first stop)"));
            return;
        }
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), args[1]);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, args[1]);
            return;
        }
        int position;
        try {
            position = Integer.parseInt(args[2]);
        } catch (NumberFormatException notANumber) {
            // By position and not by name, because a route may legitimately call at the same stop twice.
            Text.tell(player, Text.error("<what> is not a position. The stops of '<route>' are: <list>",
                    Text.arg("what", args[2]), Text.arg("route", route.get().name()),
                    Text.arg("list", numbered(route.get()))));
            return;
        }
        Route shorter = route.get().minus(position - 1);
        if (shorter.stops().size() == route.get().stops().size()) {
            Text.tell(player, Text.error("'<route>' has no stop <n>. Its stops are: <list>",
                    Text.arg("route", route.get().name()), Text.num("n", position),
                    Text.arg("list", numbered(route.get()))));
            return;
        }
        plugin.store().putRoute(player.getUniqueId(), player.getName(), shorter);
        Text.tell(player, Text.success("Dropped stop <n>. '<route>' now calls at <list>.",
                Text.num("n", position), Text.arg("route", shorter.name()),
                Text.arg("list", shorter.stops().isEmpty() ? "nothing"
                        : String.join(" → ", shorter.stops()))));
    }

    private static void loopRoute(GhastLines plugin, Player player, String raw) {
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), raw);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, raw);
            return;
        }
        Route flipped = route.get().withLoop(!route.get().loop());
        plugin.store().putRoute(player.getUniqueId(), player.getName(), flipped);
        Text.tell(player, flipped.loop()
                ? Text.success("'<name>' is a loop now — it will keep going round until you recall it.",
                        Text.arg("name", flipped.name()))
                : Text.success("'<name>' is one way now — it stops at the last stop.",
                        Text.arg("name", flipped.name())));
    }

    private static void shareRoute(GhastLines plugin, Player player, String raw) {
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), raw);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, raw);
            return;
        }
        Route flipped = route.get().withShared(!route.get().shared());
        plugin.store().putRoute(player.getUniqueId(), player.getName(), flipped);
        Text.tell(player, flipped.shared()
                ? Text.success("'<name>' is now a public line — anybody can see it and ride it.",
                        Text.arg("name", flipped.name()))
                : Text.success("'<name>' is private again.", Text.arg("name", flipped.name())));
    }

    private static void listRoutes(GhastLines plugin, Player player, String raw) {
        if (!raw.isBlank()) {
            Optional<Route> one = plugin.store().findRoute(player.getUniqueId(), raw);
            if (one.isEmpty()) {
                unknownRoute(plugin, player, raw);
                return;
            }
            player.sendMessage(Text.info("'<name>' — <kind>, <visibility>:", Text.arg("name", one.get().name()),
                    Text.arg("kind", one.get().kind()),
                    Text.arg("visibility", one.get().shared() ? "public" : "private")));
            player.sendMessage(Text.raw("  <" + Text.TEXT + "><list>", Text.arg("list", numbered(one.get()))));
            return;
        }
        List<Route> mine = plugin.store().routesOf(player.getUniqueId());
        List<Route> theirs = plugin.store().sharedRoutes().stream()
                .filter(route -> !route.owner().equals(player.getUniqueId())).toList();
        if (mine.isEmpty() && theirs.isEmpty()) {
            Text.tell(player, Text.info("There are no routes yet. Make one with /groute create <name>."));
            return;
        }
        if (!mine.isEmpty()) {
            player.sendMessage(Text.info("Your routes (<n>/<limit>):", Text.num("n", mine.size()),
                    Text.arg("limit", Limits.describe(player, plugin.options().maxRoutes()))));
            mine.forEach(route -> player.sendMessage(routeLine(route, "")));
        }
        if (!theirs.isEmpty()) {
            player.sendMessage(Text.info("Public lines:"));
            theirs.forEach(route -> player.sendMessage(
                    routeLine(route, " by " + plugin.store().nameOf(route.owner()))));
        }
    }

    private static Component routeLine(Route route, String suffix) {
        return Text.raw("  <" + Text.TEXT + "><name> <" + Text.MUTED + ">— <kind>, <n> stops<by>",
                Text.arg("name", route.name()), Text.arg("kind", route.kind()),
                Text.num("n", route.stops().size()), Text.arg("by", suffix));
    }

    private static void editRoute(GhastLines plugin, Player player, String raw) {
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), raw);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, raw);
            return;
        }
        new RouteMenu(plugin, player, route.get().name()).open();
    }

    private static void startRoute(GhastLines plugin, Player player, String[] args) {
        if (args.length < 2) {
            Text.tell(player, Text.error("Usage: /groute start <route> [ghast]"));
            return;
        }
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), args[1]);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, args[1]);
            return;
        }
        which(plugin, player, args.length > 2 ? args[2] : "")
                .ifPresent(claim -> plugin.flights().runRoute(player, claim, route.get()));
    }

    private static void stopRoute(GhastLines plugin, Player player, String raw) {
        Optional<Route> route = plugin.store().findRoute(player.getUniqueId(), raw);
        if (route.isEmpty()) {
            unknownRoute(plugin, player, raw);
            return;
        }
        List<Flight> working = plugin.flights().active().stream()
                .filter(flight -> route.get().name().equals(flight.routeName())
                        && flight.owner().equals(player.getUniqueId()))
                .toList();
        if (working.isEmpty()) {
            Text.tell(player, Text.warn("Nothing is working '<name>'.", Text.arg("name", route.get().name())));
            return;
        }
        working.forEach(flight -> plugin.flights().cancelFor(flight.ghast(),
                "Taken out of service on " + route.get().name() + "."));
    }

    private static void unknownRoute(GhastLines plugin, Player player, String typed) {
        List<String> names = plugin.store().routeNames(player.getUniqueId());
        Text.tell(player, names.isEmpty()
                ? Text.error("You have no routes yet — make one with /groute create <name>.")
                : Text.error("No route called '<name>'. You have: <list>", Text.arg("name", typed),
                        Text.arg("list", String.join(", ", names))));
    }

    /** "1 base → 2 mine → 3 market", so a position can be typed at {@code /groute drop}. */
    private static String numbered(Route route) {
        if (route.stops().isEmpty()) {
            return "nothing yet";
        }
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < route.stops().size(); index++) {
            parts.add((index + 1) + " " + route.stops().get(index));
        }
        return String.join(" → ", parts);
    }

    // ------------------------------------------------------------------ shared

    private static Player playerOrRefuse(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Text.error("Ghasts belong to a player — this needs to be run in game. "
                + "/ghast status works from here."));
        return null;
    }

    /**
     * Which of this player's ghasts they mean, or a refusal that says what they could have meant.
     * <p>
     * The empty-argument case is the interesting one: one ghast and no argument is not ambiguous, and making
     * somebody name their only ghast would be a command that refuses to do the only thing it could do.
     */
    private static Optional<GhastClaim> which(GhastLines plugin, Player player, String token) {
        Optional<GhastClaim> found = plugin.claims().byToken(player, token);
        if (found.isPresent()) {
            return found;
        }
        List<String> tokens = plugin.claims().tokens(player);
        if (tokens.isEmpty()) {
            Text.tell(player, Text.error("You have no ghasts. Stand next to a happy ghast and use "
                    + "/ghast claim."));
        } else if (token.isBlank()) {
            Text.tell(player, Text.error("Which one? You have: <list>",
                    Text.arg("list", String.join(", ", tokens))));
        } else {
            Text.tell(player, Text.error("No ghast of yours called '<name>'. You have: <list>",
                    Text.arg("name", token), Text.arg("list", String.join(", ", tokens))));
        }
        return Optional.empty();
    }

    private static String rest(String[] args, int from) {
        return args.length <= from ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private static List<String> startingWith(Collection<String> all, String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        return all.stream().filter(one -> one.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ help

    private static void ghastHelp(Player player) {
        player.sendMessage(Text.info("Ghasts — everything here is also in the menu, /ghast:"));
        help(player, "/ghast claim", "claim the happy ghast you are standing next to or riding");
        help(player, "/ghast summon [ghast]", "have it fly to you, wherever you are");
        help(player, "/ghast send <where> [ghast]", "send it — and anyone aboard — to a stop or a home");
        help(player, "/ghast recall [ghast]", "call the flight off; it waits where it is");
        help(player, "/ghast rename <ghast> <name>", "write its name tag");
        help(player, "/ghast list", "your ghasts, and what each is doing");
        help(player, "/ghast status", "every ghast in the air, with how far along it is");
        help(player, "/ghast release [ghast]", "give one up");
    }

    private static void stopHelp(Player player) {
        player.sendMessage(Text.info("Stops — also in the menu, /gstop:"));
        help(player, "/gstop add <name>", "make where you are standing a stop");
        help(player, "/gstop remove <name>", "forget one");
        help(player, "/gstop share <name>", "let everybody fly to it, or stop letting them");
        help(player, "/gstop label <name> <text>", "an alias to show it under — spaces and capitals "
                + "allowed; you still type the short name");
        help(player, "/gstop rename <name> <new>", "change the name you type, and every route using it");
        help(player, "/gstop list", "your stops");
    }

    private static void routeHelp(Player player) {
        player.sendMessage(Text.info("Routes — also in the menu, /groute:"));
        help(player, "/groute create <name>", "start a new line");
        help(player, "/groute add <route> <stop>", "add a stop to the end of it");
        help(player, "/groute drop <route> <position>", "take one out, by position");
        help(player, "/groute loop <route>", "round and round, or one way");
        help(player, "/groute share <route>", "publish it, or take it private again");
        help(player, "/groute start <route> [ghast]", "put a ghast into service on it");
        help(player, "/groute stop <route>", "take it out of service");
        help(player, "/groute list [route]", "your lines, everybody's public ones, or one in detail");
    }

    private static void help(Player player, String command, String what) {
        player.sendMessage(Text.raw("  <" + Text.SKY + "><command> <" + Text.MUTED + ">— <what>",
                Text.arg("command", command), Text.arg("what", what)));
    }
}
