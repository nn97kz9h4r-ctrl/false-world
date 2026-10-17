# The False World v0.15 — Flat corrupted dimension prototype

**SOURCE ONLY. NOT COMPILED OR TESTED.** This is not a usable server JAR.

Changes from uploaded v0.14:
- Replaced ordinary noise Overworld generation with a flat dimension definition.
- Physical leafless skeletal trees, a hollow ruined chapel, and cobwebs placed in the
  dedicated False World (not the original Overworld).
- Generated sites repeat in 128x128 regions as players explore.
- Entry moves players to the flat world's surface; return attempts to find the real
  world's surface at their final X/Z instead of returning at flat-world Y.
- Existing fake-player Echo and creepy dialogue hooks remain in source.

**Not implemented:** actual copying of the player's base, NPC walking AI,
true dense fog, terrain streaming, structure persistence bookkeeping across
server restarts, complete safety/return handling. Generated sites are tracked
in memory only and may be rewritten after restart. The world generator's
exact 1.21.11 JSON and Java APIs need compilation and in-game validation.

**Important:** Minecraft saves generated dimension chunks. For existing test
worlds, changing the dimension JSON will NOT flatten previously generated
chunks. Test on a new disposable world/server backup.
