# Spaceship Sabotage

A text-based multiplayer social deduction game. Players are assigned hidden roles on a spaceship:

- **Crew:** Try to keep the ship running by completing repairs.
- **Saboteur:** Try to destroy the ship by sabotaging systems (0 integrity wins).

Players communicate, debate, and vote to eject suspected saboteurs — but saboteurs can bluff and impersonate crew members.

---

## Architecture

The game uses a **client-server** architecture over **TCP sockets with TLS transport-layer encryption**:

- **Server** (`GameServer.java`): Owns the authoritative, synchronized game state. Accepts player connections on a configurable port (default 50051) using `SSLServerSocket` with TLS.
- **Player** (`Player.java`): Interactive Java client that connects via TLS. All communication is encrypted at the transport layer.
- **Communication** (`Communication.java`): Central library for managing client connections and delivering messages (private or broadcast).
- **Game Logic** (`GameLogic.java`): Validates actions and synchronizes updates to game state.
- **State Management** (`GameState.java` for shared state, `PlayerState.java` for individual player data).
- **Certificates** (`certs/`): Self-signed TLS certificates for server identity and client trust.

### How Messages Flow

1. Player enters a command → client sends it over the TLS-encrypted socket.
2. `GameServer` receives the message and passes it to `GameLogic`.
3. `GameLogic` validates the action and updates game state.
4. `Communication` sends responses (private or broadcast) back to clients over TLS.
5. Clients receive and display the messages.

All communication is encrypted at the transport layer using TLS.

---

## Project Structure

| File | Responsibility |
|---|---|
| `GameServer.java` | Starts TLS server, handles client connections, phase timers |
| `Player.java` | Interactive Java client with TLS encryption |
| `Communication.java` | Manages client writers; sends private and broadcast messages |
| `GameLogic.java` | Game rules, action validation, phase transitions, timer logic |
| `GameState.java` | Shared game state (phase, integrity, players, votes) |
| `PlayerState.java` | Individual player data (ID, name, role, alive status) |
| `GamePhase.java` | Phase enum: LOBBY, ACTION, DISCUSSION, VOTING, FINISHED |
| `certs/` | Self-signed TLS certificates (server.p12, truststore.p12, server.crt) |
| `pom.xml` | Maven build configuration (JDK 17, zero external dependencies) |

---

## Build

Requires **JDK 17+** and **Maven 3.8+**. No external dependencies — TLS encryption uses Java's built-in `javax.net.ssl` package.

```sh
mvn clean package
```

This compiles the code and produces a shaded JAR in `target/`.

---

## Run

**Start the server:**

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.GameServer -Dexec.args="50051"
```

The server prints:
```
Spaceship Sabotage server listening on port 50051 (TLS). Need 4 players to start.
```

**Connect the players** (one command per player, in separate terminals):

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="localhost 50051"
```

After connecting, the client prompts `Enter your name:` — type your name and press Enter. If the name is already taken (case-insensitive), you are asked to choose a different name.

The client displays your hidden role in a bordered box and a command prompt. Type commands directly:

```
STATUS
REPAIR
SABOTAGE
CHAT Hello everyone
WHISPER abc12345 Secret message
VOTE abc12345
VOTE SKIP
QUIT
```

The game starts automatically when **4 players** are connected.

For players on a different machine, use the server's IP address:

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="136.86.189.126 50051"
```

Use `QUIT` to disconnect.

---

## Game Phases

The game cycles through three timed phases automatically:

### 1. ACTION (90 seconds)

- Crew members use `REPAIR` to fix systems (+10% integrity).
- Saboteurs use `SABOTAGE` to damage systems (-20% integrity; 0% wins).
- No discussion or voting is allowed.
- A countdown timer broadcasts every second.

### 2. DISCUSSION (120 seconds)

- Players can `CHAT` publicly or `WHISPER` privately.
- No actions or voting is allowed.
- A countdown timer broadcasts every second.

### 3. VOTING (90 seconds)

- Players vote to eject a suspect with `VOTE <player-id>` or abstain with `VOTE SKIP`.
- If all living players vote before time expires, the vote resolves immediately.
- If the timer expires before all players vote, no one is ejected.
- After voting resolves, the game returns to ACTION.
- A countdown timer broadcasts every second.

The cycle repeats: **ACTION → DISCUSSION → VOTING → ACTION → ...**

---

## Player Commands

| Command | Description |
|---|---|
| `STATUS` | Show the game phase, time remaining, ship integrity, and living role counts. |
| `REPAIR` | Crew repairs the ship (ACTION phase only). |
| `SABOTAGE` | A Saboteur damages ship integrity (ACTION phase only); zero integrity wins for Saboteurs. |
| `CHAT <message>` | Send a public message (DISCUSSION phase only). |
| `WHISPER <player-id> <message>` | Send a private message to a player (DISCUSSION phase only). Player IDs are shown when players join. |
| `VOTE <player-id>` | Vote to eject a living player (VOTING phase only). |
| `VOTE SKIP` | Abstain from voting. |
| `QUIT` | Disconnect the player. |

---

## Win Conditions

- **Crew wins** by ejecting all Saboteurs.
- **Saboteurs win** by reducing ship integrity to 0% OR eliminating all Crew members.

When the game ends, all players receive a `GAME_OVER` message and a full role reveal summary.

---

## Wire Protocol

### Registration

After connecting, the server prompts for your name interactively. Once accepted, the server sends:

```
REGISTERED|Registered as Alice (a1b2c3d4). Waiting for 4 players.|a1b2c3d4
```

### Commands

Commands are sent as plain text over the TLS-encrypted connection:

```
STATUS
REPAIR
SABOTAGE
CHAT Hello everyone
WHISPER abc12345 Secret message
VOTE abc12345
VOTE SKIP
QUIT
```

### Server Messages

Format: `TYPE|text||phase|integrity`

Message types: `INFO`, `ROLE`, `EVENT`, `PRIVATE`, `PHASE_CHANGE`, `VOTE_RESULT`, `GAME_OVER`, `SUMMARY`, `DISCONNECT`, `ERROR`.

---

## TLS Certificates

The game uses **TLS** (Transport Layer Security) for all communication. Self-signed certificates are provided in the `certs/` directory:

| File | Purpose |
|---|---|
| `certs/server.p12` | Server keystore (PKCS12, password: `changeit`) |
| `certs/truststore.p12` | Server truststore (PKCS12, password: `changeit`) |
| `certs/server.crt` | Server certificate (PEM format, for client trust) |

- The server loads `server.p12` and `truststore.p12` to establish its TLS identity.
- The client loads `server.crt` to trust the server's certificate.
- Certificates are valid for 10 years and include SANs for `localhost`, `127.0.0.1`, and common LAN addresses.
- No external dependencies are needed — Java's built-in `javax.net.ssl` package provides all TLS functionality.

---

## Network Setup

### Same Machine

Connect using `localhost`:

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="localhost 50051"
```

### Local Network (LAN)

1. Find the server machine's IP address:
   - **macOS:** `ipconfig getifaddr en0`
   - **Linux:** `hostname -I`
   - **Windows:** `ipconfig` (look for IPv4 Address)

2. Start the server on the host machine.

3. Connect players from other machines using the server's IP address:
   ```sh
   mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="136.86.189.126 50051"
   ```

### Firewall

Make sure port `50051` is open for incoming connections:

- **macOS:** System Settings → Network → Firewall → Options, allow Java.
- **Linux:** `sudo ufw allow 50051/tcp`
- **Windows:** Windows Defender Firewall → Allow an app → Java.

---

## Duplicate Name Handling

Player names are checked case-insensitively. If two players attempt to use the same name (e.g., "Alice" and "alice"), the second player receives an error and is prompted to choose a different name. Each player is uniquely identified by a random 8-character ID.

---

## Disconnection Handling

When a player disconnects (via `QUIT`, network failure, or process crash):

- The player is marked as eliminated in the game state.
- Any pending vote from the disconnected player is removed.
- If all remaining living players have voted, the vote resolves immediately.
- Win conditions are checked (e.g., all saboteurs disconnected → Crew win).
- A `DISCONNECT` notification is broadcast to remaining players.
