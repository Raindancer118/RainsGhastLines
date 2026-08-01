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

## It flies the route, and it navigates

A summons is not a teleport. The ghast flies at **its own speed** — `FLYING_SPEED` read off the entity and
scaled by `speed-percent`, so 100 means "as fast as a happy ghast flies" and this plugin has no opinion about
what that is in blocks per second. It climbs *while* flying rather than rising like a lift, holds a cruise
line kept clear of the terrain **ahead** of it, and comes down a glide slope three blocks long for every block
of height to lose, so it arrives level with the stop instead of stopping dead above it and sinking. Every
heading change is rate-limited, so it banks into turns and looks where it is going.

Things in the way are answered in order of how little they cost:

| In the way | What it does |
|---|---|
| A hill, a ridge, rising ground | The cruise line rises to clear it, measured **along the route** — not underneath, which is ground already crossed |
| A wall or tower with room beside it | Goes **round** it, in a shallow curve. A five-block tower is not worth climbing over |
| A wall with no way round | Climbs over it |
| A roof, an overhang, a hangar | Cannot climb, so it flies out from under first and climbs once clear |
| None of that works | Searches the area for a way out — breadth-first over four-block cells, a ghast's width — and flies the corners of the route it finds |
| Nowhere out at all | Gives up, says so, and **sets down** on safe ground nearby, naming the coordinates |

A ghast that has stopped getting anywhere — under two blocks in five seconds — is what triggers the last two.

It lands **five blocks in front of you**, along the way you are looking, rather than on your head: a happy
ghast is four blocks across, and one that arrives on the spot you are standing on fills the screen. Once
somebody is in the harness the engine lets go of the controls, so a ghast you have just boarded is a ghast you
can fly.

Progress goes on a **boss bar** for whoever called it, whoever owns the ghast and whoever is riding it. Stops
on a line are announced **to the people aboard** — a four-stop loop would otherwise tell its owner where it is
nine times a minute — while a summons tells whoever called it, because being told is the point of calling it.

Two things make a long flight work at all:

- A flight holds **plugin chunk tickets** three chunks around the ghast. An entity does not load the world
  around it — without them the ghast stops being ticked the moment it leaves what a player has loaded, and the
  flight freezes in mid-air. Tickets are counted, so two ghasts on the same line do not unload the chunks the
  other is still flying through.
- The ghast's **AI is switched off** for the duration. A happy ghast left to itself drifts, and a drifting
  ghast fights every velocity the engine sets.

Both are given back on every ending there is: arrival, recall, the ghast dying, and the plugin shutting down.
A leaked chunk ticket is a chunk loaded for the rest of the server's life. The AI comes back on when the
flight ends, on purpose — a parked ghast bobs and drifts like the animal it is rather than standing there like
a statue, and a summons still finds it because its position is written down at the instant its chunk unloads,
which is the last moment anybody can ask.

A lead tied from a happy ghast to a boat is not allowed to snap when somebody steps into the boat. Vanilla's
distance check fires in that same tick and unties it, which is precisely the moment you needed it; carrying
somebody in a boat is one of the two things this plugin exists for, so that one check is refused when the
holder is a happy ghast. A lead a player unties is untied.

## Stops and lines

**Stops** are yours; `share` publishes one, and a published stop appears in everybody else's destination list
as `owner:stop`. A stop's name is what you type — lower case, no spaces, because it is a command argument —
and `/gstop label <stop> <text>` gives it an **alias** to be displayed under, with capitals and spaces and
anything else you like. Nothing looks a stop up by its alias: a second name you could also type would be a
second thing to disagree with the first. `/gstop rename` changes the typed name instead, and fixes up every
route that calls at it in the same breath.

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
- **`speed-percent`** (default 100) scales the ghast's *own* flying speed. There is deliberately no
  blocks-per-second setting: the animal already has a speed, and inventing a second one made the flight feel
  like a plugin.
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
JAVA_HOME=/usr/lib/jvm/java-25-temurin mvn verify   # 53 tests → target/RainsGhastLines-1.0.1.jar
```

Java 25, because Paper 26.2 ships class files at version 69 and requires it at runtime.

## Verified

Against a real Paper 26.2-82 server with a headless player driving it (mineflayer through
ViaVersion/ViaBackwards), **0 exceptions**, on an obstacle course built on cleared ground:

- a **25-block wall** across the route — the ghast went round the end of it in a curve, glided down and
  arrived at the aim point;
- a ghast parked under a **40-block-wide roof** eight blocks above it — it flew out from under, climbed over
  the roof, crossed 120 blocks and landed by the player;
- a **five-block tower** on the line — ignored, because the cruise height already cleared it;
- a name tag written in game changing both the name and the token (`Bus 12` → `bus_12`);
- all four menus opening and read back slot by slot, and `transit.yml` reloading unchanged after a restart.

The landing was wrong twice before it was right, and both are pinned by tests now: it measured the ground
*beneath* itself and flew into walls it had already passed, and then it hung ten blocks above the player
bobbing, because the glide slope and the "you are too low" reflex were fighting each other once per tick.

**Not verified in game:** a ghast sealed in with no way out at all — the "give up and set down nearby" path.
Both halves are unit-tested, and every box built to test it turned out to have a gap the search legitimately
found.
