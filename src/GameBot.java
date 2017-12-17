

import hlt.*;
import org.omg.CORBA.NVList;

import java.net.ConnectException;
import java.util.*;

public class GameBot {
    private GameMap gameMap;
    private HashMap<Integer, Integer> targets; // Ship ID -> Planet ID
    private int numOwnedPlanets;
    private final static int NAV_NUM_CORRECTIONS = 6;
    private final static double PROB_DOCK = 0.5;

    public GameBot(GameMap g) {
        this.gameMap = g;

        // We now have 1 full minute to analyse the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                        "; height: " + gameMap.getHeight() +
                        "; players: " + gameMap.getAllPlayers().size() +
                        "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);

        targets = new HashMap<>();

        double x = 0, y = 0;
        int count = gameMap.getMyPlayer().getShips().size();
        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            x += ship.getXPos();
            y += ship.getYPos();
        }
        Position startPos = new Position(x / count, y / count);
        ArrayList<Planet> planets = getPlanetsByDistance(startPos);

        // For each ship, set up an initial planet to target
        int i = 0;
        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            // Set target planet
            Log.log(Integer.toString(ship.getId()));
            Log.log(Integer.toString(planets.get(i).getId()));
            targets.put(ship.getId(), planets.get(i).getId());
            i++;
            if (i >= planets.size()) i = 0;
        }
    }

    private ArrayList<Planet> getPlanetsByDistance(Position pos) {
        // Get a list of planets by distance to avg
        ArrayList<Planet> planets = new ArrayList<>(gameMap.getAllPlanets().values());
        Collections.sort(planets, (p1, p2) -> {
            double d1 = p1.getDistanceTo(pos);
            double d2 = p2.getDistanceTo(pos);
            if (d1 < d2) return -1;
            if (d1 > d2) return 1;
            return 0;
        });
        return planets;
    }

    public ArrayList<Move> makeMove() {
        numOwnedPlanets = gameMap.getAllPlanets().values().stream().mapToInt(planet -> planet.isOwned() ? 1 : 0).sum();
        ArrayList<Move> moveList = new ArrayList<>();

        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                // TODO decide whether or not to undock
                continue;
            }

            // New ship: decide whether to dock
            // TODO fix probability implementation
            /*if (!this.targets.containsKey(ship.getId())) {
                Planet dock = getPlanetsByDistance(new Position(ship.getXPos(), ship.getYPos())).get(0);
                if (dock.getOwner() == gameMap.getMyPlayer().getId()
                        && ship.canDock(dock) && Math.random() < PROB_DOCK) {
                    moveList.add(new DockMove(ship, dock));
                    continue;
                } else {
                    decideTarget(ship);
                }
            }*/

            if (!this.targets.containsKey(ship.getId())) {
                decideTarget(ship);
            }

            // Check whether target still exists
            if (!targets.containsKey(ship.getId())) continue;
            Planet target = gameMap.getPlanet(targets.get(ship.getId()));
            if (target == null) {
                // Target no longer exists; recalculate target
                target = (Planet) decideTarget(ship);
            }

            if (ship.getDistanceTo(target) < Constants.DOCK_RADIUS * 5) {
                // if planet is opponent's
                if (target.isOwned() && target.getOwner() != gameMap.getMyPlayer().getId()) {
                    // Charge at planet to damage
                    // TODO Destroy opponent's ships
                    ThrustMove chargeMove = Navigation.navigateShipTowardsTarget(
                            gameMap, ship, new Position(target.getXPos(), target.getYPos()),
                            Constants.MAX_SPEED, false,1, Math.toRadians(20)
                    );
                    if (chargeMove != null) moveList.add(chargeMove);

                } else {
                    // Planet is ours
                    if (ship.canDock(target)) {
                        moveList.add(new DockMove(ship, target));
                    } else {
                        Log.log("can't dock");
                        ThrustMove approachPlanet = Navigation.navigateShipToDock(
                                gameMap, ship, target, Constants.MAX_SPEED / 2
                        );
                        if (approachPlanet != null) moveList.add(approachPlanet);
                    }
                }
            } else {
                final ThrustMove moveToTarget = Navigation.navigateShipTowardsTarget(
                        gameMap, ship, new Position(target.getXPos(), target.getYPos()),
                        Constants.MAX_SPEED - 2, true, NAV_NUM_CORRECTIONS,
                        Math.toRadians(20)
                );
                if (moveToTarget != null) moveList.add(moveToTarget);

            }
        }

        if (targets.size() > gameMap.getMyPlayer().getShips().size() * 2)
            clean();
        return moveList;
    }

    // (re)calculate which planet the ship should target
    private Entity decideTarget(Ship ship) {
        // Nearest unowned planet
        ArrayList<Planet> planets = getPlanetsByDistance(new Position(ship.getXPos(), ship.getYPos()));
        for (final Planet planet : planets) {
            if (planet.getOwner() == gameMap.getMyPlayer().getId())
                continue;
            if (planet.isOwned() && ((double) numOwnedPlanets / gameMap.getAllPlanets().size()) < 0.6)
                continue;
            // Designate as target
            targets.put(ship.getId(), planet.getId());
            return planet;
        }
        // No remaining planets
        return planets.get(0);
    }

    // Clean up unused mappings
    private void clean() {
        for (Integer id : new HashSet<>(targets.keySet())) {
            if (!gameMap.getMyPlayer().getShips().keySet().contains(id)) {
                // Ship no longer exists
                targets.remove(id);
            }
        }
    }
}
