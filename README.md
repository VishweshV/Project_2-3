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

This compiles the code and produces the application JAR in `target/`.

---

## Play

There are two ways to play: connect to the hosted server, or run a server locally.

### Option 1: Connect to the hosted server

The server is already running on the Google Cloud VM at `136.86.189.126`, port `50051`. You do not need to start a server. Clone the repository and, from the project directory, start a client:

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="136.86.189.126 50051"
```

Run one client per player, in a separate terminal for each player. When prompted, enter your name. The client uses `certs/server.crt` to verify the hosted server. 
### Option 2: Run the server on your own computer

To host a local game, create a private server keystore and matching public certificate on the computer that will run the server. The commands below use the password `changeit`, which matches the current server configuration. Run them from the project directory:

```sh
mkdir -p certs
keytool -genkeypair \
  -alias gameserver \
  -keyalg RSA -keysize 3072 \
  -validity 825 \
  -storetype PKCS12 \
  -keystore certs/server.p12 \
  -storepass changeit -keypass changeit \
  -dname "CN=localhost" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1" \
  -ext "KU=digitalSignature,keyEncipherment" \
  -ext "EKU=serverAuth"

keytool -exportcert -rfc \
  -alias gameserver \
  -keystore certs/server.p12 \
  -storetype PKCS12 -storepass changeit \
  -file certs/server-local.crt

keytool -importcert -noprompt \
  -alias gameserver \
  -file certs/server-local.crt \
  -keystore certs/truststore.p12 \
  -storetype PKCS12 -storepass changeit
```

The client reads `certs/server.crt` to trust the server. For local play, temporarily use the matching local certificate:

```sh
cp certs/server-local.crt certs/server.crt
```

Start the server in one terminal:

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.GameServer -Dexec.args="50051"
```

Start each player in a separate terminal on that same computer:

```sh
mvn exec:java -Dexec.mainClass=edu.spaceship.Player -Dexec.args="localhost 50051"
```

The game starts when **4 players** are connected. When finished, restore the repository's hosted-server certificate so the hosted play instructions work again:

```sh
git restore certs/server.crt
rm -f certs/server-local.crt
```

For clients on other computers, `localhost` will not work: it refers to the computer running each client. Use the server computer's reachable IP address, include that IP as an `IP:` SAN when generating its certificate, and allow inbound TCP port `50051` through its firewall. Every client must trust the certificate presented by that server.


## Playing the Game

After connecting, the client prompts `Enter your name:`. Type your name and press Enter. If the name is already taken (case-insensitive), choose a different name. The client displays your hidden role and a command prompt. The game starts automatically when **4 players** are connected.

Available commands:

```text
STATUS
REPAIR
SABOTAGE
CHAT Hello everyone
WHISPER abc12345 Secret message
VOTE abc12345
VOTE SKIP
QUIT
```

Use `QUIT` to disconnect. See [Game Phases](#game-phases), [Player Commands](#player-commands), and [Win Conditions](#win-conditions) below for details.

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
