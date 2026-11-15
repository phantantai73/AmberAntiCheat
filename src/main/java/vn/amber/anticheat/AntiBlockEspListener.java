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

                    // 1. Khác tầng Y=35: Ẩn toàn bộ thực thể (kể cả Mob, Item, Giáp)
                    if (layerEsp && plugin.isHiddenByYLayer(viewer, entLoc.getY(), false)) {
                        shouldHide = true;
                    } else if (wallEsp) {
                        // 2. Cùng tầng Y: Không ẩn Mob/Quái vật (Warden, Zombie, Golem, Villager...) để tránh lỗi tàng hình khi PvE
                        if (!(ent instanceof org.bukkit.entity.Mob)) {
                            double distSq = eye.distanceSquared(entLoc);
                            if (distSq > MAX_REVEAL_DIST_SQ) {
                                // Item rơi và thực thể Base (ArmorStand, ItemFrame, Minecart): Ẩn khi xa > 20m
                                shouldHide = true;
                            } else if (!(ent instanceof Item) && distSq > 9.0 && !viewer.hasLineOfSight(ent)) {
                                // Thực thể Base (ArmorStand, ItemFrame...): Ẩn khi khuất sau tường (Không ẩn Item Drop)
                                shouldHide = true;
                            }
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
