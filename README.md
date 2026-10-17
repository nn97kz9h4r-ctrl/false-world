# The False World v0.14 — separate dimension experiment

**SOURCE ONLY. UNCOMPILED AND UNTESTED. DO NOT INSTALL AS A JAR.**

## Changes
- Bundled `data/falseworld/dimension/false_world.json` registers a separate
  dimension with Overworld-style noise terrain and the same world seed.
- `dimension test` now attempts to teleport the selected victim into that
  dimension; after 60–300 seconds, `dimension return` sends them back to their
  current X/Y/Z in the original world, falling back to the entry location when
  the corresponding destination is obstructed.
- Private fake creeper and primed-TNT entity packets, auto-removed after ~1 sec.
  **They never explode, damage terrain or become real server entities.**
- Commands `/falseworld creepertest`, `/falseworld tnttest`, `/falseworld scareclear`.
- Random scare events during the False World session.
- Existing private block corruption, darkness, NPC text and sound systems retained.

## Important gaps / risks
- **This dimension generates fresh terrain. It does NOT copy player-built bases.**
  Copying chunks and block entities safely is a major separate subsystem.
- The dimension is not instance-isolated: multiple victims share the same
  false dimension, and normal mobs may still spawn.
- Minecraft 1.21.11 teleport and entity packet APIs must be checked against
  real mappings; no compilation or runtime validation has been performed.
- Vanilla server-side blindness/darkness cannot guarantee an entirely black
  screen; custom full-screen visual overlays require client support.
- The Creeper does not visibly enter its swelling animation yet; the illusion
  is a sudden close-up sighting, then disappearance.
- The false world does not yet have physical ruins or Echo Player recordings.
- A server crash during an encounter needs recovery logic to avoid stranding.

**Testing order after successful compilation:** backup your world, target an
operator test account, run `/falseworld dimension test`, then `/falseworld
creepertest` and `/falseworld tnttest`, then `/falseworld dimension return`.
