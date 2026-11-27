# RedstoneRefresh
Minecraft Beta 1.7.3 ModLoader mod to refresh stuck redstone signals.
## Disclaimer
This mod was made with Claude Sonnet 5.5. 

Before installing the mod, make sure you back up your save files if they contain a lot of complex redstone. While the mod does not store any data in your world files, it can trigger unintended redstone block updates, that can desynchronize or break complex setups.
## Installation
**Requires** Risugami's ModLoader to be installed: https://mcarchive.net/mods/modloader?gvsn=b1.7.3
1. Download RedstoneRefresh-b1.7.3.jar
2. Place the jar in the .minecraft\mods\ folder.
3. Done.
## Features
In beta 1.7.3 when the player loads a world, goes through a nether portal, or generates unloaded chunks, any active redstone lines or clocks remain stuck in their last state. This mod detects blocks with specific IDs, and when such a block's location is within the mod's scan radius, it forces a block update around them, akin to placing a fresh block in their spot. This triggers a block update in the block itself, and the blocks around it, refreshing any stale/stuck circuits.
## Config
You can find the mod's config file in .minecraft\config\ folder "redstonerefresh.config" after launching the game. Any changes made to the file require a game restart.
### Settings:
System settings
- scan_radius - the distance the mod should perform scans for redstone. Default: 16 (Far render distance).
- scan_interval - how often should the mod scan for redstone. Default: 5 (4 times per second).
- schedule_delay - how many ticks should pass before the update is processed. Default: 0.

Which blocks are updated
- Below is a list of blocks, which can be configured to send a block update. By default the mod only attempts to update powered redstone, redstone torches, and powered repeaters. Powered rails, detector rails, sticky pistons and pistons are OFF by default, as they are often used in BUD designs. Redstone and repeaters shouldn't require any block updates in their unpowered state, so they are kept off by default as well.
- custom_blocks - if you wish to add any blocks to the list, which should receive a block update on world load you can specify them here. The list accepts blocks from other mods.
## Limitations
### Precise Timings
Unlike with modern Minecraft, which stores redstone states in chunk data, this mod only sends block updates. What this means is that repeaters and torches that were mid transition, will restart from the beginning of their cycle, rather than carry on from where they left off. If precise timing is required, this mod may cause some circuits to fail. This was done in order to prevent any changes to your world files.

Example: a 4 tick delay redstone repeater is paused and saved when 2 ticks have elapsed. When the chunk is loaded again, the stuck repeater receives an update, and starts its 4 tick delay again.
### BUD Switches
Because of the inherent nature of the mod, any block that is scheduled an update, sends a block update around it. If you have a BUD Switch, that is adjacent to one of these blocks, or an adjacent powered block, it will trigger a block update on chunk load. For the most part, a piston firing on world load shouldn't cause too much of a problem, but if you have designs which depend on precise BUD timings, or designs that would otherwise break, this mod may not be suitable for that purpose.

### Glitches
Beta 1.7.3 has many bugs and glitches, which players can take advantage of during gameplay. If the glitch in question you are attempting to perform involves BUD switches, consider switching this mod off or testing its functionality first in a backup world.
