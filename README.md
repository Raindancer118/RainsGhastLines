# Rain's Ghast Lines

Happy ghasts as a transport network, rather than as a thing you have to already be sitting on.

```
/ghast                        your ghasts, stops, lines and flights, as a menu
/ghast claim                  claim the one you are next to, or riding
/ghast summon [ghast]         it flies to you — really flies, from wherever it was parked
/ghast send <where> [ghast]   send it, and anyone aboard, to a stop
/ghast recall [ghast]         call the flight off; it waits where it is
/ghast rename <ghast> <name>  write its name tag
/ghast list                   your ghasts, and what each is doing
/ghast status                 the departures board: every ghast in the air
/ghast release [ghast]        give one up

/gstop add base               where you are standing becomes a stop
/gstop share base             now anybody may fly to it
/gstop remove base  ·  /gstop list

/groute create commute        a new line
/groute add commute base      add a stop to the end of it
/groute drop commute 2        take one out, by position
/groute loop commute          round and round, or one way
/groute share commute         publish it
/groute start commute [ghast] put a ghast into service on it
/groute stop commute  ·  /groute list [route]
```

Every command has a menu, and the bare form of each opens it. Neither front end can do something the
other cannot — that is a rule, not a coincidence: the menu's buttons run the commands wherever the
command already knows how to refuse.

## A ghast's name is its name tag

Put one on and it answers to that. `Bus 12` becomes the token `bus_12`; a ghast with no name tag
answers to the first eight characters of its id, which `/ghast list` shows. `/ghast rename` writes the
tag, so the name floats over the ghast for everybody and survives without this plugin.

Two ghasts named the same are ambiguous. `/ghast list` shows the tokens so you can see it, the id form
always works, and renaming one fixes it.

## It flies the route

A summons is not a teleport. The ghast climbs to a height that clears the ground under it, crosses at
`speed` blocks a second, comes down onto you and hovers for `boarding-seconds` while you get on —
or attach a boat, or leash something to it. Rising terrain raises the cruise height as it arrives, so
it goes over a mountain rather than into it.

Progress goes on a **boss bar** for whoever called it, whoever owns the ghast and whoever is riding it.
`progress-in-boss-bar: false` puts the same line, with a text bar, on the action bar instead.

Two things make a long flight work at all:

- A flight holds **plugin chunk tickets** two chunks around the ghast. An entity does not load the
  world around it — without them the ghast stops being ticked the moment it leaves what a player has
  loaded, and the flight freezes in mid-air. Tickets are counted, so two ghasts on the same line do
  not unload the chunks the other is still flying through.
- The ghast's **AI is switched off** for the duration. A happy ghast left to itself drifts, and a
  drifting ghast fights every velocity the engine sets.

Both are given back on every ending there is: arrival, recall, the ghast dying, and the plugin
shutting down. A leaked chunk ticket is a chunk loaded for the rest of the server's life.

A ghast that cannot get through — wedged under an overhang, walled in — is noticed by having moved
less than two blocks in five seconds, and the flight ends saying so rather than hovering there for ever.

A player in the harness still has the reins: vanilla lets whoever is riding steer, and their input is
added to what the autopilot sets. That is the right way round — you can always take over, and keeping
your hands off gets you to the stop.

## Stops and lines

**Stops** are yours; `share` publishes one, and a published stop appears in everybody else's
destination list as `owner:stop`.

**Lines** are two or more of *your own* stops, in an order you can reorder, flown one way or as a loop.
A loop keeps going round until it is recalled. Only your own stops, deliberately: a line whose stops
belonged to other people would change route whenever one of them moved or unshared a stop, and its
owner could do nothing about it.

`share` publishes a line, and anybody may then put *their* ghast on it — a published timetable that
only its author can run is a timetable with one bus. The stops stay the author's, so it still goes
where they said it goes.

`/ghast status` needs no permission at all, by design: a transit network with a private timetable is
not a network, and "why is there a ghast over my roof" is a question anybody can now answer for
themselves.

## Configuration

Everything is in `config.yml`, with the reasoning next to each key. The two worth knowing about:

- **`cruise-clearance`** (default 12) is what makes a flight clear the terrain. Low numbers on hilly
  ground mean a lot of climbing.
- **`allow-cross-world`** is **off**. There is no route between two worlds for a ghast to fly, so a
  cross-world flight begins with it — and everybody aboard, passengers retained explicitly — appearing
  high above the destination and flying down. It is the one part of this plugin where the ghast does
  not really make the journey, so a server has to say yes to it.

A nonsense value is clamped into range rather than refused: a speed of `-5` is a typo, and a plugin
that will not start over one is worse than a plugin that flies slowly.

## The file

`plugins/RainsGhastLines/transit.yml` holds every stop, line and claim, keyed by player and by ghast.

It is written by a single thread this plugin owns, so a save never lands in the middle of a tick and
two saves can never interleave into a file that is half of each; and it is written to a temporary file
that is moved into place, so a crash mid-write costs the last change rather than everybody's network.

A claim is **not** stored on the entity. A `PersistentDataContainer` would be tidier and cannot be read
while the ghast is in an unloaded chunk — which is the state a parked ghast is in nearly all of the
time, and exactly the state `/ghast list` and `/ghast summon` have to work in. The claim remembers
where the ghast was last seen, so a summons can load that chunk and find it.

## Permissions

| Node | Default | What it does |
|---|---|---|
| `ghastlines.use` | everyone | The commands and the menus |
| `ghastlines.unlimited` | nobody | No limits on ghasts, stops or routes, and no summon cooldown |
| `ghastlines.admin` | operators | Stop anybody's flight, from the board or `/ghast recall <id>` |

One `unlimited` node rather than three, because "this rank is not counted" is the decision an admin is
actually making, and three nodes would mostly be granted together and occasionally, accidentally, not.

## Folia

`folia-supported: true`, and meant. `Bukkit.getScheduler()` is never touched: a flight is a repeating
task on its ghast's own `EntityScheduler`, which runs on the only thread allowed to touch that entity
and cancels itself when the entity goes away. Chunk tickets are taken out on the
`GlobalRegionScheduler`, because they are server-wide bookkeeping for chunks in regions the flight has
not reached yet. Nothing reads a block in a region it is not running on — the destination's own Y
stands in for the ground at the far end, which is exactly what it is: somebody stood there to make the
stop.

## Already running Rain's SMP Core?

This plugin is folded into it as the `ghasts` module — same commands, same permissions, same data,
under the host's brand, and with your **homes** offered as destinations (`/ghast send home:base`).
Install one or the other, not both. If both are present, the module stands down and Rain's SMP Core
renames this jar rather than deleting it, so putting it back is a complete rollback.

Exactly one file differs between the two builds: the main class. Everything else reaches what it needs
through two seams — `Chrome` for how the plugin signs and delivers what it says, and `Destinations` for
places this plugin does not own.

## Building

```
JAVA_HOME=/usr/lib/jvm/java-25-temurin mvn verify   # 53 tests → target/RainsGhastLines-1.0.0.jar
```

Java 25, because Paper 26.2 ships class files at version 69 and requires it at runtime.

## Verified

Against a real Paper 26.2-82 server with a headless player driving it, **0 exceptions**: a ghast
claimed, two stops made 200 blocks apart, called — it climbed to y −48 over ground at −60, crossed,
descended onto the player and waited — a two-stop loop line run and taken out of service, a name tag
written and the token following it, all four menus opened and read back slot by slot, and
`transit.yml` reloading unchanged after a restart.
