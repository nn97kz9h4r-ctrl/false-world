# The False World v0.16 — Flat Dimension Landing & Ruins (SOURCE ONLY)

**Not compiled or verified on Fabric 1.21.11. Do NOT install this ZIP as a mod.**

## Changes
- Dimension JSON uses flat world generation (desert biome, no generated features).
- Safe arrival platform generated at heightmap surface, with 7x7 solid floor and 4-block-high cleared air pocket; player teleports to the platform rather than a hard-coded Y value.
- Four physical ruined buildings generated around arrival in the False World.
- Existing skeletal tree / chapel generator preserved.
- `/falseworld dimension groundtest` repairs and teleports victim onto safe ground.
- `/falseworld dimension scenetest` generates a cluster of ruins near victim.
- `/falseworld dimension test` reports failure instead of falsely claiming success.
- NPC dialogue and experimental fake-player Echo logic retained, but **no physical walking NPC AI** is implemented.

## Testing
1. Back up the server and world first.
2. Build the mod and resolve API compilation errors before installing the JAR.
3. If the False World has been generated previously, back it up and reset only that dimension's saved region data while the server is stopped. Worldgen JSON changes do not replace existing chunks.
4. `/falseworld target <player>` then `/falseworld dimension test`.
5. If you need to test ground safety: `/falseworld dimension groundtest`.
6. To regenerate physical ruins near the player: `/falseworld dimension scenetest`.
7. `/falseworld dimension return`.

**Limitations:** This does not yet implement moving physical NPCs, copied player bases, independent per-victim dimensions or compiled/tested 1.21.11 compatibility. The dimension terrain is flat in newly generated chunks only. Existing generated chunks must be reset separately.
