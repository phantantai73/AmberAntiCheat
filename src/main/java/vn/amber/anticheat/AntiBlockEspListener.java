package vn.amber.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
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
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiBlockEspListener implements Listener {

    public record BlockPos(String world, int x, int y, int z) {}

    private final AmberAntiCheat plugin;
    private final AsyncOcclusionEngine occlusionEngine;

    private final Map<String, Map<Long, Set<BlockPos>>> trackedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockPos>> revealedBlocksByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenBlockTime = new ConcurrentHashMap<>();

    private final BlockData stoneData = Material.STONE.createBlockData();
    private final BlockData deepslateData = Material.DEEPSLATE.createBlockData();
    private final BlockData netherrackData = Material.NETHERRACK.createBlockData();

    private static final double MAX_REVEAL_DISTANCE = 24.0;
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateBlocksForAll, 20L, 8L);
    }

    private boolean isTargetMaterial(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        if (name.endsWith("SHULKER_BOX") || name.endsWith("_BED") || name.endsWith("_SIGN")) return true;
        if (name.equals("HOPPER") || name.equals("FURNACE") || name.equals("BLAST_FURNACE")
                || name.equals("SMOKER") || name.equals("DISPENSER") || name.equals("DROPPER")
                || name.equals("BREWING_STAND") || name.equals("ENCHANTING_TABLE")
                || name.equals("ANVIL") || name.equals("CHIPPED_ANVIL") || name.equals("DAMAGED_ANVIL")
                || name.equals("BEACON") || name.equals("CRAFTER") || name.equals("LECTERN")) {
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
            BlockData mask = getMaskData(w, b.getY());
            for (Player p : w.getPlayers()) {
                if (p.equals(e.getPlayer()) || p.hasPermission("amberac.bypass")) continue;
                Set<BlockPos> rev = revealedBlocksByPlayer.get(p.getUniqueId());
                if (rev == null || !rev.contains(pos)) {
                    p.sendBlockChange(loc, mask);
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
        for (Block b : e.blockList()) {
            removeTrackedBlock(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            removeTrackedBlock(b);
        }
    }

    private void removeTrackedBlock(Block b) {
        Map<Long, Set<BlockPos>> map = trackedBlocks.get(b.getWorld().getName());
        if (map != null) {
            Set<BlockPos> set = map.get(getChunkKey(b.getX() >> 4, b.getZ() >> 4));
            if (set != null) {
                BlockPos pos = new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                set.remove(pos);
                for (Set<BlockPos> rev : revealedBlocksByPlayer.values()) {
                    rev.remove(pos);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        revealedBlocksByPlayer.remove(e.getPlayer().getUniqueId());
    }

    public void clearPlayerCache(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        Set<BlockPos> rev = revealedBlocksByPlayer.remove(uuid);
        if (p != null && rev != null) {
            for (BlockPos pos : rev) {
                World w = Bukkit.getWorld(pos.world());
                if (w != null && w.equals(p.getWorld())) {
                    p.sendBlockChange(new Location(w, pos.x(), pos.y(), pos.z()), getMaskData(w, pos.y()));
                }
            }
        }
    }

    private void updateBlocksForAll() {
        boolean wallBlockEsp = plugin.getConfig().getBoolean("anti-block-esp.bat", true);
        boolean layerBlockEsp = plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                && plugin.getConfig().getBoolean("hien-tren-an-duoi.an-ruong-block", true);

        if (!wallBlockEsp && !layerBlockEsp) {
            restoreAllHiddenBlocks();
            return;
        }

        double minAlwaysShowDist = plugin.getConfig().getDouble("anti-block-esp.khoang-cach-luon-hien", 2.5);
        double minShowDistSq = minAlwaysShowDist * minAlwaysShowDist;
        long now = System.currentTimeMillis();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            World world = viewer.getWorld();
            if (world.getEnvironment() == World.Environment.THE_END) continue;

            if (viewer.hasPermission("amberac.bypass")
                    || viewer.getGameMode() == GameMode.SPECTATOR
                    || viewer.getGameMode() == GameMode.CREATIVE) {
                revealNearbyForBypass(viewer);
                continue;
            }

            Set<BlockPos> revealedSet = revealedBlocksByPlayer.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            Location eye = viewer.getEyeLocation();
            double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
            String worldName = world.getName();

            Iterator<BlockPos> it = revealedSet.iterator();
            while (it.hasNext()) {
                BlockPos pos = it.next();
                if (!pos.world().equals(worldName)) {
                    it.remove();
                    continue;
                }
                double distSq = Math.pow(eyeX - (pos.x() + 0.5), 2)
                        + Math.pow(eyeY - (pos.y() + 0.5), 2)
                        + Math.pow(eyeZ - (pos.z() + 0.5), 2);

                boolean differentLayer = layerBlockEsp && plugin.isHiddenByYLayer(viewer, pos.y(), false);
                if (distSq > MAX_REVEAL_DIST_SQ || differentLayer) {
                    it.remove();
                    viewer.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()), getMaskData(world, pos.y()));
                }
            }

            Map<Long, Set<BlockPos>> worldMap = trackedBlocks.get(worldName);
            if (worldMap != null && !worldMap.isEmpty()) {
                int pChunkX = eye.getBlockX() >> 4;
                int pChunkZ = eye.getBlockZ() >> 4;
                int checkedCount = 0;

                outerLoop:
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        Set<BlockPos> blocksInChunk = worldMap.get(getChunkKey(pChunkX + dx, pChunkZ + dz));
                        if (blocksInChunk == null || blocksInChunk.isEmpty()) continue;

                        for (BlockPos pos : blocksInChunk) {
                            double distSq = Math.pow(eyeX - (pos.x() + 0.5), 2)
                                    + Math.pow(eyeY - (pos.y() + 0.5), 2)
                                    + Math.pow(eyeZ - (pos.z() + 0.5), 2);

                            if (distSq > MAX_REVEAL_DIST_SQ) continue;
                            if (layerBlockEsp && plugin.isHiddenByYLayer(viewer, pos.y(), false)) continue;

                            if (++checkedCount > MAX_BLOCKS_PER_PLAYER) break outerLoop;

                            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                            if (!isTargetMaterial(block.getType())) {
                                blocksInChunk.remove(pos);
                                revealedSet.remove(pos);
                                continue;
                            }

                            boolean canSee = true;
                            if (wallBlockEsp && distSq >= minShowDistSq && occlusionEngine != null) {
                                canSee = !occlusionEngine.isTargetOccludedAsync(
                                        worldName, eyeX, eyeY, eyeZ,
                                        pos.x(), pos.y(), pos.z(),
                                        pos.x() + 1.0, pos.y() + 1.0, pos.z() + 1.0
                                );
                            }

                            String timeKey = viewer.getUniqueId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
                            Location blockLoc = block.getLocation();

                            if (canSee) {
                                lastSeenBlockTime.put(timeKey, now);
                                if (revealedSet.add(pos)) {
                                    viewer.sendBlockChange(blockLoc, block.getBlockData());
                                }
                            } else {
                                if (revealedSet.contains(pos)) {
                                    long lastSeen = lastSeenBlockTime.getOrDefault(timeKey, 0L);
                                    if (now - lastSeen >= 1500L) {
                                        revealedSet.remove(pos);
                                        viewer.sendBlockChange(blockLoc, getMaskData(world, pos.y()));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            updateBaseEntitiesForViewer(viewer, wallBlockEsp, layerBlockEsp);
        }
    }

    private boolean isBaseEntity(Entity entity) {
        return entity instanceof Villager
                || entity instanceof ItemFrame
                || entity instanceof ArmorStand
                || entity instanceof Minecart
                || entity instanceof IronGolem
                || entity instanceof Allay
                || entity instanceof Boat;
    }

    private void updateBaseEntitiesForViewer(Player viewer, boolean wallEsp, boolean layerEsp) {
        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        int pChunkX = eye.getBlockX() >> 4;
        int pChunkZ = eye.getBlockZ() >> 4;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!world.isChunkLoaded(pChunkX + dx, pChunkZ + dz)) continue;
                Chunk chunk = world.getChunkAt(pChunkX + dx, pChunkZ + dz);

                for (Entity ent : chunk.getEntities()) {
                    if (!isBaseEntity(ent)) continue;

                    Location entLoc = ent.getLocation();
                    boolean shouldHide = false;

                    if (layerEsp && plugin.isHiddenByYLayer(viewer, entLoc.getY(), false)) {
                        shouldHide = true;
                    } else if (wallEsp) {
                        double distSq = eye.distanceSquared(entLoc);
                        if (distSq > MAX_REVEAL_DIST_SQ) {
                            shouldHide = true;
                        } else if (distSq > 16.0 && occlusionEngine != null) {
                            BoundingBox box = ent.getBoundingBox();
                            shouldHide = occlusionEngine.isTargetOccludedAsync(
                                    world.getName(), eye.getX(), eye.getY(), eye.getZ(),
                                    box.getMinX(), box.getMinY(), box.getMinZ(),
                                    box.getMaxX(), box.getMaxY(), box.getMaxZ()
                            );
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

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
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
                        if (isBaseEntity(ent)) {
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
        lastSeenBlockTime.clear();
    }
}
