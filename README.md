# The False World v0.12 — false-world session prototype

**UNCOMPILED AND UNTESTED SOURCE. NOT A SERVER-READY JAR.**

## New
- At horror phase 3+, an encounter has a configurable probability of starting a
  private **false-world illusion session**.
- Session duration is randomly 60–300 seconds (1–5 minutes).
- The player continues moving in the *real* world, with victim-only nighttime,
  temporary fake blocks, sounds and occasional Darkness/Blindness.
- At expiry, private block illusions are restored and time is resynchronized.
- The player is **already at the exact same world coordinates** they reached
  during the session, because no teleport occurred.
- OP commands: `/falseworld target <name>`, `/falseworld dimension test`,
  `/falseworld dimension return`, `/falseworld dimension status`.
- Config: `false_world.enabled`, `false_world.chance_percent`,
  `false_world.min_seconds`, `false_world.max_seconds`.
- Automatic session cancellation on world change and disconnect.

## Important limitations
This is NOT a new Minecraft dimension or an independent physical copy of the
world. A true cloned dimension requires a dimension definition, terrain/chunk
copying, player teleportation, entity/block updates and persistent state, which
this implementation does not provide. Other players still exist in the same
physical world; packet-only illusions cannot fully isolate their interactions.
The 1.21.11 mappings, packet constructors and build remain unverified.
The existing Mimic packet implementation also remains experimental.

Do not install the source ZIP into your server's `mods` folder.
