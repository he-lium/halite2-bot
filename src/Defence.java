import hlt.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

public class Defence {

    /**
     * Get collection of enemy ships that threaten owned planets
     * @param myPlanets List of planets by us
     * @return ArrayList of enemy ships near owned planets
     */
    public static ArrayList<Ship> getThreats(Collection<Planet> myPlanets, GameGrid grid) {
        ArrayList<Ship> result = new ArrayList<>();
        for (final Planet planet : myPlanets) {
            final double watchRadius = planet.getRadius() + Constants.DOCK_RADIUS * 3;
            grid.getGroupScaled(planet, watchRadius).stream().filter(entity -> {
                if (entity instanceof Ship) {
                    final Ship ship = (Ship) entity;
                    return ship.getOwner() != planet.getOwner() && ship.getDistanceTo(planet) <= watchRadius;
                }
                return false;
            }).forEach(entity -> result.add((Ship) entity));
        }
        return result;
    }

    public static class GameGrid {
        public static final int SCALE = 7;
        private ArrayList<ArrayList<ArrayList<Entity>>> grid;

        /**
         * Sets up empty spatial grid with correct dimensions
         * @param gMap The game map to generate the grid from
         */
        public GameGrid(GameMap gMap) {
            final int gridW = gMap.getWidth() / SCALE + 1;
            final int gridH = gMap.getHeight() / SCALE + 1;
            grid = new ArrayList<>(gridW);
            for (int x = 0; x < gridW; x++) {
                ArrayList<ArrayList<Entity>> gridCol = new ArrayList<>(gridH);
                for (int y = 0; y < gridH; y++) {
                    gridCol.add(new ArrayList<>());
                }
                grid.add(gridCol);
            }
        }

        /**
         * Update the grid for moved planets on each turn
         * @param gMap
         */
        public void update(GameMap gMap) {
            // clear grid
            for (ArrayList<ArrayList<Entity>> gridCol : grid) {
                for (ArrayList<Entity> gridCell : gridCol) {
                    gridCell.clear();
                }
            }

            for (final Ship ship : gMap.getAllShips()) {
                add(scale(ship.getXPos()), scale(ship.getYPos()), ship);
            }

            for (final Planet planet : gMap.getAllPlanets().values()) {
                final int startX = scale(planet.getXPos() - planet.getRadius());
                final int endX = scale(planet.getXPos() + planet.getRadius());
                final int startY = scale(planet.getYPos() - planet.getRadius());
                final int endY = scale(planet.getYPos() + planet.getRadius());
                for (int x = startX; x < endX; x++) {
                    for (int y = startY; y < endY; y++) {
                        add(x, y, planet);
                    }
                }
            }
        }

        /**
         * Get the scaled coordinate value
         * @param pos the REAL coordinate in the game map
         * @return the scaled grid coordinate
         */
        public int scale(double pos) {
            return (int) (pos / SCALE);
        }

        public ArrayList<Entity> getGroupScaled(Position pos, double radius) {
            final int x = scale(pos.getXPos());
            final int y = scale(pos.getYPos());
            final int r = scale(radius);
            return getGroup(x, y, r);
        }

        // TODO FIX SCALE
        public ArrayList<Entity> getGroup(int x, int y, int r) {
            ArrayList<Entity> result = new ArrayList<>();
            for (int i = x - r + 1; i < x + r; i++) {
                for (int j = y - r + 1; j < y + r; j++) {
                    result.addAll(get(i, j));
                }
            }
            return result;
        }

        /**
         * Get list of entities that are in the same grid as matched by given position
         * @param position the REAL position
         * @return list of entities
         */
        public ArrayList<Entity> getScaled(Position position) {
            return get(scale(position.getXPos()), scale(position.getYPos()));
        }

        public ArrayList<Entity> get(int x, int y) {
            if (x >= 0 && x < grid.size()
                    && y >= 0 && y < grid.get(0).size())
                return grid.get(x).get(y);
            else
                return new ArrayList<>();
        }

        private void clear(int x, int y) {
            get(x, y).clear();
        }

        /**
         * Adds entity to grid at given coordinates
         * @param x the SCALED x coordinate on the grid
         * @param y the SCALED y coordinate on the grid
         * @param entity
         */
        private void add(int x, int y, Entity entity) {
            if (x >= 0 && x < grid.size()
                    && y >= 0 && y < grid.get(0).size())
                get(x, y).add(entity);
        }
    }

}
