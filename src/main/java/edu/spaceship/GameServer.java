package edu.spaceship;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * Starts a TLS server for secure player connections.
 * All communication is encrypted at the transport layer using TLS.
 */
public final class GameServer {
    private final GameState state = new GameState();
    private final GameLogic logic = new GameLogic(state);
    private final Communication communication = new Communication();
    private ScheduledExecutorService timerExecutor;

    private GameServer(int port) throws Exception {
        // Load TLS certificates
        SSLContext sslContext = createSSLContext();
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);

        System.out.println("Spaceship Sabotage server listening on port " + serverSocket.getLocalPort() + " (TLS). Need " + GameState.MIN_PLAYERS + " players to start.");

        // Start background timer thread to check phase transitions every second
        timerExecutor = Executors.newSingleThreadScheduledExecutor();
        timerExecutor.scheduleAtFixedRate(() -> {
            if (logic.checkActionTimer()) {
                String actionMsg = logic.takeActionMessage();
                if (!actionMsg.isEmpty()) {
                    communication.broadcast("PHASE_CHANGE", actionMsg, state.getPhase().name(), state.getIntegrity());
                }
            } else if (logic.checkDiscussionTimer()) {
                communication.broadcast("PHASE_CHANGE", "Discussion time is up. VOTING phase has begun.", state.getPhase().name(), state.getIntegrity());
            } else if (logic.checkVotingTimer()) {
                String voteMsg = logic.takeVoteResult();
                if (!voteMsg.isEmpty()) {
                    communication.broadcast("VOTE_RESULT", voteMsg, state.getPhase().name(), state.getIntegrity());
                }
                String actionMsg = logic.takeActionMessage();
                if (!actionMsg.isEmpty()) {
                    communication.broadcast("PHASE_CHANGE", actionMsg, state.getPhase().name(), state.getIntegrity());
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            timerExecutor.shutdownNow();
            try { serverSocket.close(); } catch (IOException ignored) {};
        }));

        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handlePlayer(socket)).start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) System.err.println("Accept failed: " + e.getMessage());
            }
        }
    }

    private void handlePlayer(Socket socket) {
        PrintWriter out = null;
        String playerId = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Prompt for player name interactively
            String playerName = promptForName(in, out);
            if (playerName == null) return;

            if (playerName.isBlank()) playerName = "Player";
            playerId = UUID.randomUUID().toString().substring(0, 8);
            String message = logic.register(playerId, playerName);
            if (message.startsWith("The game")) {
                out.println("ERROR|" + message);
                return;
            }

            communication.register(playerId, out);

            out.println("REGISTERED|" + message + "|" + playerId);

            // Notify existing players about the new player
            final String finalPlayerId = playerId;
            state.getPlayers().values().stream()
                    .filter(p -> !p.getId().equals(finalPlayerId))
                    .forEach(p -> communication.sendPrivate(
                            finalPlayerId, "INFO", "Connected player: " + p.getName() + " (" + p.getId() + ")",
                            "", state.getPhase().name(), state.getIntegrity()
                    ));

            communication.broadcast("INFO", playerName + " joined (" + playerId + "). " + state.getPlayers().size() + " player(s) connected.",
                    state.getPhase().name(), state.getIntegrity());

            // Check if game is ready to start
            Map<String, String> roles = logic.startIfReady();
            roles.forEach((pid, role) -> {
                String teammates = role.equals("SABOTEUR") ? state.getPlayers().values().stream()
                        .filter(p -> p.getRole() == PlayerState.Role.SABOTEUR && !p.getId().equals(pid))
                        .map(p -> p.getName() + " (" + p.getId() + ")").reduce((a, b) -> a + ", " + b).orElse("none") : "";
                String roleText = "Your role is " + role + "." + (role.equals("SABOTEUR") ? " Your fellow saboteurs: " + teammates + "." : "");
                communication.sendPrivate(pid, "ROLE", roleText, role, state.getPhase().name(), state.getIntegrity());
            });

            if (state.getPhase() == GamePhase.ACTION) {
                communication.broadcast("PHASE_CHANGE", "Game started! ACTION phase — " + GameLogic.getActionTimeoutSeconds() + " seconds to repair or sabotage.",
                        state.getPhase().name(), state.getIntegrity());
            }

            // Read subsequent messages
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] tokens = line.split("\\s+", -1);
                String action = tokens[0].toUpperCase();
                String target = tokens.length > 1 ? tokens[1] : "";
                String text = tokens.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(tokens, 2, tokens.length)) : "";

                GameLogic.Result result = logic.act(playerId, action, target, text);

                if (action.equalsIgnoreCase("WHISPER") && result.privateMessage() && !result.message().startsWith("Unknown target")) {
                    communication.sendPrivate(target, "PRIVATE", result.message(), "", state.getPhase().name(), state.getIntegrity());
                    communication.sendPrivate(playerId, "PRIVATE", "Private message sent to " + target + ".", "", state.getPhase().name(), state.getIntegrity());
                } else if (result.privateMessage()) {
                    communication.sendPrivate(playerId, "PRIVATE", result.message(), "", state.getPhase().name(), state.getIntegrity());
                } else {
                    communication.broadcast("EVENT", result.message(), state.getPhase().name(), state.getIntegrity());
                }

                String voteResult = logic.takeVoteResult();
                if (!voteResult.isBlank()) {
                    communication.broadcast("VOTE_RESULT", voteResult, state.getPhase().name(), state.getIntegrity());
                }

                if (state.getPhase() == GamePhase.FINISHED) {
                    communication.broadcast("GAME_OVER", state.getWinner() + " win!", state.getPhase().name(), state.getIntegrity());
                    communication.broadcast("SUMMARY", logic.getGameSummary(), state.getPhase().name(), state.getIntegrity());
                }
            }
        } catch (IOException e) {
            // Player disconnected
        } finally {
            String disconnectedId = communication.findPlayerId(out);
            if (disconnectedId != null) {
                logic.handleDisconnect(disconnectedId);
                String msg = logic.takeDisconnectMessage();
                if (!msg.isEmpty()) {
                    communication.broadcast("DISCONNECT", msg, state.getPhase().name(), state.getIntegrity());
                }
                String voteResult = logic.takeVoteResult();
                if (!voteResult.isEmpty()) {
                    communication.broadcast("VOTE_RESULT", voteResult, state.getPhase().name(), state.getIntegrity());
                }
                if (state.getPhase() == GamePhase.FINISHED) {
                    communication.broadcast("GAME_OVER", state.getWinner() + " win!", state.getPhase().name(), state.getIntegrity());
                }
                communication.unregister(disconnectedId);
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Creates an SSLContext configured with the server's TLS certificates.
     */
    private SSLContext createSSLContext() throws Exception {
        Path certsDir = Path.of("certs");
        char[] storePass = "changeit".toCharArray();

        // Load the keystore (server identity)
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(certsDir.resolve("server.p12"))) {
            keyStore.load(is, storePass);
        }

        // Load the truststore (trusted client certificates)
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(certsDir.resolve("truststore.p12"))) {
            trustStore.load(is, storePass);
        }

        // Initialize KeyManager and TrustManager
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, storePass);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Create and return the SSLContext
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Prompts the player for a name interactively.
     * Re-prompts if the name is empty or already taken (case-insensitive).
     * Returns the player name or null if the connection is lost.
     */
    private String promptForName(BufferedReader in, PrintWriter out) throws IOException {
        while (true) {
            out.println("Enter your name: ");
            out.flush();
            String line = in.readLine();
            if (line == null) return null;
            String name = line.trim();
            if (name.isEmpty()) {
                out.println("Name cannot be empty. Please choose a name.");
                out.flush();
                continue;
            }
            if (logic.isNameTaken(name)) {
                out.println("The name \"" + name + "\" is already taken. Please choose a different name.");
                out.flush();
                continue;
            }
            return name;
        }
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 50051;
        try {
            new GameServer(port);
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
        }
    }
}
