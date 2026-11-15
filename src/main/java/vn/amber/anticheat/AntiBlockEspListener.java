package vn.amber.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiBlockEspListener implements Listener {

    public record BlockPos(String world, int x, int y, int z) {}

    private final AmberAntiCheat plugin;
    private final AsyncOcclusionEngine occlusionEngine;

    private final Map<String, Map<Long, Set<BlockPos>>> trackedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockPos>> revealedBlocksByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockPos>> maskedBlocksByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenBlockTime = new ConcurrentHashMap<>();

    private final BlockData airData = Material.AIR.createBlockData();
    private final BlockData stoneData = Material.STONE.createBlockData();
    private final BlockData deepslateData = Material.DEEPSLATE.createBlockData();
    private final BlockData netherrackData = Material.NETHERRACK.createBlockData();

    private static final double MAX_REVEAL_DISTANCE = 20.0;
    private static final double MAX_REVEAL_DIST_SQ = MAX_REVEAL_DISTANCE * MAX_REVEAL_DISTANCE;
    private static final int MAX_BLOCKS_PER_PLAYER = 64;

    public AntiBlockEspListener(AmberAntiCheat plugin, AsyncOcclusionEngine occlusionEngine) {
        this.plugin = plugin;
        this.occlusionEngine = occlusionEngine;

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }
    }

    public void startTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateBlocksForAll, 20L, 5L);
    }

    private boolean isTargetMaterial(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        if (name.contains("CHEST") || name.endsWith("SHULKER_BOX") || name.endsWith("_BED") || name.endsWith("_SIGN")) {
            return true;
        }
        if (name.equals("BARREL") || name.equals("SPAWNER") || name.equals("TRIAL_SPAWNER")
                || name.equals("VAULT") || name.equals("HOPPER")
                || name.equals("FURNACE") || name.equals("BLAST_FURNACE")
                || name.equals("SMOKER") || name.equals("DISPENSER") || name.equals("DROPPER")
                || name.equals("BREWING_STAND") || name.equals("ENCHANTING_TABLE")
                || name.contains("ANVIL") || name.equals("BEACON") || name.equals("CRAFTER")
                || name.equals("LECTERN") || name.equals("DECORATED_POT")) {
            return true;
        }
        List<String> list = plugin.getConfig().getStringList("anti-block-esp.danh-sach-block");
        return list.contains(name);
    }

    private long getChunkKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    private void scanChunk(Chunk chunk) {
        if (chunk.getWorld().getEnvironment() == World.Environment.THE_END) return;
        String worldName = chunk.getWorld().getName();
        long key = getChunkKey(chunk.getX(), chunk.getZ());
        Set<BlockPos> set = ConcurrentHashMap.newKeySet();

        for (BlockState state : chunk.getTileEntities()) {
            if (isTargetMaterial(state.getType())) {
                set.add(new BlockPos(worldName, state.getX(), state.getY(), state.getZ()));
            }
        }
        if (!set.isEmpty()) {
            trackedBlocks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>()).put(key, set);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        scanChunk(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, Set<BlockPos>> map = trackedBlocks.get(e.getWorld().getName());
        if (map != null) {
            map.remove(getChunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isTargetMaterial(b.getType())) return;

        World w = b.getWorld();
        BlockPos pos = new BlockPos(w.getName(), b.getX(), b.getY(), b.getZ());
        long key = getChunkKey(b.getX() >> 4, b.getZ() >> 4);
        trackedBlocks.computeIfAbsent(w.getName(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(pos);

        revealedBlocksByPlayer.computeIfAbsent(e.getPlayer().getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(pos);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isTargetMaterial(b.getType())) return;
            Location loc = b.getLocation();
            for (Player p : w.getPlayers()) {
                if (p.equals(e.getPlayer()) || p.getGameMode() == GameMode.SPECTATOR) continue;
                Set<BlockPos> rev = revealedBlocksByPlayer.get(p.getUniqueId());
                if (rev == null || !rev.contains(pos)) {
                    boolean diffLayer = plugin.isHiddenByYLayer(p, b.getY(), false);
                    p.sendBlockChange(loc, diffLayer ? airData : getMaskData(w, b.getY()));
                }
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        removeTrackedBlock(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) removeTrackedBlock(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) removeTrackedBlock(b);
    }

    private void removeTrackedBlock(Block b) {
        Map<Long, Set<BlockPos>> map = trackedBlocks.get(b.getWorld().getName());
        if (map != null) {
            Set<BlockPos> set = map.get(getChunkKey(b.getX() >> 4, b.getZ() >> 4));
            if (set != null) {
                BlockPos pos = new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                set.remove(pos);
                for (Set<BlockPos> rev : revealedBlocksByPlayer.values()) rev.remove(pos);
                for (Set<BlockPos> m : maskedBlocksByPlayer.values()) m.remove(pos);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        revealedBlocksByPlayer.remove(uuid);
        maskedBlocksByPlayer.remove(uuid);
    }

    public void clearPlayerCache(UUID uuid) {
        revealedBlocksByPlayer.remove(uuid);
        maskedBlocksByPlayer.remove(uuid);
    }

    private boolean hasLineOfSightToBlock(Player viewer, Block targetBlock) {
        Location eye = viewer.getEyeLocation();
        Vector eyeVec = eye.toVector();
        double bx = targetBlock.getX();
        double by = targetBlock.getY();
        double bz = targetBlock.getZ();

        double[][] points = {
                {bx + 0.5, by + 0.5, bz + 0.5},
                {bx + 0.5, by + 0.85, bz + 0.5},
                {bx + 0.2, by + 0.5, bz + 0.2},
                {bx + 0.8, by + 0.5, bz + 0.8}
        };

        World world = viewer.getWorld();
        for (double[] pt : points) {
            Vector dir = new Vector(pt[0] - eyeVec.getX(), pt[1] - eyeVec.getY(), pt[2] - eyeVec.getZ());
            double dist = dir.length();
            if (dist < 0.3) return true;

            RayTraceResult hit = world.rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null || hit.getHitBlock().equals(targetBlock)) {
                return true;
            }
        }
        return false;
    }

    private void updateBlocksForAll() {
        boolean wallBlockEsp = plugin.getConfig().getBoolean("anti-block-esp.bat", true);
        boolean layerBlockEsp = plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                && plugin.getConfig().getBoolean("hien-tren-an-duoi.an-ruong-block", true);

        if (!wallBlockEsp && !layerBlockEsp) {
            restoreAllHiddenBlocks();
            return;
        }

        long now = System.currentTimeMillis();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            World world = viewer.getWorld();
            if (world.getEnvironment() == World.Environment.THE_END) continue;

            if (viewer.getGameMode() == GameMode.SPECTATOR) {
                revealNearbyForBypass(viewer);
                continue;
            }

            UUID viewerId = viewer.getUniqueId();
            Set<BlockPos> revealedSet = revealedBlocksByPlayer.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
            Set<BlockPos> maskedSet = maskedBlocksByPlayer.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
            Location eye = viewer.getEyeLocation();
            double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
            String worldName = world.getName();

            // 1. RƯƠNG, SPAWNER, SHULKER, PHỄU...: Ẩn cả khi ở xa (> 20m) LẪN khi khuất sau tường
            Map<Long, Set<BlockPos>> worldMap = trackedBlocks.get(worldName);
            if (worldMap != null && !worldMap.isEmpty()) {
                int pChunkX = eye.getBlockX() >> 4;
                int pChunkZ = eye.getBlockZ() >> 4;
                int rayChecks = 0;

                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        Set<BlockPos> blocksInChunk = worldMap.get(getChunkKey(pChunkX + dx, pChunkZ + dz));
                        if (blocksInChunk == null || blocksInChunk.isEmpty()) continue;

                        for (BlockPos pos : blocksInChunk) {
                            Location blockLoc = new Location(world, pos.x(), pos.y(), pos.z());
                            boolean differentLayer = layerBlockEsp && plugin.isHiddenByYLayer(viewer, pos.y(), false);

                            if (differentLayer) {
                                revealedSet.remove(pos);
                                if (maskedSet.add(pos)) {
                                    viewer.sendBlockChange(blockLoc, airData);
                                }
                                continue;
                            }

                            double distSq = Math.pow(eyeX - (pos.x() + 0.5), 2)
                                    + Math.pow(eyeY - (pos.y() + 0.5), 2)
                                    + Math.pow(eyeZ - (pos.z() + 0.5), 2);

                            if (distSq > MAX_REVEAL_DIST_SQ) {
                                revealedSet.remove(pos);
                                if (maskedSet.add(pos)) {
                                    viewer.sendBlockChange(blockLoc, getMaskData(world, pos.y()));
                                }
                                continue;
                            }

                            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                            if (!isTargetMaterial(block.getType())) {
                                blocksInChunk.remove(pos);
                                revealedSet.remove(pos);
                                maskedSet.remove(pos);
                                continue;
                            }

                            boolean canSee = true;
                            if (wallBlockEsp) {
                                if (rayChecks < MAX_BLOCKS_PER_PLAYER) {
                                    rayChecks++;
                                    canSee = hasLineOfSightToBlock(viewer, block);
                                } else {
                                    canSee = false;
                                }
                            }

                            String timeKey = viewerId + ":" + pos.x() + "," + pos.y() + "," + pos.z();

                            if (canSee) {
                                lastSeenBlockTime.put(timeKey, now);
                                maskedSet.remove(pos);
                                if (revealedSet.add(pos)) {
                                    viewer.sendBlockChange(blockLoc, block.getBlockData());
                                }
                            } else {
                                long lastSeen = lastSeenBlockTime.getOrDefault(timeKey, 0L);
                                if (!revealedSet.contains(pos) || (now - lastSeen >= 1000L)) {
                                    revealedSet.remove(pos);
                                    if (maskedSet.add(pos)) {
                                        viewer.sendBlockChange(blockLoc, getMaskData(world, pos.y()));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. CẬP NHẬT THỰC THỂ (Tách riêng Item Drop không ẩn sau tường)
            updateBaseEntitiesForViewer(viewer, wallBlockEsp, layerBlockEsp);
        }
    }

    /**
     * - Nếu là ITEM DROP (ent instanceof Item): KHÔNG ẩn sau block, chỉ ẩn khi đi ra xa (> 20m) hoặc khác tầng Y=35. Lại gần là hiện!
     * - Các thực thể khác (ArmorStand, ItemFrame, Xe mỏ, Quái...): Vẫn ẩn cả sau tường lẫn khi ở xa.
     */
    private void updateBaseEntitiesForViewer(Player viewer, boolean wallEsp, boolean layerEsp) {
        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        int pChunkX = eye.getBlockX() >> 4;
        int pChunkZ = eye.getBlockZ() >> 4;

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (!world.isChunkLoaded(pChunkX + dx, pChunkZ + dz)) continue;
                Chunk chunk = world.getChunkAt(pChunkX + dx, pChunkZ + dz);

                for (Entity ent : chunk.getEntities()) {
                    if (ent instanceof Player) continue;

                    Location entLoc = ent.getLocation();
                    boolean shouldHide = false;

                    if (layerEsp && plugin.isHiddenByYLayer(viewer, entLoc.getY(), false)) {
                        shouldHide = true;
                    } else if (wallEsp) {
                        double distSq = eye.distanceSquared(entLoc);
                        if (distSq > MAX_REVEAL_DIST_SQ) {
                            // Ở xa (> 20m): Ẩn tất cả (bao gồm cả Item Drop)
                            shouldHide = true;
                        } else if (!(ent instanceof Item) && distSq > 9.0 && !viewer.hasLineOfSight(ent)) {
                            // Ở gần (<= 20m) nhưng khuất sau tường: Chỉ ẩn Thực thể khác, KHÔNG ẩn Item Drop!
                            shouldHide = true;
                        }
                    }

                    if (shouldHide) {
                        viewer.hideEntity(plugin, ent);
                    } else {
                        viewer.showEntity(plugin, ent);
                    }
                }
            }
        }
    }

    private void revealNearbyForBypass(Player viewer) {
        World world = viewer.getWorld();
        int cx = viewer.getLocation().getBlockX() >> 4;
        int cz = viewer.getLocation().getBlockZ() >> 4;

        Map<Long, Set<BlockPos>> worldMap = trackedBlocks.get(world.getName());
        Set<BlockPos> rev = revealedBlocksByPlayer.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        maskedBlocksByPlayer.remove(viewer.getUniqueId());

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (worldMap != null) {
                    Set<BlockPos> set = worldMap.get(getChunkKey(cx + dx, cz + dz));
                    if (set != null) {
                        for (BlockPos pos : set) {
                            if (rev.add(pos)) {
                                Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
                                viewer.sendBlockChange(b.getLocation(), b.getBlockData());
                            }
                        }
                    }
                }
                if (world.isChunkLoaded(cx + dx, cz + dz)) {
                    Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                    for (Entity ent : chunk.getEntities()) {
                        if (!(ent instanceof Player)) {
                            viewer.showEntity(plugin, ent);
                        }
                    }
                }
            }
        }
    }

    private BlockData getMaskData(World world, int y) {
        if (world.getEnvironment() == World.Environment.NETHER) return netherrackData;
        return y < 0 ? deepslateData : stoneData;
    }

    public void restoreAllHiddenBlocks() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            revealNearbyForBypass(p);
        }
        revealedBlocksByPlayer.clear();
        maskedBlocksByPlayer.clear();
        lastSeenBlockTime.clear();
    }
}
