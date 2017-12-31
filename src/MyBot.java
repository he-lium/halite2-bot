import hlt.*;

import java.util.ArrayList;

public class MyBot {

    public static void main(final String[] args) {
        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("Helium-4e");
        // Init game
        final GameBot bot = new GameBot(gameMap);

        while (true) {
            // make each move of the game
            networking.updateMap(gameMap);
            Networking.sendMoves(bot.makeMove());
            bot.logIncoming();
        }
    }
}
