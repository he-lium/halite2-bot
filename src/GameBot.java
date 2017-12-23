import hlt.*;
import java.util.*;


public class GameBot {
    private GameMap gameMap;
    private HashMap<Integer, Integer> targets; // Ship ID -> Planet ID
    private int numOwnedPlanets;
    private ArrayList<Planet> ourPlanets;
    private final static int NAV_NUM_CORRECTIONS = 20;
    private final static double PROB_DOCK = 0.5;
    private final static int OFFENSE_THRESHOLD = 4;
    private int turnCount;
    // number of undocked ships that are targeting planet
    private HashMap<Planet, Integer> incoming;
    private Finder finder;
    // Defence
    // Enemy ship -> list of our ships targeting it
    private HashMap<Ship, ArrayList<Ship>> enemyTrack;

    private void recalcIncoming() {
        incoming = new HashMap<>();
        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            if (ship.getDockingStatus() == Ship.DockingStatus.Docked) continue;
            if (!targets.containsKey(ship.getId())) continue;
            // Increment incoming count of target planet
            final Planet target = gameMap.getPlanet(targets.get(ship.getId()));
            if (incoming.containsKey(target))
                incoming.put(target, incoming.get(target) + 1);
            else incoming.put(target, 1);
        }
    }

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
        turnCount = 0;
        finder = new Finder(gameMap);

        // init defence
        // enemyTrack = new HashMap<>();
        initStats();
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
        ourPlanets = new ArrayList<>(gameMap.getAllPlanets().values());
        ourPlanets.removeIf(p -> p.getOwner() != gameMap.getMyPlayer().getId());
        finder.recalculate();
        ArrayList<Move> moveList = new ArrayList<>();
        recalcIncoming();

        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                // TODO decide whether or not to undock
                continue;
            }

            if (!this.targets.containsKey(ship.getId())) {
                decideTarget(ship);
            }

            // Check whether target still exists
            if (!targets.containsKey(ship.getId())) {
                Log.log("NULL NULL NULL");
                continue;
            }
            Planet target = gameMap.getPlanet(targets.get(ship.getId()));
            if (target == null) {
                // Target no longer exists; recalculate target
                target = (Planet) decideTarget(ship);
            }

            if (target.isOwned()) {
                if (target.getOwner() == gameMap.getMyPlayer().getId()) {
                    // Target is ours
                    if (target.isFull()) target = (Planet) decideTarget(ship);
                } else if (ourPlanets.size() < OFFENSE_THRESHOLD) {
                    // Target is opponent's and we don't have enough planets
                    target = (Planet) decideTarget(ship);
                }
            }

            if (ship.getDistanceTo(target) < target.getRadius() + Constants.DOCK_RADIUS * 3) {
                // if planet is opponent's
                if (target.isOwned() && target.getOwner() != gameMap.getMyPlayer().getId()) {
                    final Move enemyMove = approachEnemy(ship, target);
                    if (enemyMove != null) moveList.add(enemyMove);
                } else {
                    // Planet is ours or undocked
                    if (ship.canDock(target)) {
                        moveList.add(new DockMove(ship, target));
                    } else {
                        ThrustMove approachPlanet = Navigation.navigateShipToDock(
                                gameMap, ship, target, Constants.MAX_SPEED - 1
                        );
                        if (approachPlanet != null) moveList.add(approachPlanet);
                    }
                }
            } else {
                Log.log("moving to target");
                // Move to target
                int speed = (turnCount < 3) ? Constants.MAX_SPEED / 2 : Constants.MAX_SPEED;
                // Try A* finder
                final Position midTarget = null; // finder.findPath(ship, target);
                ThrustMove move;
                if (midTarget != null) {
                    int direction = ship.orientTowardsInDeg(midTarget);
                    move = new ThrustMove(ship, direction, Constants.MAX_SPEED);
                    aStarUsed++; // stats
                } else {
                    move = Navigation.navigateShipTowardsTarget(
                            gameMap, ship, new Position(target.getXPos(), target.getYPos()),
                            speed, true, NAV_NUM_CORRECTIONS,
                            Math.toRadians(5)
                    );
                    naiveUsed++; // stats
                }
                if (move != null) moveList.add(move);

            }
        }

        if (targets.size() > gameMap.getMyPlayer().getShips().size() * 2)
            clean();
        turnCount++;
        return moveList;
    }

    // (re)calculate which planet the ship should target
    private Entity decideTarget(Ship ship) {
        // Nearest unowned planet
        ArrayList<Planet> planets = getPlanetsByDistance(new Position(ship.getXPos(), ship.getYPos()));
        for (final Planet planet : planets) {
            if (planet.getOwner() == gameMap.getMyPlayer().getId()) {
                if (planet.isFull())
                    continue;
                else if (planet.getDockedShips().size() > 1 && Math.random() > 0.3)
                    continue;
            }
            if (planet.isOwned() && ourPlanets.size() < OFFENSE_THRESHOLD &&
                    ((double) numOwnedPlanets / gameMap.getAllPlanets().size()) < 0.6)
                continue;
            // Designate as target
            targets.put(ship.getId(), planet.getId());
            return planet;
        }
        // No remaining planets
        return planets.get(0);
    }

    // Decide action when within close range of enemy planet
    private Move approachEnemy(Ship myShip, Planet enemy) {
        // Charge at planet to damage
        // TODO Destroy opponent's ships
        for (final int enemyShipId : enemy.getDockedShips()) {
            final Ship enemyShip = gameMap.getShip(enemy.getOwner(), enemyShipId);
            // within range to attack enemy ship; keep attacking
            if (myShip.getDistanceTo(enemyShip) < Constants.WEAPON_RADIUS) {
                final int direction = myShip.orientTowardsInDeg(enemyShip);
                return new ThrustMove(myShip, direction, 0);
            }
        }
        if (incoming.containsKey(enemy)
                && incoming.get(enemy) >= enemy.getDockedShips().size()) {
            // Destroy enemy ships
            final Ship enemyShip = gameMap.getShip(enemy.getOwner(), enemy.getDockedShips().get(0));
            final boolean avoid = Math.random() < 0.7;
            return Navigation.navigateShipTowardsTarget(gameMap, myShip, enemyShip,
                    Constants.MAX_SPEED, avoid, 3, Math.toRadians(20));
        }
        // destroy enemy planet
        return Navigation.navigateShipTowardsTarget(
                gameMap, myShip, new Position(enemy.getXPos(), enemy.getYPos()),
                Constants.MAX_SPEED, false,1, Math.toRadians(20)
        );
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

    // Stats
    private int aStarUsed;
    private int naiveUsed;

    private void initStats() {
        aStarUsed = 0;
        naiveUsed = 0;
    }

    public void printStats() {
        Log.log("A*: " + Integer.toString(aStarUsed));
        Log.log("Naive: " + Integer.toString(naiveUsed));
    }
}
