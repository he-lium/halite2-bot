import hlt.*;
import java.net.ConnectException;
import java.util.*;
import java.util.stream.Collectors;

public class GameBot {
    private GameMap gameMap;
    private HashMap<Integer, Integer> targets; // Ship ID -> Planet ID
    private int numOwnedPlanets;
    private ArrayList<Planet> ourPlanets;
    private final static int NAV_NUM_CORRECTIONS = 20;
    private final static int OFFENSE_THRESHOLD = 4;
    private int turnCount;
    // number of undocked ships that are targeting planet
    private HashMap<Planet, Integer> incoming;
    private Defence.GameGrid grid;
    // Override target for enemy ships
    private HashMap<Integer, Ship> shipTargets; // Ship ID -> Enemy Ship target
    private int destroyer;

    public GameBot(GameMap g) {
        this.gameMap = g;
        this.destroyer = -1;

        // We now have 1 full minute to analyse the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                        "; height: " + gameMap.getHeight() +
                        "; players: " + gameMap.getAllPlayers().size() +
                        "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);

        targets = new HashMap<>();
        shipTargets = new HashMap<>();

        final ArrayList<Ship> ourShips = new ArrayList<>(gameMap.getMyPlayer().getShips().values());
        final Optional<Ship> enemyShip = gameMap.getAllShips().stream().filter(s -> s.getOwner() == gameMap.getMyPlayerId())
                .findFirst();
        final boolean destroyMode = gameMap.getAllPlayers().size() == 2
                && enemyShip.isPresent()
                && enemyShip.get().getDistanceTo(ourShips.get(0)) < 125;
        if (destroyMode)
            destroyer = ourShips.get(0).getId();
        // For each ship, set up an initial planet to target
        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            // Set target planet
            for (final Planet planet : getPlanetsByDistance(ship)) {
                if (!targets.containsValue(planet.getId())) {
                    targets.put(ship.getId(), planet.getId());
                    break;
                }
            }
        }
        grid = new Defence.GameGrid(gameMap);
        turnCount = 0;
    }

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

    private ArrayList<Planet> getPlanetsByDistance(Position pos) {
        // Get a list of planets by distance to avg
        ArrayList<Planet> planets = new ArrayList<>(gameMap.getAllPlanets().values());
        planets.sort(Comparator.comparingDouble(p -> p.getDistanceTo(pos)));
        return planets;
    }

    private ArrayList<Move> firstMove() {
        ArrayList<Move> moves = new ArrayList<>();
        ArrayList<Ship> ships = new ArrayList<>(gameMap.getMyPlayer().getShips().values());
        ships.sort(Comparator.comparingDouble(Ship::getYPos));
        // uppermost ship
        moves.add(new ThrustMove(ships.get(0), 0, Constants.MAX_SPEED));
        moves.add(new ThrustMove(ships.get(1), 90, Constants.MAX_SPEED));
        moves.add(new ThrustMove(ships.get(2), 200, Constants.MAX_SPEED));

        turnCount++;
        return moves;
    }

    public ArrayList<Move> makeMove() {
        // Perform per-turn calculations
        if (turnCount == 0) return this.firstMove();
        numOwnedPlanets = gameMap.getAllPlanets().values().stream()
                .mapToInt(planet -> planet.isOwned() ? 1 : 0).sum();
        ourPlanets = new ArrayList<>(gameMap.getAllPlanets().values());
        ourPlanets.removeIf(p -> p.getOwner() != gameMap.getMyPlayerId());
        ArrayList<Move> moveList = new ArrayList<>();
        recalcIncoming(); // calculate incoming per planet
        assignDefence(); // retarget enemy ships near our planets

        // Process each ship
        for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
            if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                // TODO decide whether or not to undock
                continue;
            }

            // TODO PATCH
            if (ship.getId() == destroyer) {
                Move move = getDestroyerMove(ship);
                if (move != null) moveList.add(move);
                continue;
            }

            // If ship has been assigned a ship target
            if (shipTargets.containsKey(ship.getId())) {
                final Ship validate = shipTargets.get(ship.getId());
                final Ship enemyShip = gameMap.getShip(validate.getOwner(), validate.getId());
                if (enemyShip == null) {
                    shipTargets.remove(ship.getId());
                } else {
                    ThrustMove move = approachEnemyShip(ship, enemyShip);
                    if (move != null) {
                        moveList.add(move);
                        continue;
                    }
                }
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
                if (target.getOwner() == gameMap.getMyPlayerId()) {
                    // Target is ours
                    if (target.isFull()) target = (Planet) decideTarget(ship);
                } else if (ourPlanets.size() < OFFENSE_THRESHOLD) {
                    // Target is opponent's and we don't have enough planets
                    target = (Planet) decideTarget(ship);
                }
            }

            // Target planet is nearby
            if (ship.getDistanceTo(target) < (target.getRadius() + Constants.DOCK_RADIUS * 4)) {
                // if planet is opponent's
                if (target.isOwned() && target.getOwner() != gameMap.getMyPlayerId()) {
                    final Move enemyMove = approachEnemy(ship, target);
                    if (enemyMove != null) moveList.add(enemyMove);
                    else Log.log("INVALID APPROACH MOVE");
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
                // Move to target
                ThrustMove moveToTarget;
                int speed = (turnCount < 4) ? Constants.MAX_SPEED - 2 : Constants.MAX_SPEED;
                ArrayList<Entity> inBetween = new ArrayList<>();
                GameMap.addEntitiesBetween(inBetween, ship, target, gameMap.getAllPlanets().values());
                // Check for docked ships
                List<Ship> dockedShips = gameMap.getMyPlayer().getShips().values().stream()
                        .filter(s -> s.getDockingStatus() != Ship.DockingStatus.Undocked)
                        .collect(Collectors.toList());
                GameMap.addEntitiesBetween(inBetween, ship, target, dockedShips);
                if (inBetween.isEmpty() && turnCount > 8) {
                    moveToTarget = new ThrustMove(ship, ship.orientTowardsInDeg(target), speed);
                } else {
                    moveToTarget = Navigation.navigateShipTowardsTarget(
                            gameMap, ship, new Position(target.getXPos(), target.getYPos()),
                            speed, true, NAV_NUM_CORRECTIONS,
                            Math.toRadians(5)
                    );
                }
                if (moveToTarget != null) moveList.add(moveToTarget);
            }
        }

        if (targets.size() > gameMap.getMyPlayer().getShips().size() * 2)
            clean();
        turnCount++;
        return moveList;
    }

    private ThrustMove getDestroyerMove(Ship ship) {
        final ArrayList<Ship> targets = gameMap.getAllShips().stream()
                .filter(s -> s.getOwner() != ship.getOwner() &&
                        (turnCount < 20 || s.getDockingStatus() != Ship.DockingStatus.Undocked))
                .collect(Collectors.toCollection(ArrayList::new));
        if (targets.isEmpty()) {
            destroyer = -1;
            decideTarget(ship);
            return null;
        }
        targets.sort(Comparator.comparingDouble(s -> s.getDistanceTo(ship)));
        final Ship target = targets.get(0);
        if (ship.getDistanceTo(target) < Constants.WEAPON_RADIUS) return null;
        final int speed = ship.getDistanceTo(target) > 8 ? Constants.MAX_SPEED : Constants.MAX_SPEED / 2;
        return Navigation.navigateShipTowardsTarget(gameMap, ship, target, speed,
                true, NAV_NUM_CORRECTIONS, Math.toRadians(5));

    }

    private ThrustMove approachEnemyShip(Ship ship, Ship enemyShip) {
        return Navigation.navigateShipTowardsTarget(gameMap, ship, enemyShip,
                Constants.MAX_SPEED - 1, true, NAV_NUM_CORRECTIONS,
                Math.toRadians(5));
    }

    /**
     * Assign ships to target enemy ships near our planets
     */
    private void assignDefence() {
        grid.update(gameMap);
        for (final Ship threat : Defence.getThreats(ourPlanets, grid)) {
            // Assign nearest ship to target the threat
            ArrayList<Ship> ships = new ArrayList<>(gameMap.getMyPlayer().getShips().values());
            ships.removeIf(ship ->
                    ship.getDockingStatus() != Ship.DockingStatus.Undocked
                            || ship.getDistanceTo(threat) > 30
                            || shipTargets.containsKey(ship.getId())
            );
            ships.sort(Comparator.comparingDouble(ship -> ship.getDistanceTo(threat)));
            if (!ships.isEmpty()) {
                final Ship assignee = ships.get(0);
                shipTargets.put(assignee.getId(), threat);
            }
        }
    }

    // (re)calculate which planet the ship should target
    private Entity decideTarget(Ship ship) {
        // Nearest unowned planet
        ArrayList<Planet> planets = getPlanetsByDistance(ship);

        ArrayList<Planet> unowned = new ArrayList<>(planets);
        unowned.removeIf(Planet::isOwned);
        if (unowned.size() > 1 && Math.random() < 0.4) {
            targets.put(ship.getId(), unowned.get(0).getId());
        }
        for (final Planet planet : planets) {
            if (planet.getOwner() == gameMap.getMyPlayerId()) {
                if (planet.isFull())
                    continue; // skip if is our planet and full
                else if (planet.getDockedShips().size() > 1 && Math.random() > 0.3)
                    continue; // skip sometimes if 2+ ships on planet
            }
            if (planet.isOwned() && ourPlanets.size() < OFFENSE_THRESHOLD &&
                    ((double) numOwnedPlanets / gameMap.getAllPlanets().size()) < 0.6)
                continue; // skip if enemy planet and we don't own enough planets
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
//        if (incoming.containsKey(enemy)
//                && incoming.get(enemy) >= enemy.getDockedShips().size()) {
        // Destroy enemy ships
        Ship lastShip = null;
        // Find enemy ship to destroy
        for (final Ship enemyShip : enemy.getDockedShips()
                .stream().map(id -> gameMap.getShip(enemy.getOwner(), id)).collect(Collectors.toList())) {
            lastShip = enemyShip;
            if (!Collision.segmentCircleIntersect(myShip, enemyShip, enemy, Constants.FORECAST_FUDGE_FACTOR)) {
                // Go directly to target
                return Navigation.navigateShipTowardsTarget(gameMap, myShip, enemyShip,
                        Constants.MAX_SPEED - 2, true, 5, Math.toRadians(7) );
            }
        }
        // Circle around ship$
        return Navigation.navigateShipTowardsTarget(gameMap, myShip, lastShip, Constants.MAX_SPEED - 1,
                true, NAV_NUM_CORRECTIONS, Math.toRadians(5));
//        int circleAngle = Math.floorMod(myShip.orientTowardsInDeg(enemy) + 90, 360);

//        return new ThrustMove(myShip, circleAngle, Constants.MAX_SPEED - 1);
    }

    private ArrayList<Entity> entitiesInBetween(Entity from, Entity to, Collection<? extends Entity> entities) {
        ArrayList<Entity> result = new ArrayList<>();
        GameMap.addEntitiesBetween(result, from, to, entities);
        return result;
    }

    // Clean up unused mappings
    private void clean() {
        final HashSet<Integer> idCheck = new HashSet<>();
        idCheck.addAll(targets.keySet());
        idCheck.addAll(shipTargets.keySet());
        for (int id : idCheck) {
            if (!gameMap.getMyPlayer().getShips().keySet().contains(id)) {
                // Ship no longer exists
                targets.remove(id);
                shipTargets.remove(id);
            }
        }
    }

    public void logIncoming() {
        if (incoming == null) return;
        for (final Planet planet : incoming.keySet()) {
            Log.log(String.format("Planet id %d, Owner %d, Incoming %d",
                    planet.getId(), planet.getOwner(), incoming.get(planet)));
        }
    }
}
