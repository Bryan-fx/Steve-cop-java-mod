# PoliceSteve (summon-npc edition)

A Paper plugin: when a player is killed by another player, "Officer Steve" —
a real, Steve-skinned NPC (powered by the Summon plugin) — spawns and
hunts down the killer.

## Requirements
- **Paper** (or a Paper fork) — SummonNpcs does not support vanilla Spigot.
- Java 21+, Maven
- The Summon plugin installed
  on the server (this plugin depends on it and won't load without it)

## How it works
Since Summon NPCs are lightweight, packet-only entities with **no built-in
health, combat, or AI**, this plugin implements all of that itself on top of
SummonNpcs' visual/interaction API:

- On a PvP kill, Officer Steve spawns at the death location wearing the
  classic Steve skin, blue leather helmet + chestplate, and an iron sword.
- **Chasing:** every ~0.2s he takes a step directly toward the killer. This is
  straight-line homing, not real pathfinding - SummonNpcs doesn't provide
  terrain-aware navigation, so he won't walk around obstacles the way a real
  mob would.
- **Attacking:** once within melee range, he deals exactly 1 heart (2 HP) of
  damage to the killer, at most once per second. (Note: armor/resistance can
  still reduce the actual damage taken, same as any other damage source in
  vanilla Minecraft.)
- **Taking hits:** every left-click on him counts as one hit, regardless of
  weapon (SummonNpcs has no real damage model to hook into, so this plugin
  counts clicks instead). After 3 hits he goes down.
- **Death drop:** he drops a paper item named "Ticket to Jaildonia for
  <killer's name>".

## Building
From the `police-steve` folder:

```bash
mvn clean package
```

The compiled plugin will be at `target/PoliceSteve.jar`.

Before building, open `pom.xml` and:
1. Set the `paper-api` version to match your server's Minecraft version.
2. Check the `SummonNpcs` dependency version against what's published at
   https://repo.fancyplugins.de/releases (or the GitHub releases page) - the
   jar you're running is build `2.11.0+370`, and the pom currently targets
   `2.11.0`.

## Installing
1. Make sure SummonNpcs is already installed and working on your server.
2. Copy `PoliceSteve.jar` into your server's `plugins/` folder.
3. Restart the server.
4. Kill another player in a PvP-enabled world to test it.

## Customizing
Open `PoliceListener.java`:
- `HITS_TO_DEFEAT` - how many left-clicks take Officer Steve down.
- `ATTACK_DAMAGE` / `ATTACK_RANGE` / `ATTACK_COOLDOWN_TICKS` - his attack behavior.
- `MOVE_SPEED` - how fast he closes distance on the killer.
- `STEVE_SKIN` - the skin identifier (currently `"MHF_Steve"`, a long-standing
  Mojang account with the default Steve skin). If it ever stops resolving,
  swap in another username with the default skin, or use a direct skin URL
  (SummonNpcs' `setSkin(String)` also accepts URLs).
- The ticket's name/lore text is in `dropTicket()`.
- Equipment/armor color is set in `spawnOfficer()` / `blueLeather()`.
