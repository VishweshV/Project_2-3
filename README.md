# Spaceship Sabotage

A text-based multiplayer social deduction game. Players are assigned hidden roles on a spaceship:

- **Crew:** Try to keep the ship running by completing repairs.
- **Saboteur:** Try to destroy the ship by sabotaging systems (0 integrity wins).

Players communicate, debate, and vote to eject suspected saboteurs — but saboteurs can bluff and impersonate crew members.

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
