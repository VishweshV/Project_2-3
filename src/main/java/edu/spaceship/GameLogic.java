package edu.spaceship;

import java.util.*;

/** Validates player actions and updates the synchronized shared state. */
public final class GameLogic {
    public record Result(String message, boolean privateMessage) {}
    private final GameState state;
    private final Random random = new Random();

    public GameLogic(GameState state) { this.state = state; }

    /** Check if a name is already taken (case-insensitive). Does not register the player. */
    public synchronized boolean isNameTaken(String name) {
        String trimmed = name.trim();
        return state.getPlayers().values().stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(trimmed));
    }

    public synchronized String register(String id, String name) {
        if (state.getPhase() != GamePhase.LOBBY) return "The game has already started.";
        if (name.isBlank()) return "Player name cannot be empty.";
        String trimmed = name.trim();
        boolean nameTaken = state.getPlayers().values().stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(trimmed));
        if (nameTaken) return "NAME_TAKEN|The name \"" + trimmed + "\" is already taken. Please choose a different name.";
        state.getPlayers().put(id, new PlayerState(id, trimmed));
        return "Registered as " + trimmed + " (" + id + "). Waiting for " + GameState.MIN_PLAYERS + " players.";
    }

    public synchronized Map<String, String> startIfReady() {
        if (state.getPhase() != GamePhase.LOBBY || state.getPlayers().size() < GameState.MIN_PLAYERS) return Map.of();
        List<PlayerState> players = new ArrayList<>(state.getPlayers().values());
        Collections.shuffle(players, random);
        int saboteurCount = Math.max(1, players.size() / 4);
        for (int i = 0; i < players.size(); i++) players.get(i).setRole(i < saboteurCount ? PlayerState.Role.SABOTEUR : PlayerState.Role.CREW);
        state.setPhase(GamePhase.ACTION);
        actionStartTime = System.currentTimeMillis();
        actionTimerActive = true;
        Map<String, String> roles = new LinkedHashMap<>();
        players.forEach(p -> roles.put(p.getId(), p.getRole().name()));
        return roles;
    }

    public synchronized Result act(String id, String action, String target, String text) {
        PlayerState player = state.getPlayers().get(id);
        if (player == null) return new Result("Register before acting.", true);
        if (!player.isAlive()) return new Result("Eliminated players cannot act.", true);
        action = action.toUpperCase(Locale.ROOT);
        switch (action) {
            case "STATUS" -> {
                return new Result("Phase: " + state.getPhase() + " | Integrity: " + state.getIntegrity() + "% | Living crew: " + state.aliveCount(PlayerState.Role.CREW) + " | Living saboteurs: " + state.aliveCount(PlayerState.Role.SABOTEUR), true);
            }
            case "REPAIR", "TASK" -> {
                if (state.getPhase() != GamePhase.ACTION) return new Result("Repairs are only allowed during ACTION phase.", true);
                if (player.getRole() != PlayerState.Role.CREW) return new Result("Saboteurs cannot complete crew repairs.", true);
                player.completeTask(); state.setIntegrity(state.getIntegrity() + 10);
                return new Result(player.getName() + " completed a repair. Ship integrity is " + state.getIntegrity() + "%.", false);
            }
            case "SABOTAGE" -> {
                if (state.getPhase() != GamePhase.ACTION) return new Result("Sabotage is only allowed during ACTION phase.", true);
                if (player.getRole() != PlayerState.Role.SABOTEUR) return new Result("Only saboteurs can sabotage.", true);
                state.setIntegrity(state.getIntegrity() - 20);
                checkWin();
                return new Result("A system was sabotaged. Ship integrity is now " + state.getIntegrity() + "%.", false);
            }
            case "CHAT", "SAY" -> {
                if (state.getPhase() != GamePhase.DISCUSSION) return new Result("Chat is only allowed during DISCUSSION phase.", true);
                return new Result(player.getName() + ": " + (text.isBlank() ? target : text), false);
            }
            case "WHISPER" -> {
                if (state.getPhase() != GamePhase.DISCUSSION) return new Result("Whisper is only allowed during DISCUSSION phase.", true);
                PlayerState recipient = state.getPlayers().get(target);
                if (recipient == null) return new Result("Unknown target. Use the player's ID.", true);
                return new Result("[Private from " + player.getName() + "] " + text, true);
            }
            case "VOTE" -> {
                if (state.getPhase() != GamePhase.VOTING) return new Result("Voting is not open. Wait for the VOTING phase.", true);
                boolean skip = target.equalsIgnoreCase("SKIP");
                PlayerState candidate = skip ? null : state.getPlayers().get(target);
                if (!skip && (candidate == null || !candidate.isAlive())) return new Result("Choose the ID of a living player, or use SKIP.", true);
                state.getVotes().put(id, skip ? "SKIP" : target);
                if (state.allLivingPlayersVoted()) resolveVote();
                return new Result("Vote recorded. " + state.getVotes().size() + " vote(s) received.", false);
            }
            default -> { return new Result("Unknown action. Try STATUS, REPAIR, SABOTAGE, CHAT, WHISPER, or VOTE.", true); }
        }
    }

    private void resolveVote() {
        Map<String, Long> counts = new HashMap<>();
        state.getVotes().values().stream().filter(v -> !v.equals("SKIP")).forEach(v -> counts.merge(v, 1L, Long::sum));
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> leaders = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        String result;
        if (max == 0 || leaders.size() != 1) result = "Voting ended in a tie or skip; no one was ejected.";
        else {
            PlayerState eliminated = state.getPlayers().get(leaders.get(0));
            eliminated.setAlive(false);
            result = eliminated.getName() + " was ejected. Their role was " + eliminated.getRole() + ".";
        }
        state.getVotes().clear();
        if (state.getPhase() != GamePhase.FINISHED) {
            // Transition back to ACTION phase after voting
            state.setPhase(GamePhase.ACTION);
            actionStartTime = System.currentTimeMillis();
            actionTimerActive = true;
            lastActionMessage = "Voting complete. ACTION phase has begun. You have " + ACTION_TIMEOUT_SECONDS + " seconds to repair or sabotage.";
        }
        checkWin();
        state.setWinner(state.getWinner().isEmpty() ? "" : state.getWinner());
        lastVoteResult = result;
    }
    private String lastVoteResult = "";
    private String lastDisconnectMessage = "";
    private String lastActionMessage = "";
    
    // ACTION phase timer
    private static final int ACTION_TIMEOUT_SECONDS = 90;
    private long actionStartTime = 0;
    private boolean actionTimerActive = false;
    
    // DISCUSSION phase timer
    private static final int DISCUSSION_TIMEOUT_SECONDS = 120;
    private long discussionStartTime = 0;
    private boolean discussionTimerActive = false;
    
    // VOTING phase timer
    private static final int VOTING_TIMEOUT_SECONDS = 90;
    private long votingStartTime = 0;
    private boolean votingTimerActive = false;
    
    public static int getActionTimeoutSeconds() { return ACTION_TIMEOUT_SECONDS; }
    public static int getDiscussionTimeoutSeconds() { return DISCUSSION_TIMEOUT_SECONDS; }
    public static int getVotingTimeoutSeconds() { return VOTING_TIMEOUT_SECONDS; }
    
    /** Check if the ACTION timer has expired and transition to DISCUSSION if so. Returns true if transition happened. */
    public synchronized boolean checkActionTimer() {
        if (!actionTimerActive) return false;
        if (state.getPhase() != GamePhase.ACTION) {
            actionTimerActive = false;
            return false;
        }
        long elapsed = (System.currentTimeMillis() - actionStartTime) / 1000;
        if (elapsed >= ACTION_TIMEOUT_SECONDS) {
            // Time's up — transition to DISCUSSION
            actionTimerActive = false;
            state.setPhase(GamePhase.DISCUSSION);
            state.getVotes().clear();
            discussionStartTime = System.currentTimeMillis();
            discussionTimerActive = true;
            lastActionMessage = "Action time is up. DISCUSSION phase has begun. You have " + DISCUSSION_TIMEOUT_SECONDS + " seconds to discuss.";
            return true;
        }
        return false;
    }
    
    /** Check if the DISCUSSION timer has expired and transition to VOTING if so. Returns true if transition happened. */
    public synchronized boolean checkDiscussionTimer() {
        if (!discussionTimerActive) return false;
        if (state.getPhase() != GamePhase.DISCUSSION) {
            discussionTimerActive = false;
            return false;
        }
        long elapsed = (System.currentTimeMillis() - discussionStartTime) / 1000;
        if (elapsed >= DISCUSSION_TIMEOUT_SECONDS) {
            // Time's up — transition to VOTING
            discussionTimerActive = false;
            state.setPhase(GamePhase.VOTING);
            votingStartTime = System.currentTimeMillis();
            votingTimerActive = true;
            lastVoteResult = "Discussion time is up. Voting phase has begun. You have " + VOTING_TIMEOUT_SECONDS + " seconds to vote.";
            return true;
        }
        return false;
    }
    
    /** Check if the VOTING timer has expired and transition back to ACTION if so. Returns true if transition happened. */
    public synchronized boolean checkVotingTimer() {
        if (!votingTimerActive) return false;
        if (state.getPhase() != GamePhase.VOTING) {
            votingTimerActive = false;
            return false;
        }
        long elapsed = (System.currentTimeMillis() - votingStartTime) / 1000;
        if (elapsed >= VOTING_TIMEOUT_SECONDS) {
            // Time's up — no ejection, transition back to ACTION
            votingTimerActive = false;
            state.getVotes().clear();
            state.setPhase(GamePhase.ACTION);
            actionStartTime = System.currentTimeMillis();
            actionTimerActive = true;
            lastVoteResult = "Voting time is up. No one was ejected. ACTION phase has begun.";
            lastActionMessage = "Voting time is up. No one was ejected. ACTION phase has begun. You have " + ACTION_TIMEOUT_SECONDS + " seconds to repair or sabotage.";
            return true;
        }
        return false;
    }
    
    
    public synchronized String takeVoteResult() { String result = lastVoteResult; lastVoteResult = ""; return result; }
    public synchronized String takeDisconnectMessage() { String result = lastDisconnectMessage; lastDisconnectMessage = ""; return result; }
    public synchronized String takeActionMessage() { String result = lastActionMessage; lastActionMessage = ""; return result; }

    /** Handle a player disconnecting: mark as not alive, remove votes, check win conditions, and resolve hanging votes. */
    public synchronized void handleDisconnect(String id) {
        PlayerState player = state.getPlayers().get(id);
        if (player == null || !player.isAlive()) return; // already handled or not registered

        String playerName = player.getName();
        player.setAlive(false);

        // Remove any pending vote from this player
        state.getVotes().remove(id);

        // Stop all timers if active
        actionTimerActive = false;
        discussionTimerActive = false;
        votingTimerActive = false;

        // Check if the game is stuck in a voting/discussion phase and resolve it
        if (state.getPhase() == GamePhase.VOTING || state.getPhase() == GamePhase.DISCUSSION) {
            if (state.allLivingPlayersVoted()) {
                resolveVote();
            } else if (state.getPlayers().values().stream().filter(PlayerState::isAlive).count() == 0) {
                // No living players left, resolve with current votes
                resolveVote();
            }
            // If there are still living players who haven't voted, the vote continues
        }

        // Check win conditions
        checkWin();

        lastDisconnectMessage = playerName + " has disconnected.";
    }

    private void checkWin() {
        if (state.getIntegrity() <= 0 || state.aliveCount(PlayerState.Role.CREW) == 0) finish("SABOTEURS");
        else if (state.aliveCount(PlayerState.Role.SABOTEUR) == 0) finish("CREW");
    }
    private void finish(String winner) { state.setWinner(winner); state.setPhase(GamePhase.FINISHED); }

    /** Build a post-game summary revealing every player's role. */
    public synchronized String getGameSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(state.getWinner()).append(" win! Game summary:\n");
        for (PlayerState p : state.getPlayers().values()) {
            String status = p.isAlive() ? "alive" : "eliminated";
            sb.append("  ").append(p.getName()).append(" (").append(p.getId()).append(") was ").append(p.getRole()).append(" - ").append(status).append("\n");
        }
        return sb.toString();
    }

    public synchronized GameState getState() { return state; }
}
