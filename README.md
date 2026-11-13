# AntiFreecam

Paper 1.21.11 plugin. Requires the **PacketEvents** plugin (2.11.x+) on the server.

## What it does
- **Underground masking:** players on the surface never receive blocks below `hide-below-y`
  (replaced with a fake block). Freecam / cave-finders only see solid fake blocks.
- **Reveal:** when a player goes below `hide-below-y + reveal-margin`, nearby chunks are re-sent
  and they see the real world (vanilla gameplay underground).
- **Block updates** below the hide line are blocked for masked players.
- **Entity hiding:** mobs/players below the hide line, or with no line of sight, are hidden.
- **Spectator teleport** blocking.

## Build
`mvn package` (JDK 21) or push to GitHub and download the artifact from the Actions tab.
Put `AntiFreecam-1.1.0.jar` and PacketEvents in `plugins/`.

## Commands / permissions
`/antifreecam reload|status` (`antifreecam.admin`), `antifreecam.bypass` for staff.

## Known limits
- Block entities (chests, signs, spawners) below the line are still in the chunk packet.
- A player standing in a cave still sees nearby caves (by design - that is vanilla).
