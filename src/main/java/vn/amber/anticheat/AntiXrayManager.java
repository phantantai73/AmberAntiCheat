package vn.amber.anticheat;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AntiXrayManager implements Listener {

    private final AmberAntiCheat plugin;

    private static final int MAX_STATE_ID = 65536;
    private static final byte CAT_UNKNOWN = -1;
    private static final byte CAT_NORMAL_SOLID = 0;
    private static final byte CAT_AIR_OR_WATER = 1;
    private static final byte CAT_STORAGE_HIDE = 2;
    private static final byte CAT_OW_ORE = 4;
    private static final byte CAT_NETHER_ORE = 8;
    private static final byte CAT_ALWAYS_HIDE_DEBRIS = 16;

    private volatile byte[] stateCategoryCache = new byte[MAX_STATE_ID];
    private volatile boolean[] oreMaterialTable = new boolean[Material.values().length];

    private Set<String> owOreNames = new HashSet<>();
    private Set<String> netherOreNames = new HashSet<>();
    private Set<String> storageBlockNames = new HashSet<>();

    private volatile int airId = 0;
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
        byte[] newCache = new byte[MAX_STATE_ID];
        Arrays.fill(newCache, CAT_UNKNOWN);
        boolean[] newOreMat = new boolean[Material.values().length];

        airId = getGlobalId(Material.AIR);
        stoneId = getGlobalId(Material.STONE);
        deepslateId = getGlobalId(Material.DEEPSLATE);
        netherrackId = getGlobalId(Material.NETHERRACK);

        Set<String> newOw = new HashSet<>(Arrays.asList(
                "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
                "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
                "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
                "IRON_ORE", "DEEPSLATE_IRON_ORE",
                "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
                "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
                "COAL_ORE", "DEEPSLATE_COAL_ORE",
                "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
                "RAW_IRON_BLOCK", "RAW_GOLD_BLOCK", "RAW_COPPER_BLOCK"
        ));
        for (String s : plugin.getConfig().getStringList("anti-xray.quang-overworld")) {
            newOw.add(s.toUpperCase(Locale.ROOT));
        }

        Set<String> newNether = new HashSet<>(Arrays.asList(
                "ANCIENT_DEBRIS", "NETHER_GOLD_ORE", "NETHER_QUARTZ_ORE", "GILDED_BLACKSTONE"
        ));
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
        if (state == null) return CAT_AIR_OR_WATER;
        int id = state.getGlobalId();
        if (id <= 0 || id >= MAX_STATE_ID) return CAT_AIR_OR_WATER;

        byte cached = stateCategoryCache[id];
        if (cached != CAT_UNKNOWN) return cached;

        String rawName = state.getType().getName().toUpperCase(Locale.ROOT);
        int colonIdx = rawName.indexOf(':');
        String typeName = (colonIdx != -1) ? rawName.substring(colonIdx + 1) : rawName;

        byte result = CAT_NORMAL_SOLID;

        // Chỉ tính Không khí, Nước, Kính, Lá cây là trong suốt (Lava và Soul Sand bị coi là đặc để ẩn quặng dưới Lava/Soul Sand)
        if (typeName.equals("AIR")
                || typeName.equals("CAVE_AIR")
                || typeName.equals("VOID_AIR")
                || typeName.equals("WATER")
                || typeName.contains("GLASS")
                || typeName.contains("LEAVES")
                || typeName.contains("FENCE")
                || typeName.contains("TORCH")
                || typeName.contains("LANTERN")
                || typeName.contains("RAIL")) {
            result |= CAT_AIR_OR_WATER;
        }

        if (typeName.equals("ANCIENT_DEBRIS")) {
            // Ancient Debris luôn bị ẩn 100% trong gói Chunk ở mọi tọa độ (Kể cả sát viền chunk hay cạnh Lava)
            result |= CAT_ALWAYS_HIDE_DEBRIS;
        } else if (storageBlockNames.contains(typeName)
                || typeName.contains("CHEST")
                || typeName.endsWith("SHULKER_BOX")
                || typeName.endsWith("_BED")
                || typeName.endsWith("_SIGN")
                || typeName.endsWith("_HEAD")
                || typeName.endsWith("_SKULL")
                || typeName.endsWith("_BANNER")) {
            result |= CAT_STORAGE_HIDE;
        } else if (netherOreNames.contains(typeName)) {
            result |= CAT_NETHER_ORE;
        } else if (owOreNames.contains(typeName)) {
            result |= CAT_OW_ORE;
        }

        stateCategoryCache[id] = result;
        return result;
    }

    public boolean obfuscateChunkPacket(Player player, WrapperPlayServerChunkData wrapper) {
        if (player != null && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        Column column = wrapper.getColumn();
        if (column == null) return false;
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return false;

        // Nhận diện chính xác môi trường dựa trên cả số lượng Section của gói Chunk (Overworld = 24 sections, Nether = 16 sections)
        boolean isNetherChunk = (sections.length < 24);
        int minY = isNetherChunk ? 0 : -64;

        if (player != null) {
            World world = player.getWorld();
            World.Environment env = world.getEnvironment();
            if (env == World.Environment.THE_END) return false;
            if (env == World.Environment.NETHER) {
                isNetherChunk = true;
                minY = world.getMinHeight();
            } else if (env == World.Environment.NORMAL && sections.length >= 24) {
                isNetherChunk = false;
                minY = world.getMinHeight();
            }
        }

        boolean xrayEnabled = plugin.getConfig().getBoolean("anti-xray.bat", true);
        boolean hideStorageOnChunk = plugin.getConfig().getBoolean("anti-block-esp.bat", true);
        boolean layerEnabled = plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true);
        boolean pieChartProtect = layerEnabled && plugin.getConfig().getBoolean("hien-tren-an-duoi.chong-pie-chart-f3", true);

        if (!xrayEnabled && !hideStorageOnChunk && !layerEnabled) return false;

        boolean modified = false;

        // 1. Vô hiệu hóa trực tiếp mảng TileEntity[] (Giữ nguyên Column gốc để tương thích 100% với 1.21.11)
        if (hideStorageOnChunk || pieChartProtect) {
            TileEntity[] tiles = column.getTileEntities();
            if (tiles != null && tiles.length > 0) {
                TileEntity stripped = new TileEntity((byte) 0, (short) -2048, 0, new NBTCompound());
                for (int i = 0; i < tiles.length; i++) {
                    tiles[i] = stripped;
                }
                modified = true;
            }
        }

        // Trong Nether: Quét toàn bộ độ cao đến Y = 256 (Không giới hạn ở Y = 64 như Overworld)
        int maxScanY = isNetherChunk ? 256 : plugin.getConfig().getInt("anti-xray.do-cao-quet-toi-da", 64);
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

                // 2. Phân tầng Y=35 trong Overworld (Biến tầng khác thành Không khí trống rỗng)
                if (layerEnabled && !isNetherChunk) {
                    if ((viewerZone == 1 && worldY <= splitY - 5) || (viewerZone == -1 && worldY >= splitY + 5)) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                section.set(x, y, z, airId);
                            }
                        }
                        modified = true;
                        continue;
                    }
                }

                boolean scanOreHere = xrayEnabled && (isNetherChunk || worldY <= maxScanY);
                if (!scanOreHere && !hideStorageOnChunk && !pieChartProtect) continue;

                int defaultMaskId = isNetherChunk ? netherrackId : (worldY < 0 ? deepslateId : stoneId);

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        WrappedBlockState state = section.get(x, y, z);
                        byte cat = getCategory(state);
                        if (cat == CAT_NORMAL_SOLID || cat == CAT_AIR_OR_WATER) continue;

                        // 3. ANCIENT DEBRIS: Ẩn 100% thành Netherrack vô điều kiện ở mọi độ cao & kể cả sát viền chunk
                        if (xrayEnabled && (cat & CAT_ALWAYS_HIDE_DEBRIS) != 0) {
                            section.set(x, y, z, netherrackId);
                            modified = true;
                            continue;
                        }

                        // 4. Rương, EnderChest, Shulker, Spawner, Phễu, Lò nung -> Biến thành Đá / Netherrack
                        if ((hideStorageOnChunk || pieChartProtect) && (cat & CAT_STORAGE_HIDE) != 0) {
                            section.set(x, y, z, defaultMaskId);
                            modified = true;
                            continue;
                        }

                        // 5. Quặng Nether & Quặng Overworld: Ẩn nếu không tiếp xúc trực tiếp với Không khí / Nước
                        if (scanOreHere && (cat & (CAT_NETHER_ORE | CAT_OW_ORE)) != 0) {
                            if (!maskBuilt) {
                                buildTransparentMask(section, transparentMask);
                                maskBuilt = true;
                            }
                            if (!isExposedFast(sections, secIdx, transparentMask, x, y, z)) {
                                int oreMask = ((cat & CAT_NETHER_ORE) != 0) ? netherrackId : defaultMaskId;
                                section.set(x, y, z, oreMask);
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
                    if ((cat & CAT_AIR_OR_WATER) != 0) {
                        int idx = zOff | x;
                        mask[idx >> 6] |= (1L << (idx & 63));
                    }
                }
            }
        }
    }

    /**
     * Đã xóa bỏ lỗi "x == 0 || x == 15 || z == 0 || z == 15 return true"
     * Quặng nằm ở sát vách ranh giới chunk vẫn bị ẩn kín 100% nếu không lộ ra không khí trong chunk
     */
    private boolean isExposedFast(BaseChunk[] sections, int secIdx, long[] mask, int x, int y, int z) {
        if (x > 0 && isMaskBitSet(mask, x - 1, y, z)) return true;
        if (x < 15 && isMaskBitSet(mask, x + 1, y, z)) return true;
        if (z > 0 && isMaskBitSet(mask, x, y, z - 1)) return true;
        if (z < 15 && isMaskBitSet(mask, x, y, z + 1)) return true;

        if (y > 0) {
            if (isMaskBitSet(mask, x, y - 1, z)) return true;
        } else if (secIdx > 0) {
            BaseChunk below = sections[secIdx - 1];
            if (below != null && !below.isEmpty()) {
                if ((getCategory(below.get(x, 15, z)) & CAT_AIR_OR_WATER) != 0) return true;
            }
        }

        if (y < 15) {
            if (isMaskBitSet(mask, x, y + 1, z)) return true;
        } else if (secIdx + 1 < sections.length) {
            BaseChunk above = sections[secIdx + 1];
            if (above != null && !above.isEmpty()) {
                if ((getCategory(above.get(x, 0, z)) & CAT_AIR_OR_WATER) != 0) return true;
            }
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
        boolean isNether = (world.getEnvironment() == World.Environment.NETHER);
        int maxScanY = isNether ? 256 : plugin.getConfig().getInt("anti-xray.do-cao-quet-toi-da", 64);
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
                if (!isNether && plugin.isHiddenByYLayer(p, adjLoc.getY(), false)) continue;
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
