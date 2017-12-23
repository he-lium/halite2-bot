import hlt.*;

import java.util.ArrayList;

public class MyBot {

    public static void main(final String[] args) {
        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("Helium-4x");
        // Init game
        final GameBot bot = new GameBot(gameMap);

        while (true) {
            // make each move of the game
            networking.updateMap(gameMap);
            bot.printStats();
            Networking.sendMoves(bot.makeMove());

        }
    }
}
