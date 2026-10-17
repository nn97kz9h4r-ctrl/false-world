# The False World v0.10 — server-side integrated TEST BUILD SOURCE

**IMPORTANT: UNCOMPILED, UNTESTED. This is NOT a working JAR.**

Minecraft 1.21.11, Fabric, JDK 21. **Server-side only** (`fabric.mod.json` environment server). Vanilla clients need no mod.

## Implemented in source
- Victim-only fake player packet *attempt* (player-info and spawn packets) with captured skin GameProfile.
- Mimic observation/stalking/chase state machine, limited horizontal collision avoidance and ground placement.
- Victim-only positional sound and packet-based temporary block hallucinations.
- Attack damage, phase escalation, admin test commands.

## Test commands (operator, permission level 2)
- `/falseworld target <player>` — select victim
- `/falseworld mimic spawn` — request fake player impersonating another ONLINE player
- `/falseworld mimic hide` — remove the fake player
- `/falseworld mimic chase` — force maximum phase
- `/falseworld phase <0..4>` — force phase
- `/falseworld trigger` — force a random horror encounter
- `/falseworld illusiontest` — create a temporary client-only fake block
- `/falseworld soundtest` — private positional sound
- `/falseworld attacktest` — deal 1 heart of magic damage to victim
- `/falseworld disguiseinfo` — show captured identity metadata
- `/falseworld status` — internal status
- `/falseworld reset` — restore blocks and clear session
- `/falseworld disable` / `enable` — switch encounters off/on

## Known serious limitations
- This has NOT been compiled against the Minecraft 1.21.11 mappings. Packet constructors, fake player metadata and skin resolution may require significant fixes.
- Fake player movement currently uses remove-and-respawn packet refresh every five ticks: **visually jarring**, not smooth movement.
- No true A* pathfinding; the actor only tries direct movement and lateral obstacle avoidance.
- No crouching/walking animations or fake-player equipment packets yet.
- Actor identity may collide with a real online player profile; real vanilla player-list semantics require testing.
- Attacks are server-applied independent of client rendering; do not test on a live survival server.
- No world-save persistence; disconnected victim illusion cleanup is best-effort.

## Build
Requires Gradle and online Fabric/Minecraft dependencies. `gradle build` from project root. Only install a successfully compiled remapped JAR after testing on a disposable server.
