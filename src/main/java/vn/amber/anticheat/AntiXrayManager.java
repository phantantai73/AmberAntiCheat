package vn.amber.anticheat;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Arrays;
import java.util.List;

public class AntiXrayManager implements Listener {

    private final AmberAntiCheat plugin;

    private static final int MAX_STATE_ID = 65536;
    private volatile boolean[] transparentTable = new boolean[MAX_STATE_ID];
    private volatile boolean[] overworldOreTable = new boolean[MAX_STATE_ID];
    private volatile boolean[] netherOreTable = new boolean[MAX_STATE_ID];
    private volatile boolean[] storageBlockTable = new boolean[MAX_STATE_ID];
    private volatile boolean[] oreMaterialTable = new boolean[Material.values().length];

    private volatile int stoneId;
    private volatile int deepslateId;
    private volatile int netherrackId;

    private static final int[][] ADJACENT_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    public AntiXrayManager(AmberAntiCheat plugin) {
        this.plugin = plugin;
        reloadStateMappings();
    }

    public void reloadStateMappings() {
        boolean[] newTransparent = new boolean[MAX_STATE_ID];
        boolean[] newOwOres = new boolean[MAX_STATE_ID];
        boolean[] newNetherOres = new boolean[MAX_STATE_ID];
        boolean[] newStorage = new boolean[MAX_STATE_ID];
        boolean[] newOreMat = new boolean[Material.values().length];

        stoneId = getGlobalId(Material.STONE);
        deepslateId = getGlobalId(Material.DEEPSLATE);
        netherrackId = getGlobalId(Material.NETHERRACK);

        List<String> owList = plugin.getConfig().getStringList("anti-xray.quang-overworld");
        List<String> netherList = plugin.getConfig().getStringList("anti-xray.quang-nether");
        List<String> blockEspList = plugin.getConfig().getStringList("anti-block-esp.danh-sach-block");

        List<String> pieChartExtras = Arrays.asList(
                "HOPPER", "FURNACE", "BLAST_FURNACE", "SMOKER", "DISPENSER", "DROPPER",
                "PISTON", "STICKY_PISTON", "MOVING_PISTON", "BEACON", "BREWING_STAND",
                "ENCHANTING_TABLE", "BELL", "CRAFTER", "DECORATED_POT", "LECTERN",
                "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL"
        );

        for (Material mat : Material.values()) {
            if (!mat.isBlock()) continue;
            int id = getGlobalId(mat);
            if (id < 0 || id >= MAX_STATE_ID) continue;

            if (mat.isAir() || !plugin.isFullSolidBlock(mat)) {
                newTransparent[id] = true;
            }

            String name = mat.name();
            boolean isOw = owList.contains(name);
            boolean isNether = netherList.contains(name);
            boolean isStorageOrTile = name.endsWith("SHULKER_BOX")
                    || blockEspList.contains(name)
                    || pieChartExtras.contains(name)
                    || name.endsWith("_BED")
                    || name.endsWith("_SIGN");

            if (isOw) newOwOres[id] = true;
            if (isNether) newNetherOres[id] = true;
            if (isStorageOrTile) newStorage[id] = true;
            if (isOw || isNether) {
                newOreMat[mat.ordinal()] = true;
            }
        }

        this.transparentTable = newTransparent;
        this.overworldOreTable = newOwOres;
        this.netherOreTable = newNetherOres;
        this.storageBlockTable = newStorage;
        this.oreMaterialTable = newOreMat;
    }

    private int getGlobalId(Material mat) {
        try {
            return SpigotConversionUtil.fromBukkitBlockData(mat.createBlockData()).getGlobalId();
        } catch (Exception e) {
            return 0;
        }
    }

    private int getBlockStateId(BaseChunk section, int x, int y, int z) {
        WrappedBlockState state = section.get(x, y, z);
        return state != null ? state.getGlobalId() : 0;
    }

    public boolean obfuscateChunkPacket(Player player, WrapperPlayServerChunkData wrapper) {
        if (player == null) return false;
        GameMode gm = player.getGameMode();
        if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE || player.hasPermission("amberac.bypass")) {
            return false;
        }

        World world = player.getWorld();
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.THE_END) return false;

        boolean xrayEnabled = plugin.getConfig().getBoolean("anti-xray.bat", true);
        boolean hideStorageOnChunk = plugin.getConfig().getBoolean("anti-block-esp.bat", true);
        boolean layerEnabled = plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true);
        boolean pieChartProtect = layerEnabled && plugin.getConfig().getBoolean("hien-tren-an-duoi.chong-pie-chart-f3", true);

        if (!xrayEnabled && !hideStorageOnChunk && !layerEnabled) return false;

        int maxScanY = plugin.getConfig().getInt("anti-xray.do-cao-quet-toi-da", 64);

        Column column = wrapper.getColumn();
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return false;

        int minY = world.getMinHeight();
        boolean isNether = (env == World.Environment.NETHER);
        boolean[] oreTable = isNether ? netherOreTable : overworldOreTable;
        boolean modified = false;

        long[] transparentMask = new long[64];

        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            int sectionBaseY = minY + (secIdx << 4);
            if (sectionBaseY > maxScanY && !hideStorageOnChunk && !pieChartProtect) break;

            BaseChunk section = sections[secIdx];
            if (section == null || section.isEmpty()) continue;

            boolean maskBuilt = false;

            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;
                boolean scanOreHere = xrayEnabled && worldY <= maxScanY;

                if (!scanOreHere && !hideStorageOnChunk && !pieChartProtect) continue;

                int defaultMaskId = isNether ? netherrackId : (worldY < 0 ? deepslateId : stoneId);

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int currentId = getBlockStateId(section, x, y, z);
                        if (currentId <= 0 || currentId >= MAX_STATE_ID) continue;

                        if ((hideStorageOnChunk || pieChartProtect) && storageBlockTable[currentId]) {
                            section.set(x, y, z, defaultMaskId);
                            modified = true;
                            continue;
                        }

                        if (scanOreHere && oreTable[currentId]) {
                            if (!maskBuilt) {
                                buildTransparentMask(section, transparentMask);
                                maskBuilt = true;
                            }
                            if (!isExposedFast(sections, secIdx, transparentMask, x, y, z)) {
                                section.set(x, y, z, defaultMaskId);
                                modified = true;
                            }
                        }
                    }
                }
            }
        }

        return modified;
    }

    private void buildTransparentMask(BaseChunk section, long[] mask) {
        Arrays.fill(mask, 0L);
        boolean[] trans = this.transparentTable;
        for (int y = 0; y < 16; y++) {
            int yOff = y << 8;
            for (int z = 0; z < 16; z++) {
                int zOff = yOff | (z << 4);
                for (int x = 0; x < 16; x++) {
                    int id = getBlockStateId(section, x, y, z);
                    if (id >= 0 && id < MAX_STATE_ID && trans[id]) {
                        int idx = zOff | x;
                        mask[idx >> 6] |= (1L << (idx & 63));
                    }
                }
            }
        }
    }

    private boolean isExposedFast(BaseChunk[] sections, int secIdx, long[] mask, int x, int y, int z) {
        if (x == 0 || x == 15 || z == 0 || z == 15) return true;

        if (isMaskBitSet(mask, x - 1, y, z)) return true;
        if (isMaskBitSet(mask, x + 1, y, z)) return true;
        if (isMaskBitSet(mask, x, y, z - 1)) return true;
        if (isMaskBitSet(mask, x, y, z + 1)) return true;

        if (y > 0) {
            if (isMaskBitSet(mask, x, y - 1, z)) return true;
        } else if (secIdx > 0) {
            BaseChunk below = sections[secIdx - 1];
            if (below == null || below.isEmpty()) return true;
            int id = getBlockStateId(below, x, 15, z);
            if (id >= 0 && id < MAX_STATE_ID && transparentTable[id]) return true;
        }

        if (y < 15) {
            if (isMaskBitSet(mask, x, y + 1, z)) return true;
        } else if (secIdx + 1 < sections.length) {
            BaseChunk above = sections[secIdx + 1];
            if (above == null || above.isEmpty()) return true;
            int id = getBlockStateId(above, x, 0, z);
            if (id >= 0 && id < MAX_STATE_ID && transparentTable[id]) return true;
        }

        return false;
    }

    private boolean isMaskBitSet(long[] mask, int x, int y, int z) {
        int idx = (y << 8) | (z << 4) | x;
        return (mask[idx >> 6] & (1L << (idx & 63))) != 0L;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        revealAdjacentBlocks(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) revealAdjacentBlocks(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) revealAdjacentBlocks(b);
    }

    public void revealAdjacentBlocks(Block center) {
        if (!plugin.getConfig().getBoolean("anti-xray.bat", true)) return;

        World world = center.getWorld();
        if (world.getEnvironment() == World.Environment.THE_END) return;

        int by = center.getY();
        int maxScanY = plugin.getConfig().getInt("anti-xray.do-cao-quet-toi-da", 64);
        if (by > maxScanY + 1) return;

        int bx = center.getX();
        int bz = center.getZ();
        double centerX = bx + 0.5;
        double centerY = by + 0.5;
        double centerZ = bz + 0.5;
        boolean[] ores = this.oreMaterialTable;

        for (int[] offset : ADJACENT_OFFSETS) {
            Block adj = world.getBlockAt(bx + offset[0], by + offset[1], bz + offset[2]);
            Material type = adj.getType();
            if (!ores[type.ordinal()]) continue;

            Location adjLoc = adj.getLocation();
            BlockData realData = adj.getBlockData();
            for (Player p : world.getPlayers()) {
                if (plugin.isHiddenByYLayer(p, adjLoc.getY(), false)) continue;
                Location pLoc = p.getLocation();
                double dx = pLoc.getX() - centerX;
                double dy = pLoc.getY() - centerY;
                double dz = pLoc.getZ() - centerZ;
                if ((dx * dx + dy * dy + dz * dz) <= 576.0) {
                    p.sendBlockChange(adjLoc, realData);
                }
            }
        }
    }
}
