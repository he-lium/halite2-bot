import hlt.*;

import java.util.*;

public class Finder {
    private GameMap gmap;
    private ArrayList<ArrayList<Integer>> box; // 2d grid -> cost of entities

    public Finder(GameMap gmap) {
        this.gmap = gmap;
    }

    private ArrayList<Integer> constructRowArray() {
        ArrayList<Integer> arr = new ArrayList<>(gmap.getHeight());
        for (int i = 0; i < gmap.getHeight(); i++) {
            arr.add(0);
        }
        return arr;
    }

    private int getGrid(int w, int h) {
        return box.get(w).get(h);
    }

    private void setGrid(int w, int h, int val) {
        box.get(w).set(h, val);
    }

    // Call at each round
    public void recalculate() {
        box = new ArrayList<>(gmap.getWidth());
        for (int i = 0; i < gmap.getWidth(); i++) {
            box.add(constructRowArray());
        }

        for (final Ship ship : gmap.getAllShips()) {
            final int x = (int) ship.getXPos();
            final int y = (int) ship.getYPos();
            setGrid(x, y, getGrid(x, y) + 1);
        }

        for (final Planet planet : gmap.getAllPlanets().values()) {
            // Test whether planet is within each dot in square dim of planet
            for (int x = (int) (planet.getXPos() - planet.getRadius());
                 x <= planet.getXPos() + planet.getRadius(); x++) {
                for (int y = (int) (planet.getYPos() - planet.getRadius());
                        y <= planet.getYPos() + planet.getRadius(); y++) {
                    double dx = (x - planet.getXPos());
                    double dy = (y - planet.getYPos());
                    if (dx * dx + dy * dy <= planet.getRadius() * planet.getRadius()) {
                        setGrid(x, y, 9000);
                    }
                }
            }
        }
    }

    public Position findPath(Ship ship, Planet finish) {
        Log.log("called");
        Point src = new Point(ship);
        Point dest = new Point(finish);
        Log.log("dest: " + dest.toString());
        HashSet<Point> closedSet = new HashSet<>();
        HashSet<Point> openSet = new HashSet<>();
        openSet.add(src);
        Hashtable<Point, Point> prev = new Hashtable<>();
        Hashtable<Point, Integer> gScore = new Hashtable<>();
        gScore.put(src, 0);
        Hashtable<Point, Integer> fScore = new Hashtable<>();
        fScore.put(src, est(src, dest));

        while (!openSet.isEmpty()) {
            Log.log("?");
            // get Point curr with lowest fScore
            Optional<Point> p = openSet.stream().min(Comparator.comparingInt(fScore::get));
            if (!p.isPresent()) break;
            Point curr = p.get();
            Log.log("curr: " + curr.toString());
            Log.log("prev: " + prev.get(curr));
            Log.log("gScore: " + Integer.toString(gScore.get(curr)));
            Log.log("fScore: " + Integer.toString(fScore.get(curr)));

            if (reachedPlanet(curr, finish)) {
                // found; return goal
                Log.log(Integer.toString(ship.getId()) + " found");
                ArrayList<Point> path = new ArrayList<>();
                path.add(curr);
                while (prev.containsKey(curr)) {
                    curr = prev.get(curr);
                    path.add(curr);
                }
                Log.log("pathlen: " + Integer.toString(path.size()));
                Point target = path.size() >= 2 ?
                        path.get(path.size() - 2) : path.get(path.size() - 1);
                return new Position(target.x, target.y);
            }

            openSet.remove(curr);
            closedSet.add(curr);

            for (final Point next : curr.expand()) {
                if (closedSet.contains(next)) continue;
                if (!openSet.contains(next)) openSet.add(next);

                final int tentative_gScore = gScore.get(curr) + 1 + getGrid(next.x, next.y);
                if (gScore.contains(next) && tentative_gScore >= gScore.get(next))
                    continue;

                prev.put(next, curr);
                Log.log("added " + next.toString());
                gScore.put(next, tentative_gScore);
                fScore.put(next, tentative_gScore + est(next, dest));
            }
        }
        Log.log("A* null");
        return null;
    }

    private boolean reachedPlanet(Point point, Planet planet) {
        return point.x > planet.getXPos() - planet.getRadius()
                && point.x < planet.getXPos() + planet.getRadius()
                && point.y > planet.getYPos() - planet.getRadius()
                && point.y < planet.getYPos() + planet.getRadius();
    }

    // a star algorithm heuristic
    private int est(Point a, Point b) {
        int dx = Math.abs(a.x - b.x);
        int dy = Math.abs(a.y - b.y);
        return Math.max(dx, dy);
    }

    class Point {
        public int x, y;
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
        public Point(Position s) {
            this.x = (int) s.getXPos();
            this.y = (int) s.getYPos();
        }

        public Collection<Point> expand() {
            ArrayList<Point> next = new ArrayList<>(8);
            final boolean up = y > 0;
            final boolean down = y < gmap.getHeight() - 1;
            final boolean left = x > 0;
            final boolean right = x < gmap.getWidth() - 1;
            if (up) next.add(new Point(x , y - 1));
            if (down) next.add(new Point(x , y + 1));
            if (left) next.add(new Point(x - 1, y));
            if (right) next.add(new Point(x + 1, y));
            if (up && left) next.add(new Point(x - 1, y - 1));
            if (up && right) next.add(new Point(x + 1, y - 1));
            if (down && left) next.add(new Point(x - 1, y + 1));
            if (down && right) next.add(new Point(x + 1, y + 1));
            return next;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }

        @Override
        public int hashCode() {
            return x * gmap.getWidth() + y;
        }

        @Override
        public String toString() {
            return "(" + Integer.toString(x) + ", " + Integer.toString(y) + ")";
        }
    }
}
