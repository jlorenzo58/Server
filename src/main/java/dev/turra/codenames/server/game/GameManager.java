package dev.turra.codenames.server.game;

import dev.turra.codenames.common.Role;
import dev.turra.codenames.common.Team;
import dev.turra.codenames.common.network.Packet;
import dev.turra.codenames.common.network.cb.*;
import dev.turra.codenames.common.network.sb.*;
import dev.turra.codenames.common.CardColor;
import dev.turra.codenames.server.Connection;
import dev.turra.codenames.server.IPacketListener;

import java.awt.*;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the game state. Most of the game mechanics are handled here.
 */
public class GameManager implements IPacketListener {

	private List<String> words = new ArrayList<>();

	private HashMap<Integer, Player> players = new HashMap<>();
	private Card[][] board = new Card[5][5];

	private Team currentTurn;

	private int totalNumberOfGuesses;
	private int numberOfGuesses;

	private boolean debug = true;

	public GameManager() {

		// Load words from words.txt to words
		InputStream inputStream = getClass().getClassLoader().getResourceAsStream("words.txt");
		Scanner scanner = new Scanner(inputStream);
		while (scanner.hasNextLine()) {
			words.add(scanner.nextLine());
		}
		scanner.close();

		generateBoard();
		// Print words with their colors
		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				System.out.print(board[i][j].getColor().toString() + " " + board[i][j].getWord() + ", ");
			}
			System.out.println();
		}
	}

	/**
	 * Handles when a player joins or switches a team
	 * @param id The player's id
	 * @param team The player's new team
	 * @param role The player's new role
	 */
	public void switchTeam(int id, Team team, Role role) {
		Player player = players.get(id);
		Team oldTeam = player.team;
		Role oldRole = player.role;

		player.team = team;
		player.role = role;

		// Get a list of players in a Team and Role in form of a String.
		String players = this.players.values().stream().filter(p -> p.team == team).filter(p -> p.role == role).map(p -> p.name).collect(Collectors.joining(", "));

		PacketClientUpdatePlayers packet = new PacketClientUpdatePlayers(team, role, players);
		sendToAll(packet);

		// If the player was in a different team, remove that player from the old team
		if (oldTeam != null && oldRole != null) {
			String updateOld = this.players.values().stream().filter(p -> p.team == oldTeam).filter(p -> p.role == oldRole).map(p -> p.name).collect(Collectors.joining(", "));
			PacketClientUpdatePlayers updateOldPacket = new PacketClientUpdatePlayers(oldTeam, oldRole, updateOld);
			sendToAll(updateOldPacket);
		}
	}

	/**
	 * Generates the board with 25 cards that have random words and colors
	 */
	public void generateBoard() {
		// Get 25 random unique words from the word bank
		List<String> selected = new Random().ints(0, words.size()).distinct().limit(25).mapToObj(words::get).collect(Collectors.toList());

		// set current turn to a random team
		currentTurn = Team.values()[new Random().nextInt(2)];

		// Create 25 colors
		List<CardColor> colors = new ArrayList<>();
		for (int i = 0; i < (currentTurn == Team.BLUE ? 9 : 8); i++) {
			colors.add(CardColor.BLUE);
		}
		for (int i = 0; i < (currentTurn == Team.RED ? 9 : 8); i++) {
			colors.add(CardColor.RED);
		}
		for (int i = 0; i < 7; i++) {
			colors.add(CardColor.CITIZEN);
		}
		colors.add(CardColor.ASSASSIN);

		// Shuffle words and colors
		Collections.shuffle(selected);
		Collections.shuffle(colors);

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				board[i][j] = new Card(i, j, selected.get(i * 5 + j), colors.get(i * 5 + j));
			}
		}
	}

	/**
	 * Sends all cards to the player
	 * @param playerToSend Player to send card packets to. Used to determine the player's role
	 */
	public void sendCardPackets(Player playerToSend) {
		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 5; j++) {
				if (playerToSend == null) {
					for (Player player : players.values()) {
						PacketClientCard packet = new PacketClientCard(i, j, board[i][j].getWord(), player.role == Role.SPYMASTER ? board[i][j].getColor() : null);
						player.connection.sendPacket(packet);
					}
				} else {
					PacketClientCard packet = new PacketClientCard(i, j, board[i][j].getWord(), playerToSend.role == Role.SPYMASTER ? board[i][j].getColor() : null);
					playerToSend.connection.sendPacket(packet);
				}
			}
		}

	}

	/**
	 * Handle player leaving the game
	 * @param id The player's id
	 */
	public void playerQuit(int id) {
		Team team = players.get(id).team;
		Role role = players.get(id).role;
		players.remove(id);

		String updatedList = this.players.values().stream().filter(p -> p.team == team).filter(p -> p.role == role).map(p -> p.name).collect(Collectors.joining(", "));
		PacketClientUpdatePlayers updateOldPacket = new PacketClientUpdatePlayers(team, role, updatedList);
		sendToAll(updateOldPacket);
	}

	/**
	 * Sends a packet to all players
	 * @param packet The packet to send
	 */
	public void sendToAll(Packet packet) {
		for (Player player : players.values()) {
			player.connection.sendPacket(packet);
		}
	}

	/**
	 * Handles the packets that were received by all clients
	 * @param packet The packet received
	 * @param connection The connection that sent the packet
	 */
	@Override
	public void received(Packet packet, Connection connection) {
		if (packet instanceof PacketServerLogin p) {
			players.put(connection.id, new Player(connection, p.getName()));
			sendCardPackets(players.get(connection.id));

			// Send packet with all players in the game
			for (Team team : Team.values()) {
				for (Role role : Role.values()) {
					String players = this.players.values().stream().filter(player -> player.team == team).filter(player -> player.role == role).map(player -> player.name).collect(Collectors.joining(", "));

					PacketClientUpdatePlayers playerList = new PacketClientUpdatePlayers(team, role, players);
					sendToAll(playerList);
				}
			}
			announce(currentTurn.getName() + " team is giving a hint", currentTurn.getColor().getColor());
			updateScores(players.get(connection.id));
		} else if (packet instanceof PacketServerTeamRole p) {
			switchTeam(connection.id, p.getTeam(), p.getRole());
			sendCardPackets(players.get(connection.id));
		} else if (packet instanceof PacketServerCardClick p) {
			clickCard(connection, p);
		} else if (packet instanceof PacketServerHint p) {
			receiveHint(connection, p);
		}
	}

	/**
	 * Handles {@link PacketServerHint} packets
	 * @param connection The connection that sent the packet
	 * @param p The packet
	 */
	private void receiveHint(Connection connection, PacketServerHint p) {
		if (players.get(connection.id).role != Role.SPYMASTER)
			return;

		if (players.get(connection.id).team != currentTurn)
			return;

		totalNumberOfGuesses = p.getWordAmount() + 1;
		numberOfGuesses = 0;

		announce(currentTurn.getName() + " team is guessing", currentTurn.getColor().getColor());
		PacketClientHint hintPacket = new PacketClientHint(p.getHint(), p.getWordAmount(), players.get(connection.id).team);
		sendToAll(hintPacket);
		debug("Hint: " + p.getHint());
	}

	/**
	 * Handles {@link PacketServerCardClick} packets
	 * @param connection The connection that sent the packet
	 * @param p The packet
	 */
	private void clickCard(Connection connection, PacketServerCardClick p) {
		Card card = board[p.getX()][p.getY()];
		Player player = players.get(connection.id);

		if (player.role != Role.OPERATIVE)
			return;

		if (player.team != currentTurn)
			return;

		if (card.isRevealed())
			return;

		PacketClientCardReveal revealPacket = new PacketClientCardReveal(p.getX(), p.getY(), card.getColor());
		sendToAll(revealPacket);
		card.setRevealed(true);
		updateScores(null);
		if (card.getColor() == CardColor.ASSASSIN) {
			debug("Player " + player.name + " clicked on the assassin");
			Team winningTeam = currentTurn == Team.BLUE ? Team.RED : Team.BLUE;
			announce(winningTeam.getName() + " team won!", winningTeam.getColor().getColor());
			System.exit(0);
		} else if (card.getColor() != player.team.getColor() || card.getColor() == CardColor.CITIZEN) {
			debug("Player " + player.name + " clicked on " + card.getWord() + " of " + card.getColor() + " color");
			switchTurn();
		}

		// Check if they won
		if (getRemainingCards(currentTurn) == 0) {
			announce(currentTurn.getName() + " team won!", currentTurn.getColor().getColor());
			System.exit(0);
		}
		numberOfGuesses++;
		if (numberOfGuesses == totalNumberOfGuesses) {
			switchTurn();
		}
	}

	/**
	 * Send the updated score to the client/s
	 * @param player Who to send the score packets to. If this is null, the packets will be sent to all players.
	 */
	private void updateScores(Player player) {
		PacketClientUpdateScore bluePacket = new PacketClientUpdateScore(Team.BLUE, getRemainingCards(Team.BLUE));
		PacketClientUpdateScore redPacket = new PacketClientUpdateScore(Team.RED, getRemainingCards(Team.RED));
		if (player != null) {
			player.connection.sendPacket(bluePacket);
			player.connection.sendPacket(redPacket);
		} else {
			sendToAll(bluePacket);
			sendToAll(redPacket);
		}
	}

	/**
	 * Get the amount of unrevealed cards left in the board for a team
	 * @param team The team to get the amount of unrevealed cards for
	 * @return The amount of unrevealed cards left in the board for the team
	 */
	private int getRemainingCards(Team team) {
		int count = 0;
		for (Card[] row : board) {
			for (Card card : row) {
				if (card.getColor() == team.getColor()) {
					if(!card.isRevealed()) {
						count++;
					}
				}
			}
		}
		return count;
	}

	/**
	 * Switch the turn to the next team
	 */
	private void switchTurn() {
		currentTurn = currentTurn == Team.BLUE ? Team.RED : Team.BLUE;
		announce(currentTurn.getName() + " team is giving a hint", currentTurn.getColor().getColor());
	}

	/**
	 * Sends the announcement packet to all players
	 * @param message The message to send
	 * @param color The color of the message
	 */
	private void announce(String message, Color color) {
		PacketClientAnnouncer announcer = new PacketClientAnnouncer(message, color);
		sendToAll(announcer);
	}

	/**
	 * For debugging purposes. Prints a message to the console
	 * @param debug Message to print
	 */
	private void debug(String debug) {
		if (this.debug) {
			System.out.println(debug);
		}
	}
}
