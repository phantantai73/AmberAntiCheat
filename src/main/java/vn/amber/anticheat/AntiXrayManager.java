package vn.amber.anticheat;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AntiXrayManager implements Listener {

    private final AmberAntiCheat plugin;

    private static final int MAX_STATE_ID = 65536;
    private static final byte CAT_UNKNOWN = -1;
    private static final byte CAT_NORMAL_SOLID = 0;
    private static final byte CAT_TRANSPARENT = 1;
    private static final byte CAT_STORAGE_HIDE = 2;
    private static final byte CAT_OW_ORE = 4;
    private static final byte CAT_NETHER_ORE = 8;

    private volatile byte[] stateCategoryCache = new byte[MAX_STATE_ID];
    private volatile boolean[] oreMaterialTable = new boolean[Material.values().length];

    private Set<String> owOreNames = new HashSet<>();
    private Set<String> netherOreNames = new HashSet<>();
    private Set<String> storageBlockNames = new HashSet<>();

    private volatile int airId = 0;
    private volatile int stoneId;
    private volatile int deepslateId;
    private volatile int netherrackId;

    private static final TileEntity[] EMPTY_TILE_ENTITIES = new TileEntity[0];

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
        byte[] newCache = new byte[MAX_STATE_ID];
        Arrays.fill(newCache, CAT_UNKNOWN);
        boolean[] newOreMat = new boolean[Material.values().length];

        airId = getGlobalId(Material.AIR);
        stoneId = getGlobalId(Material.STONE);
        deepslateId = getGlobalId(Material.DEEPSLATE);
        netherrackId = getGlobalId(Material.NETHERRACK);

        Set<String> newOw = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("anti-xray.quang-overworld")) {
            newOw.add(s.toUpperCase(Locale.ROOT));
        }

        Set<String> newNether = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("anti-xray.quang-nether")) {
            newNether.add(s.toUpperCase(Locale.ROOT));
        }

        Set<String> newStorage = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("anti-block-esp.danh-sach-block")) {
            newStorage.add(s.toUpperCase(Locale.ROOT));
        }
        newStorage.addAll(Arrays.asList(
                "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "BARREL", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
                "HOPPER", "FURNACE", "BLAST_FURNACE", "SMOKER", "DISPENSER", "DROPPER",
                "PISTON", "STICKY_PISTON", "MOVING_PISTON", "BEACON", "BREWING_STAND",
                "ENCHANTING_TABLE", "BELL", "CRAFTER", "DECORATED_POT", "LECTERN",
                "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "JUKEBOX", "CHISELED_BOOKSHELF"
        ));

        for (Material mat : Material.values()) {
            String name = mat.name();
            if (newOw.contains(name) || newNether.contains(name)) {
                newOreMat[mat.ordinal()] = true;
            }
        }

        this.owOreNames = newOw;
        this.netherOreNames = newNether;
        this.storageBlockNames = newStorage;
        this.oreMaterialTable = newOreMat;
        this.stateCategoryCache = newCache;
    }

    private int getGlobalId(Material mat) {
        try {
            return SpigotConversionUtil.fromBukkitBlockData(mat.createBlockData()).getGlobalId();
        } catch (Exception e) {
            return 0;
        }
    }

    private byte getCategory(WrappedBlockState state) {
        if (state == null) return CAT_TRANSPARENT;
        int id = state.getGlobalId();
        if (id <= 0 || id >= MAX_STATE_ID) return CAT_TRANSPARENT;

        byte cached = stateCategoryCache[id];
        if (cached != CAT_UNKNOWN) return cached;

        String rawName = state.getType().getName().toUpperCase(Locale.ROOT);
        int colonIdx = rawName.indexOf(':');
        String typeName = (colonIdx != -1) ? rawName.substring(colonIdx + 1) : rawName;

        Material mat = Material.getMaterial(typeName);
        byte result = CAT_NORMAL_SOLID;

        if (mat == null || mat.isAir() || !plugin.isFullSolidBlock(mat)) {
            result |= CAT_TRANSPARENT;
        }

        if (storageBlockNames.contains(typeName)
                || typeName.contains("CHEST")
                || typeName.endsWith("SHULKER_BOX")
                || typeName.endsWith("_BED")
                || typeName.endsWith("_SIGN")
                || typeName.endsWith("_HEAD")
                || typeName.endsWith("_SKULL")
                || typeName.endsWith("_BANNER")) {
            result |= CAT_STORAGE_HIDE;
        } else if (owOreNames.contains(typeName)) {
            result |= CAT_OW_ORE;
        } else if (netherOreNames.contains(typeName)) {
            result |= CAT_NETHER_ORE;
        }

        stateCategoryCache[id] = result;
        return result;
    }

    /**
     * Áp dụng cho TẤT CẢ người chơi (Bao gồm cả OP và Creative)
     */
    public boolean obfuscateChunkPacket(Player player, WrapperPlayServerChunkData wrapper) {
        boolean isNether = false;
        int minY = -64;
        if (player != null) {
            World world = player.getWorld();
            World.Environment env = world.getEnvironment();
            if (env == World.Environment.THE_END) return false;
            isNether = (env == World.Environment.NETHER);
            minY = world.getMinHeight();
        }

        boolean xrayEnabled = plugin.getConfig().getBoolean("anti-xray.bat", true);
        boolean hideStorageOnChunk = plugin.getConfig().getBoolean("anti-block-esp.bat", true);
        boolean layerEnabled = plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true);
        boolean pieChartProtect = layerEnabled && plugin.getConfig().getBoolean("hien-tren-an-duoi.chong-pie-chart-f3", true);

        if (!xrayEnabled && !hideStorageOnChunk && !layerEnabled) return false;

        Column column = wrapper.getColumn();
        if (column == null) return false;
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return false;

        boolean modified = false;

        if (hideStorageOnChunk || pieChartProtect) {
            TileEntity[] tiles = column.getTileEntities();
            if (tiles != null && tiles.length > 0) {
                TileEntity dummy = new TileEntity(0, -2048, 0, 0, new NBTCompound());
                Arrays.fill(tiles, dummy);
                try {
                    Column cleanColumn = new Column(
                            column.getX(),
                            column.getZ(),
                            column.isFullChunk(),
                            sections,
                            EMPTY_TILE_ENTITIES,
                            column.getHeightMaps(),
                            column.getBiomeDataBytes()
                    );
                    wrapper.setColumn(cleanColumn);
                } catch (Throwable ignored) {
                }
                modified = true;
            }
        }

        byte targetOreBit = isNether ? CAT_NETHER_ORE : CAT_OW_ORE;
        int maxScanY = plugin.getConfig().getInt("anti-xray.do-cao-quet-toi-da", 64);
        int splitY = plugin.getConfig().getInt("hien-tren-an-duoi.moc-do-cao-y", 35);
        int viewerZone = (player != null) ? plugin.getPlayerZone(player) : 1;

        long[] transparentMask = new long[64];

        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            int sectionBaseY = minY + (secIdx << 4);
            BaseChunk section = sections[secIdx];
            if (section == null || section.isEmpty()) continue;

            boolean maskBuilt = false;

            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;

                if (layerEnabled && !isNether) {
                    if ((viewerZone == 1 && worldY <= splitY - 1) || (viewerZone == -1 && worldY >= splitY + 1)) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                section.set(x, y, z, airId);
                            }
                        }
                        modified = true;
                        continue;
                    }
                }

                boolean scanOreHere = xrayEnabled && worldY <= maxScanY;
                if (!scanOreHere && !hideStorageOnChunk && !pieChartProtect) continue;

                int defaultMaskId = isNether ? netherrackId : (worldY < 0 ? deepslateId : stoneId);

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        WrappedBlockState state = section.get(x, y, z);
                        byte cat = getCategory(state);
                        if (cat == CAT_NORMAL_SOLID || cat == CAT_TRANSPARENT) continue;

                        if ((hideStorageOnChunk || pieChartProtect) && (cat & CAT_STORAGE_HIDE) != 0) {
                            section.set(x, y, z, defaultMaskId);
                            modified = true;
                            continue;
                        }

                        if (scanOreHere && (cat & targetOreBit) != 0) {
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
        for (int y = 0; y < 16; y++) {
            int yOff = y << 8;
            for (int z = 0; z < 16; z++) {
                int zOff = yOff | (z << 4);
                for (int x = 0; x < 16; x++) {
                    byte cat = getCategory(section.get(x, y, z));
                    if ((cat & CAT_TRANSPARENT) != 0) {
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
            if ((getCategory(below.get(x, 15, z)) & CAT_TRANSPARENT) != 0) return true;
        }

        if (y < 15) {
            if (isMaskBitSet(mask, x, y + 1, z)) return true;
        } else if (secIdx + 1 < sections.length) {
            BaseChunk above = sections[secIdx + 1];
            if (above == null || above.isEmpty()) return true;
            if ((getCategory(above.get(x, 0, z)) & CAT_TRANSPARENT) != 0) return true;
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
