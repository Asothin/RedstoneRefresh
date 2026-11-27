import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * RedstoneRefresh (Beta 1.7.3, ModLoader, singleplayer).
 *
 * Pending block ticks are not saved with a chunk, so a world saved while a clock or
 * torch/repeater was mid-transition loads with circuits stuck. When a chunk is
 * loaded this mod re-triggers updates for the blocks selected in the config. By default:
 *   - redstone dust, id 55, metadata 1-15 (powered)
 *   - redstone torch, id 75 (unlit) and 76 (lit)
 *   - redstone repeater (on), id 94
 * Optional switches: unpowered dust (55:0), powered rail 27, detector rail 28, sticky
 * piston 29, piston 33, plus a custom_blocks list of any block ids (55 or 55:0, 55:1-15).
 *
 * Settings live in "redstonerefresh.config" in the .minecraft/config folder. It is
 * created with the defaults on first run.
 * Edit it and restart the game to apply changes.
 *   scan_radius    chunks around the player that are checked for newly loaded chunks
 *   scan_interval  game ticks between scans (20 ticks = 1 second)
 *   schedule_delay ticks passed to the block-update schedule (0-100, default 0)
 *   <block switches> and custom_blocks: which blocks are refreshed
 *
 * Obfuscated names used (b1.7.3 client):
 *   fd = World, lm = Chunk, cl = IChunkProvider, uu = Block, dc = EntityPlayerSP
 *   fd.a(x,y,z) getBlockId, fd.e(x,y,z) getBlockMetadata,
 *   fd.c(x,y,z,id,delay) scheduleBlockUpdate, fd.i(x,y,z,id) notifyBlocksOfNeighborChange,
 *   fd.w() getChunkProvider, fd.B multiplayerWorld, uu.m Block.blocksList,
 *   uu.c(fd,x,y,z) onBlockAdded, cl.a(cx,cz) chunkExists, cl.b(cx,cz) provideChunk,
 *   lm.b block array, lm.j/k chunk x/z, Minecraft.f theWorld, Minecraft.h thePlayer,
 *   Minecraft.b() .minecraft folder, sn.aM / sn.aO posX / posZ
 */
public class mod_RedstoneRefresh extends BaseMod {

    private static final int WIRE = 55;
    private static final int TORCH_OFF = 75;
    private static final int TORCH_ON = 76;
    private static final int REPEATER_ON = 94;

    static final String CONFIG_NAME = "redstonerefresh.config";
    static final int DEFAULT_SCAN_RADIUS = 16;
    static final int DEFAULT_SCAN_INTERVAL = 5;
    static final int DEFAULT_SCHEDULE_DELAY = 0;

    /** Metadata bit mask meaning "every metadata value" (bit m = metadata m). */
    private static final int ALL = 0xFFFF;
    /** Built-in switches: config key, block id, metadata values covered, default. */
    private static final String[] PRESET_KEY = { "dust_powered", "dust_unpowered", "torch_unlit", "torch_lit",
            "repeater_powered", "powered_rail", "detector_rail", "sticky_piston", "piston" };
    private static final int[] PRESET_ID = { 55, 55, 75, 76, 94, 27, 28, 29, 33 };
    private static final int[] PRESET_MASK = { 0xFFFE, 0x0001, ALL, ALL, ALL, ALL, ALL, ALL, ALL };
    private static final boolean[] PRESET_DEFAULT = { true, false, true, true, true, false, false, false, false };
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Chunks around the player that are checked for newly loaded chunks. */
    private int scanRadius = DEFAULT_SCAN_RADIUS;
    /** Game ticks between scans. */
    private int scanInterval = DEFAULT_SCAN_INTERVAL;
    /** Delay passed to scheduleBlockUpdate for torches / repeaters. */
    private int scheduleDelay = DEFAULT_SCHEDULE_DELAY;

    private final boolean[] presetOn = PRESET_DEFAULT.clone();
    /** Metadata values added by custom_blocks, per block id. */
    private final int[] customMask = new int[256];
    /** Final result: for each block id, the metadata values that are refreshed (0 = none). */
    private final int[] metaMask = new int[256];

    private fd lastWorld;
    private final Set<Long> processed = new HashSet<Long>();
    private int tickCounter;

    public mod_RedstoneRefresh() {
        loadConfig();
        // enabled, once per world tick
        ModLoader.SetInGameHook(this, true, true);
    }

    @Override
    public String Version() {
        return "1.0 (Beta 1.7.3)";
    }

    // ---------------------------------------------------------------- config

    /** .minecraft/config, created if it does not exist yet. */
    private static File configFolder() {
        File dir = new File(Minecraft.b(), "config");
        dir.mkdirs();
        return dir;
    }

    private void loadConfig() {
        File file = new File(configFolder(), CONFIG_NAME);
        if (!file.isFile()) {
            writeDefaultConfig(file);
        } else {
            try {
                parseConfig(Files.readAllLines(file.toPath(), UTF8), file.getName());
            } catch (IOException e) {
                System.out.println("[RedstoneRefresh] could not read " + file + ", using defaults: " + e);
            }
        }
        buildMetaMask();
        System.out.println("[RedstoneRefresh] config " + file.getPath() + ": scan_radius=" + scanRadius
                + " scan_interval=" + scanInterval + " schedule_delay=" + scheduleDelay
                + " blocks=" + describeBlocks());
    }

    private void parseConfig(List<String> lines, String name) {
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                warn(name, n + 1, "expected key=value, ignored: " + line);
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase();
            String text = line.substring(eq + 1).trim();

            if (key.equals("custom_blocks")) {
                parseCustomBlocks(text, name, n + 1);
                continue;
            }
            int preset = presetIndex(key);
            if (preset >= 0) {
                int on = parseSwitch(text);
                if (on < 0) {
                    warn(name, n + 1, "'" + text + "' is not on/off, keeping the default for " + key);
                } else {
                    presetOn[preset] = on == 1;
                }
                continue;
            }
            if (!key.equals("scan_radius") && !key.equals("scan_interval") && !key.equals("schedule_delay")) {
                warn(name, n + 1, "unknown setting '" + key + "' ignored");
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                warn(name, n + 1, "'" + text + "' is not a whole number, keeping the default for " + key);
                continue;
            }
            if (key.equals("scan_radius")) {
                scanRadius = clamp(name, n + 1, key, value, 1, 64);
            } else if (key.equals("scan_interval")) {
                scanInterval = clamp(name, n + 1, key, value, 1, 1200);
            } else {
                scheduleDelay = clamp(name, n + 1, key, value, 0, 100);
            }
        }
    }

    private static int clamp(String name, int line, String key, int value, int min, int max) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            warn(name, line, key + "=" + value + " is outside " + min + "-" + max + ", using " + fixed);
            return fixed;
        }
        return value;
    }

    private static void warn(String name, int line, String message) {
        System.out.println("[RedstoneRefresh] " + name + " line " + line + ": " + message);
    }

    private static int presetIndex(String key) {
        for (int i = 0; i < PRESET_KEY.length; i++) {
            if (PRESET_KEY[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** 1 = on, 0 = off, -1 = not understood. */
    private static int parseSwitch(String text) {
        String s = text.trim().toLowerCase();
        if (s.equals("on") || s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("enabled")) {
            return 1;
        }
        if (s.equals("off") || s.equals("false") || s.equals("no") || s.equals("0") || s.equals("disabled")) {
            return 0;
        }
        return -1;
    }

    /** custom_blocks=55, 27:0, 29:8-15 : "55" = every metadata value, "55:0" / "55:1-15" = only those. */
    private void parseCustomBlocks(String text, String name, int line) {
        Arrays.fill(customMask, 0);
        for (String part : text.split(",")) {
            String entry = part.trim();
            if (entry.length() == 0) {
                continue;
            }
            try {
                int colon = entry.indexOf(':');
                int id = Integer.parseInt((colon < 0 ? entry : entry.substring(0, colon)).trim());
                if (id < 1 || id > 255) {
                    warn(name, line, "block id " + id + " is outside 1-255, ignored");
                    continue;
                }
                int mask = ALL;
                if (colon >= 0) {
                    String m = entry.substring(colon + 1).trim();
                    int dash = m.indexOf('-');
                    int lo = Integer.parseInt((dash < 0 ? m : m.substring(0, dash)).trim());
                    int hi = dash < 0 ? lo : Integer.parseInt(m.substring(dash + 1).trim());
                    if (lo < 0 || hi > 15 || lo > hi) {
                        warn(name, line, "'" + entry + "': metadata must be within 0-15 (low-high), ignored");
                        continue;
                    }
                    mask = ((1 << (hi + 1)) - 1) & ~((1 << lo) - 1);
                }
                customMask[id] |= mask;
            } catch (NumberFormatException e) {
                warn(name, line, "'" + entry + "' is not a block id like 55, 55:0 or 55:1-15, ignored");
            }
        }
    }

    /** Built-in toggles that are on, plus the custom list (which only adds blocks whose toggle is off). */
    private void buildMetaMask() {
        Arrays.fill(metaMask, 0);
        for (int i = 0; i < PRESET_KEY.length; i++) {
            if (presetOn[i]) {
                metaMask[PRESET_ID[i]] |= PRESET_MASK[i];
            }
        }
        for (int id = 1; id < 256; id++) {
            metaMask[id] |= customMask[id];
        }
    }

    private String describeBlocks() {
        StringBuilder sb = new StringBuilder();
        for (int id = 1; id < 256; id++) {
            int mask = metaMask[id];
            if (mask == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(id);
            if (mask != ALL) {
                sb.append(':');
                boolean first = true;
                for (int m = 0; m < 16; m++) {
                    if ((mask >> m & 1) == 0) {
                        continue;
                    }
                    int end = m;
                    while (end < 15 && (mask >> (end + 1) & 1) != 0) {
                        end++;
                    }
                    sb.append(first ? "" : ",").append(m);
                    if (end > m) {
                        sb.append('-').append(end);
                    }
                    first = false;
                    m = end;
                }
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private static void writeDefaultConfig(File file) {
        String nl = System.getProperty("line.separator");
        String text = "# RedstoneRefresh settings (Beta 1.7.3, singleplayer)." + nl
                + "# Lines starting with # are comments. Restart the game after editing." + nl
                + "# A setting that is missing, not understood, or out of range falls back to its" + nl
                + "# default / the nearest allowed value (a warning is printed to the console)." + nl
                + nl
                + "# scan_radius (1-64): how many chunks around the player are checked for newly" + nl
                + "# loaded chunks. Chunks further away are refreshed once you get close enough." + nl
                + "# Use at least your render distance (Far = 16 chunks) to cover everything you see." + nl
                + "scan_radius=" + DEFAULT_SCAN_RADIUS + nl
                + nl
                + "# scan_interval (1-1200): game ticks between scans (20 ticks = 1 second)." + nl
                + "# Lower = a new chunk is refreshed sooner but the check runs more often." + nl
                + "# Higher = less work, but a new chunk waits up to this many ticks." + nl
                + "scan_interval=" + DEFAULT_SCAN_INTERVAL + nl
                + nl
                + "# schedule_delay (0-100): ticks before the scheduled update of a refreshed block" + nl
                + "# (torch, repeater, rail, piston...) runs. 0 = as soon as possible, 1 = next tick." + nl
                + "# Blocks re-check their own input when it runs, so small values are fine." + nl
                + "schedule_delay=" + DEFAULT_SCHEDULE_DELAY + nl
                + nl
                + "# ---- Which blocks are updated ----" + nl
                + "# Each line switches one block type on or off (on/off, true/false, yes/no, 1/0)." + nl
                + nl
                + "# Redstone dust that is powered (55, metadata 1-15)." + nl
                + "dust_powered=on" + nl
                + "# Redstone dust that is NOT powered (55, metadata 0). Updates every unpowered wire." + nl
                + "dust_unpowered=off" + nl
                + "# Redstone torch, unlit (75)." + nl
                + "torch_unlit=on" + nl
                + "# Redstone torch, lit (76)." + nl
                + "torch_lit=on" + nl
                + "# Redstone repeater, powered (94)." + nl
                + "repeater_powered=on" + nl
                + "# Powered rail (27)." + nl
                + "powered_rail=off" + nl
                + "# Detector rail (28)." + nl
                + "detector_rail=off" + nl
                + "# Sticky piston (29)." + nl
                + "sticky_piston=off" + nl
                + "# Piston (33)." + nl
                + "piston=off" + nl
                + nl
                + "# custom_blocks: extra blocks to update, as block IDs separated by commas." + nl
                + "#   55          every variation (all metadata values) of block 55" + nl
                + "#   55:0, 55:1  only those metadata values; the block's other values are left alone" + nl
                + "#   55:1-15     a range of metadata values" + nl
                + "# The list only matters for blocks whose switch above is off (or that have no switch):" + nl
                + "# a switch that is on already covers its block, and the list adds to it, never removes." + nl
                + "# Each listed block gets a neighbour update and a scheduled update (see schedule_delay)," + nl
                + "# so only list blocks that react to those. Example: custom_blocks=23, 29:8-15" + nl
                + "custom_blocks=" + nl;
        try {
            Files.write(file.toPath(), text.getBytes(UTF8));
        } catch (IOException e) {
            System.out.println("[RedstoneRefresh] could not create " + file + ", using defaults: " + e);
        }
    }

    // ---------------------------------------------------------------- hook

    @Override
    public boolean OnTickInGame(Minecraft mc) {
        fd world = mc.f;
        dc player = mc.h;
        if (world == null || player == null) {
            return true;
        }
        if (world != lastWorld) {
            // new world object: forget everything we processed in the old one
            lastWorld = world;
            processed.clear();
            tickCounter = 0;
        }
        if (world.B) {
            return true; // multiplayer client world: the server owns redstone
        }
        if (++tickCounter % scanInterval != 0) {
            return true;
        }

        cl provider = world.w();
        int pcx = ((int) Math.floor(player.aM)) >> 4;
        int pcz = ((int) Math.floor(player.aO)) >> 4;

        // forget chunks that have unloaded so they get refreshed when they return
        for (Iterator<Long> it = processed.iterator(); it.hasNext();) {
            long key = it.next();
            if (!provider.a(unpackX(key), unpackZ(key))) {
                it.remove();
            }
        }

        for (int cx = pcx - scanRadius; cx <= pcx + scanRadius; cx++) {
            for (int cz = pcz - scanRadius; cz <= pcz + scanRadius; cz++) {
                long key = pack(cx, cz);
                if (processed.contains(key)) {
                    continue;
                }
                if (!provider.a(cx, cz)) {
                    continue;
                }
                // Only refresh once all 8 neighbours are loaded, so the updates
                // do not spill into unloaded chunks and force them to generate.
                if (!neighboursLoaded(provider, cx, cz)) {
                    continue;
                }
                processed.add(key);
                refreshChunk(world, provider.b(cx, cz));
            }
        }
        return true;
    }

    private static boolean neighboursLoaded(cl provider, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !provider.a(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void refreshChunk(fd world, lm chunk) {
        byte[] blocks = chunk.b;
        int baseX = chunk.j << 4;
        int baseZ = chunk.k << 4;

        // Collect first, update after: updating while scanning could change the array.
        int[] hits = new int[64];
        int n = 0;
        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFF;
            if (metaMask[id] != 0) {
                if (n + 1 > hits.length) {
                    hits = Arrays.copyOf(hits, hits.length * 2);
                }
                hits[n++] = i;
            }
        }

        for (int h = 0; h < n; h++) {
            int i = hits[h];
            // b1.7.3 chunk layout: index = x << 11 | z << 7 | y
            int x = baseX + ((i >> 11) & 15);
            int z = baseZ + ((i >> 7) & 15);
            int y = i & 127;

            int id = world.a(x, y, z);
            if (metaMask[id] == 0 || uu.m[id] == null) {
                continue;
            }
            if ((metaMask[id] >> world.e(x, y, z) & 1) == 0) {
                continue; // this metadata value is not selected in the config
            }
            if (id == WIRE) {
                // onBlockAdded recalculates the wire's power from its neighbours
                // and notifies everything around it, as when dust is placed.
                uu.m[WIRE].c(world, x, y, z);
                world.i(x, y, z, WIRE); // notify this block's own neighbours
            } else if (id == TORCH_OFF) {
                // Schedule FIRST: the block schedules its own tick when it is notified below,
                // and the game ignores a second tick for the same block, so scheduling
                // after would silently discard schedule_delay.
                // unlit torch: the tick re-checks its input and relights it if it is
                // no longer powered; neighbours re-check theirs too.
                world.c(x, y, z, TORCH_OFF, scheduleDelay);
                uu.m[TORCH_OFF].c(world, x, y, z);
                world.i(x, y, z, TORCH_OFF);
            } else if (id == TORCH_ON) {
                // onBlockAdded notifies neighbours; the scheduled tick makes the
                // torch re-check its own input (burn out / turn off if needed).
                world.c(x, y, z, TORCH_ON, scheduleDelay);
                uu.m[TORCH_ON].c(world, x, y, z);
                world.i(x, y, z, TORCH_ON); // adjacent dust/repeaters re-check their input
            } else if (id == REPEATER_ON) {
                world.c(x, y, z, REPEATER_ON, scheduleDelay);
                uu.m[REPEATER_ON].c(world, x, y, z);
                world.i(x, y, z, REPEATER_ON);
            } else {
                // Rails, pistons and custom blocks: scheduled tick (detector rails need one),
                // the block's own neighbour check (power state of rails / pistons), and a
                // notification of everything around it.
                world.c(x, y, z, id, scheduleDelay);
                uu.m[id].b(world, x, y, z, id);
                world.i(x, y, z, id);
            }
        }
    }

    private static long pack(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}
