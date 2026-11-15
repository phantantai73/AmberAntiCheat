package vn.amber.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AsyncOcclusionEngine implements Listener {

    private static class ChunkBitMask {
        final int minY;
        final int height;
        final BitSet solidBits;

        ChunkBitMask(int minY, int maxY) {
            this.minY = minY;
            this.height = maxY - minY;
            this.solidBits = new BitSet(16 * 16 * this.height);
        }

        int getIndex(int localX, int worldY, int localZ) {
            int relY = worldY - minY;
            if (relY < 0 || relY >= height) return -1;
            return (relY << 8) | (localZ << 4) | (localX & 15);
        }

        synchronized void setSolid(int localX, int worldY, int localZ, boolean solid) {
            int idx = getIndex(localX, worldY, localZ);
            if (idx >= 0) {
                solidBits.set(idx, solid);
            }
        }

        synchronized boolean isSolid(int localX, int worldY, int localZ) {
            int idx = getIndex(localX, worldY, localZ);
            return idx >= 0 && solidBits.get(idx);
        }
    }

    private final AmberAntiCheat plugin;
    private final Map<String, Map<Long, ChunkBitMask>> worldChunkMasks = new ConcurrentHashMap<>();

    public AsyncOcclusionEngine(AmberAntiCheat plugin) {
        this.plugin = plugin;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                indexChunkAsync(chunk);
            }
        }
    }

    private long getChunkKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    private void indexChunkAsync(Chunk chunk) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        long key = getChunkKey(chunk.getX(), chunk.getZ());
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ChunkBitMask mask = new ChunkBitMask(minY, maxY);
            for (int y = minY; y < maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Material mat = snapshot.getBlockType(x, y, z);
                        if (plugin.isFullSolidBlock(mat)) {
                            mask.setSolid(x, y, z, true);
                        }
                    }
                }
            }
            worldChunkMasks.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>()).put(key, mask);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        indexChunkAsync(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, ChunkBitMask> map = worldChunkMasks.get(e.getWorld().getName());
        if (map != null) {
            map.remove(getChunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        updateSingleBlock(e.getBlockPlaced(), e.getBlockPlaced().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        updateSingleBlock(e.getBlock(), Material.AIR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            updateSingleBlock(b, Material.AIR);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            updateSingleBlock(b, Material.AIR);
        }
    }

    private void updateSingleBlock(Block b, Material newType) {
        Map<Long, ChunkBitMask> map = worldChunkMasks.get(b.getWorld().getName());
        if (map == null) return;
        ChunkBitMask mask = map.get(getChunkKey(b.getX() >> 4, b.getZ() >> 4));
        if (mask != null) {
            mask.setSolid(b.getX() & 15, b.getY(), b.getZ() & 15, plugin.isFullSolidBlock(newType));
        }
    }

    public boolean isTargetOccludedAsync(
            String worldName,
            double eyeX, double eyeY, double eyeZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {

        double midX = (minX + maxX) * 0.5;
        double midY = (minY + maxY) * 0.5;
        double midZ = (minZ + maxZ) * 0.5;

        double[][] points = {
                {midX, midY, midZ},
                {midX, maxY - 0.1, midZ},
                {midX, minY + 0.1, midZ},
                {minX + 0.1, midY, minZ + 0.1},
                {maxX - 0.1, midY, maxZ - 0.1}
        };

        for (double[] pt : points) {
            if (!isRayBlockedAsync(worldName, eyeX, eyeY, eyeZ, pt[0], pt[1], pt[2])) {
                return false;
            }
        }
        return true;
    }

    public boolean isRayBlockedAsync(String worldName, double x0, double y0, double z0, double x1, double y1, double z1) {
        Map<Long, ChunkBitMask> worldMap = worldChunkMasks.get(worldName);
        if (worldMap == null) return false;

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.6) return false;

        int steps = (int) Math.ceil(dist * 2.5);
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        int endBlockX = (int) Math.floor(x1);
        int endBlockY = (int) Math.floor(y1);
        int endBlockZ = (int) Math.floor(z1);

        double curX = x0 + stepX;
        double curY = y0 + stepY;
        double curZ = z0 + stepZ;

        long cachedChunkKey = Long.MIN_VALUE;
        ChunkBitMask currentMask = null;

        for (int i = 1; i < steps; i++) {
            int bx = (int) Math.floor(curX);
            int by = (int) Math.floor(curY);
            int bz = (int) Math.floor(curZ);

            if (bx == endBlockX && by == endBlockY && bz == endBlockZ) {
                return false;
            }

            long ck = getChunkKey(bx >> 4, bz >> 4);
            if (ck != cachedChunkKey) {
                cachedChunkKey = ck;
                currentMask = worldMap.get(ck);
            }

            if (currentMask != null && currentMask.isSolid(bx & 15, by, bz & 15)) {
                return true;
            }

            curX += stepX;
            curY += stepY;
            curZ += stepZ;
        }
        return false;
    }
}
