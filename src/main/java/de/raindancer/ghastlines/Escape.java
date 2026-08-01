package de.raindancer.ghastlines;

import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finding a way out of somewhere — a cliff overhang, a cave mouth, a courtyard, a tunnel.
 *
 * <h2>Why this exists when the flight is not pathfound</h2>
 * Crossing a hundred blocks of open sky needs no path search, and doing one for it would be the mistake this
 * plugin deliberately does not make. Getting out from under a cliff is the opposite problem: it is small,
 * it is local, and there is no answer at all without looking around — "climb" is wrong under a roof and
 * "forward" is wrong at a wall, and a ghast that only knows those two sits there until it is given up on.
 * So the long haul is flown and the short escape is searched, each with the tool that suits it.
 *
 * <h2>The search</h2>
 * Breadth-first over a lattice of {@link #STEP}-block cells, because a happy ghast is four blocks across and
 * anything finer would be searching gaps it cannot fit through anyway. A cell is passable when the ghast's
 * whole body fits in it; the goal is the nearest cell with open air above it, which is what "out" means to
 * something that flies. Breadth-first gives the nearest way out rather than the best one, which is the right
 * trade for something that runs while a ghast hovers waiting for an answer.
 *
 * <h2>Why the result is smoothed</h2>
 * A lattice path is a staircase, and a ghast flying a staircase looks like a ghast having a fit. Collinear
 * waypoints are dropped, so what comes out is a handful of long straight runs with turns between them — and
 * the turns themselves are rounded off by {@link Steering#smooth}, which is what makes it read as flying
 * rather than as a sequence of moves.
 *
 * <p>The world arrives as a {@link Space}, so the whole search is testable against a grid drawn in a string.
 */
public final class Escape {

    /** Lattice spacing: a happy ghast's own width, so a passable cell is one it fits in. */
    public static final int STEP = 4;

    /** How far out the search will look, in blocks. Beyond this, the ghast is not stuck, it is lost. */
    public static final int DEFAULT_RADIUS = 32;

    /**
     * How far up a cell has to be clear to count as "out".
     *
     * <h2>Why this is so much more than a ghast's height</h2>
     * The first version asked for eight blocks of air above the ghast, and a ghast standing under a wide stone
     * ledge with a twenty-block gap under it passed the test without being anywhere near outside — so the
     * search "found a way out" that led nowhere and the ghast set off deeper under the cliff. Being out means
     * being able to climb, and being able to climb means nothing overhead for a long way, not for a few blocks.
     */
    public static final int OPEN_SKY = 40;

    /** A hard cap on cells visited, so a search inside a cave system cannot cost a tick. */
    private static final int MAX_VISITED = 4000;

    /** Whether the block at these coordinates is air as far as a flying four-block animal cares. */
    @FunctionalInterface
    public interface Space {
        boolean isFree(int x, int y, int z);
    }

    private record Cell(int x, int y, int z) {
    }

    private Escape() {
    }

    /**
     * A way out from where the ghast is, as waypoints to fly through in order.
     *
     * @return the waypoints, or an empty list when there is no way out within {@code radius}
     */
    public static List<Vector> route(Space space, Vector from, int radius) {
        Cell start = new Cell((int) Math.floor(from.getX()), (int) Math.floor(from.getY()),
                (int) Math.floor(from.getZ()));

        Deque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> cameFrom = new HashMap<>();
        Set<Cell> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);

        Cell found = null;
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_VISITED) {
            Cell cell = queue.poll();
            visited++;
            // The start is where it is stuck, so it never counts as the way out however open it looks.
            if (!cell.equals(start) && isOut(space, cell)) {
                found = cell;
                break;
            }
            for (Cell next : neighbours(cell)) {
                if (seen.contains(next) || !within(start, next, radius) || !fits(space, next)) {
                    continue;
                }
                seen.add(next);
                cameFrom.put(next, cell);
                queue.add(next);
            }
        }
        if (found == null) {
            return List.of();
        }
        return smooth(trace(cameFrom, start, found));
    }

    public static List<Vector> route(Space space, Vector from) {
        return route(space, from, DEFAULT_RADIUS);
    }

    // ------------------------------------------------------------------ the lattice

    /**
     * The six directions, up first.
     * <p>
     * Order matters at equal distance: breadth-first keeps the first route it found to a cell, so putting up
     * first means a ghast under an overhang prefers the way out that is upwards when both are the same number
     * of steps. That is the answer a person would give.
     */
    private static List<Cell> neighbours(Cell cell) {
        return List.of(
                new Cell(cell.x(), cell.y() + STEP, cell.z()),
                new Cell(cell.x() + STEP, cell.y(), cell.z()),
                new Cell(cell.x() - STEP, cell.y(), cell.z()),
                new Cell(cell.x(), cell.y(), cell.z() + STEP),
                new Cell(cell.x(), cell.y(), cell.z() - STEP),
                new Cell(cell.x(), cell.y() - STEP, cell.z()));
    }

    private static boolean within(Cell start, Cell cell, int radius) {
        return Math.abs(cell.x() - start.x()) <= radius
                && Math.abs(cell.y() - start.y()) <= radius
                && Math.abs(cell.z() - start.z()) <= radius;
    }

    /** Whether a ghast's body fits here: every block of a {@link #STEP} cube is free. */
    private static boolean fits(Space space, Cell cell) {
        for (int x = 0; x < STEP; x++) {
            for (int y = 0; y < STEP; y++) {
                for (int z = 0; z < STEP; z++) {
                    if (!space.isFree(cell.x() + x, cell.y() + y, cell.z() + z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether this cell is out in the open: nothing overhead for {@link #OPEN_SKY} blocks.
     * <p>
     * Four columns rather than all sixteen, and each abandoned at the first solid block. A ceiling is found
     * immediately, which is the case that has to be cheap: this runs for every cell the search reaches.
     */
    private static boolean isOut(Space space, Cell cell) {
        int[][] corners = {{0, 0}, {STEP - 1, 0}, {0, STEP - 1}, {STEP - 1, STEP - 1}};
        for (int[] corner : corners) {
            for (int up = STEP; up < STEP + OPEN_SKY; up++) {
                if (!space.isFree(cell.x() + corner[0], cell.y() + up, cell.z() + corner[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the path

    private static List<Cell> trace(Map<Cell, Cell> cameFrom, Cell start, Cell goal) {
        List<Cell> backwards = new ArrayList<>();
        Cell at = goal;
        while (at != null && !at.equals(start)) {
            backwards.add(at);
            at = cameFrom.get(at);
        }
        List<Cell> forwards = new ArrayList<>(backwards.size());
        for (int index = backwards.size() - 1; index >= 0; index--) {
            forwards.add(backwards.get(index));
        }
        return forwards;
    }

    /**
     * Drops every waypoint that is on the straight line between its neighbours.
     * <p>
     * What is left is the corners, which is what a flight plan is: three long runs rather than eleven short
     * ones. Each waypoint is put at the centre of its cell, so the ghast aims at the middle of a gap rather
     * than at its edge.
     */
    private static List<Vector> smooth(List<Cell> path) {
        List<Vector> waypoints = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            boolean corner = index == 0 || index == path.size() - 1
                    || !collinear(path.get(index - 1), path.get(index), path.get(index + 1));
            if (corner) {
                Cell cell = path.get(index);
                waypoints.add(new Vector(cell.x() + STEP / 2.0, cell.y(), cell.z() + STEP / 2.0));
            }
        }
        return List.copyOf(waypoints);
    }

    private static boolean collinear(Cell before, Cell here, Cell after) {
        return (here.x() - before.x()) == (after.x() - here.x())
                && (here.y() - before.y()) == (after.y() - here.y())
                && (here.z() - before.z()) == (after.z() - here.z());
    }
}
