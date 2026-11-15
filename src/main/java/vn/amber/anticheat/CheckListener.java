package vn.amber.anticheat;

import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CheckListener implements Listener {

    private final AmberAntiCheat plugin;
    private final AsyncOcclusionEngine occlusionEngine;

    // Combat Maps
    private final Map<UUID, Long> cpsWindowMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cpsCountMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastTargetIdMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttackTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> preAttackSpeedMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> keepSprintStreakMap = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastMoveYawDeltaMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> snapAimStreakMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTotemPopTimeMap = new ConcurrentHashMap<>();

    // Movement Maps
    private final Map<UUID, Integer> airTicksMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastDeltaXMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastDeltaYMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastDeltaZMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> takeoffGroundYMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> maxAirHeightReachedMap = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastValidLocationMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> setbackLockUntilMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> noSlowStreakMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> waterWalkStreakMap = new ConcurrentHashMap<>();

    // Interaction / GUI / Freecam Maps
    private final Map<UUID, Long> lastBlockedInteractMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> scaffoldUnderFeetStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movementGraceTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> slimeOrJumpGraceMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> opNotifyCooldownMap = new ConcurrentHashMap<>();

    public CheckListener(AmberAntiCheat plugin, AsyncOcclusionEngine occlusionEngine) {
        this.plugin = plugin;
        this.occlusionEngine = occlusionEngine;
    }

    /**
     * Ghi nhận thời điểm chặn tương tác Freecam (Dùng để khóa cứng mọi GUI cố mở lên sau cú click đó)
     */
    private void handleFreecamViolation(Player p, String checkName, String detail) {
        UUID uuid = p.getUniqueId();
        lastBlockedInteractMap.put(uuid, System.currentTimeMillis());

        if (!plugin.isExemptFromAnticheat(p)) {
            plugin.flag(p, checkName, detail);
        } else {
            long now = System.currentTimeMillis();
            if (now - opNotifyCooldownMap.getOrDefault(uuid, 0L) >= 1500L) {
                opNotifyCooldownMap.put(uuid, now);
                p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&8[&6&lAmber AntiCheat&8] &cĐã chặn thao tác Freecam/GUI: &e" + detail
                                + " &7(Bạn có OP/Creative nên không bị tính VL)"));
            }
        }
    }

    /**
     * Nhận diện toàn bộ các Block có thể mở GUI (Bao gồm cả SmartSpawner, Vault, Trial Spawner, Đầu trang trí, Rương...)
     */
    private boolean isGuiOrPluginBlock(Material type) {
        if (type == null || type.isAir()) return false;
        if (type.isInteractable()) return true;
        String name = type.name();
        return name.contains("SPAWNER")
                || name.contains("CHEST")
                || name.endsWith("SHULKER_BOX")
                || name.equals("VAULT")
                || name.equals("BARREL")
                || name.equals("HOPPER")
                || name.contains("FURNACE")
                || name.equals("SMOKER")
                || name.equals("DISPENSER")
                || name.equals("DROPPER")
                || name.equals("BREWING_STAND")
                || name.equals("BEACON")
                || name.contains("ANVIL")
                || name.equals("ENCHANTING_TABLE")
                || name.equals("CRAFTER")
                || name.equals("LECTERN")
                || name.equals("JUKEBOX")
                || name.equals("DECORATED_POT")
                || name.equals("CHISELED_BOOKSHELF")
                || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")
                || name.equals("END_PORTAL_FRAME")
                || name.equals("LODESTONE")
                || name.equals("RESPAWN_ANCHOR")
                || name.contains("CAULDRON");
    }

    private void forceSetback(Player p, PlayerMoveEvent e) {
        UUID uuid = p.getUniqueId();
        setbackLockUntilMap.put(uuid, System.currentTimeMillis() + 650L);

        World world = p.getWorld();
        Location validLoc = lastValidLocationMap.get(uuid);

        if (validLoc == null || validLoc.getWorld() == null || !validLoc.getWorld().equals(world)) {
            validLoc = findGroundBelow(e.getFrom());
            lastValidLocationMap.put(uuid, validLoc.clone());
        }

        Location pullback = validLoc.clone();
        if (e.getTo() != null) {
            pullback.setYaw(e.getTo().getYaw());
            pullback.setPitch(e.getTo().getPitch());
        }

        e.setTo(pullback);
        p.setSprinting(false);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0.0, -0.5, 0.0));
        lastDeltaXMap.put(uuid, 0.0);
        lastDeltaZMap.put(uuid, 0.0);
    }

    private Location findGroundBelow(Location loc) {
        World w = loc.getWorld();
        if (w == null) return loc;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        int minY = w.getMinHeight();

        for (int y = by; y >= minY; y--) {
            Material type = w.getBlockAt(bx, y, bz).getType();
            if (!type.isAir() && type.isSolid()) {
                return new Location(w, loc.getX(), y + 1.0, loc.getZ(), loc.getYaw(), loc.getPitch());
            }
        }
        return loc;
    }

    private BoundingBox getSafeBlockBox(Block b) {
        BoundingBox box = b.getBoundingBox();
        if (box.getWidthX() <= 0.01 || box.getHeight() <= 0.01 || box.getWidthZ() <= 0.01) {
            return BoundingBox.of(b);
        }
        return box;
    }

    private double getDistanceToBox(Location eye, BoundingBox box) {
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        double dx = Math.max(box.getMinX() - ex, Math.max(0.0, ex - box.getMaxX()));
        double dy = Math.max(box.getMinY() - ey, Math.max(0.0, ey - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - ez, Math.max(0.0, ez - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isRealBodyLookingAt(Player p, BoundingBox targetBox, double expandTolerance, double maxDist) {
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();
        BoundingBox expanded = targetBox.clone().expand(expandTolerance);
        if (expanded.contains(eyeVec)) return true;

        RayTraceResult hit = expanded.rayTrace(eyeVec, eye.getDirection(), maxDist);
        return hit != null;
    }

    private boolean isBlockOccludedByWall(Player p, Block targetBlock, BlockFace clickedFace) {
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();
        World world = p.getWorld();

        Block eyeBlock = eye.getBlock();
        Block feetBlock = p.getLocation().getBlock();
        if (targetBlock.equals(eyeBlock) || targetBlock.equals(feetBlock)) {
            return false;
        }

        RayTraceResult directLook = world.rayTraceBlocks(eye, eye.getDirection(), 6.0, FluidCollisionMode.NEVER, true);
        if (directLook != null && directLook.getHitBlock() != null && directLook.getHitBlock().equals(targetBlock)) {
            return false;
        }

        BoundingBox box = getSafeBlockBox(targetBlock);
        if (clickedFace != null && clickedFace != BlockFace.SELF) {
            Vector facePoint = getFaceCenterPoint(box, clickedFace);
            if (isRayClearToPoint(world, eye, eyeVec, facePoint, targetBlock, eyeBlock, feetBlock)) {
                return false;
            }
        }

        double minX = box.getMinX() + 0.03;
        double minY = box.getMinY() + 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double maxY = box.getMaxY() - 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        double midX = (minX + maxX) * 0.5;
        double midY = (minY + maxY) * 0.5;
        double midZ = (minZ + maxZ) * 0.5;

        double[][] samplePoints = {
                {midX, midY, midZ},
                {midX, maxY, midZ}, {midX, minY, midZ},
                {minX, midY, midZ}, {maxX, midY, midZ},
                {midX, midY, minZ}, {midX, midY, maxZ},
                {minX, minY, minZ}, {maxX, minY, minZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ},
                {minX, maxY, maxZ}, {maxX, maxY, maxZ}
        };

        for (double[] pt : samplePoints) {
            Vector targetPt = new Vector(pt[0], pt[1], pt[2]);
            if (isRayClearToPoint(world, eye, eyeVec, targetPt, targetBlock, eyeBlock, feetBlock)) {
                return false;
            }
        }

        return true;
    }

    private Vector getFaceCenterPoint(BoundingBox box, BlockFace face) {
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        double cz = box.getCenterZ();
        return switch (face) {
            case UP -> new Vector(cx, box.getMaxY() - 0.02, cz);
            case DOWN -> new Vector(cx, box.getMinY() + 0.02, cz);
            case NORTH -> new Vector(cx, cy, box.getMinZ() + 0.02);
            case SOUTH -> new Vector(cx, cy, box.getMaxZ() - 0.02);
            case WEST -> new Vector(box.getMinX() + 0.02, cy, cz);
            case EAST -> new Vector(box.getMaxX() - 0.02, cy, cz);
            default -> new Vector(cx, cy, cz);
        };
    }

    private boolean isRayClearToPoint(World world, Location eye, Vector eyeVec, Vector targetPt,
                                      Block targetBlock, Block eyeBlock, Block feetBlock) {
        Vector diff = targetPt.clone().subtract(eyeVec);
        double dist = diff.length();
        if (dist <= 0.15) return true;

        Vector dir = diff.normalize();
        Location startLoc = eye.clone();
        double remaining = dist - 0.02;

        for (int step = 0; step < 3 && remaining > 0.05; step++) {
            RayTraceResult res = world.rayTraceBlocks(startLoc, dir, remaining, FluidCollisionMode.NEVER, true);
            if (res == null || res.getHitBlock() == null) {
                return true;
            }
            Block hit = res.getHitBlock();
            if (hit.equals(targetBlock)) {
                return true;
            }
            if (hit.equals(eyeBlock) || hit.equals(feetBlock)) {
                Vector hitPos = res.getHitPosition();
                Vector nextStart = hitPos.clone().add(dir.clone().multiply(0.06));
                remaining = targetPt.distance(nextStart) - 0.02;
                startLoc = nextStart.toLocation(world);
                continue;
            }
            return false;
        }
        return false;
    }

    private boolean isBlockedToEntity(Player p, Entity target) {
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();
        World world = p.getWorld();
        BoundingBox box = target.getBoundingBox();

        double[][] points = {
                {box.getCenterX(), box.getCenterY(), box.getCenterZ()},
                {box.getCenterX(), box.getMaxY() - 0.1, box.getCenterZ()},
                {box.getCenterX(), box.getMinY() + 0.1, box.getCenterZ()}
        };

        Block eyeBlock = eye.getBlock();
        Block feetBlock = p.getLocation().getBlock();

        for (double[] pt : points) {
            Vector targetPt = new Vector(pt[0], pt[1], pt[2]);
            Vector diff = targetPt.clone().subtract(eyeVec);
            double dist = diff.length();
            if (dist < 0.3) return false;

            RayTraceResult res = world.rayTraceBlocks(eye, diff.normalize(), dist - 0.05, FluidCollisionMode.NEVER, true);
            if (res == null || res.getHitBlock() == null
                    || res.getHitBlock().equals(eyeBlock)
                    || res.getHitBlock().equals(feetBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean isServerSideSolidGround(World world, Location loc, UUID uuid) {
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        double[] offsets = {-0.28, 0.28};
        boolean onGround = false;

        for (double ox : offsets) {
            for (double oz : offsets) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);
                Material mat1 = world.getBlockAt(bx, (int) Math.floor(y - 0.05), bz).getType();
                Material mat2 = world.getBlockAt(bx, (int) Math.floor(y - 0.51), bz).getType();

                if (isWalkableSupport(mat1) || isWalkableSupport(mat2)) {
                    onGround = true;
                    String n1 = mat1.name();
                    String n2 = mat2.name();
                    if (n1.equals("SLIME_BLOCK") || n2.equals("SLIME_BLOCK")
                            || n1.endsWith("_BED") || n2.endsWith("_BED")) {
                        slimeOrJumpGraceMap.put(uuid, System.currentTimeMillis() + 2500L);
                    }
                }
                if (mat1 == Material.BUBBLE_COLUMN || mat2 == Material.BUBBLE_COLUMN) {
                    slimeOrJumpGraceMap.put(uuid, System.currentTimeMillis() + 2500L);
                }
            }
        }
        return onGround;
    }

    private boolean isWalkableSupport(Material mat) {
        if (mat == null || mat.isAir()) return false;
        if (mat == Material.WATER || mat == Material.LAVA) return false;
        String name = mat.name();
        return mat.isSolid()
                || name.contains("CARPET")
                || name.equals("LILY_PAD")
                || name.equals("SNOW")
                || name.equals("SCAFFOLDING")
                || name.equals("LADDER")
                || name.contains("VINE")
                || name.equals("COBWEB")
                || name.equals("POWDER_SNOW");
    }

    private boolean isLiquidSurfaceOnly(World world, Location loc) {
        Block feet = world.getBlockAt(loc.getBlockX(), (int) Math.floor(loc.getY()), loc.getBlockZ());
        Block below = world.getBlockAt(loc.getBlockX(), (int) Math.floor(loc.getY() - 0.35), loc.getBlockZ());
        return feet.getType().isAir() && (below.getType() == Material.WATER || below.getType() == Material.LAVA);
    }

    private boolean hasSpecialSurfaceNearby(World world, Location loc) {
        int bx = loc.getBlockX();
        int by = (int) Math.floor(loc.getY() - 0.6);
        int bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                String name = world.getBlockAt(bx + dx, by, bz + dz).getType().name();
                if (name.contains("ICE") || name.equals("SLIME_BLOCK")
                        || name.equals("SOUL_SAND") || name.equals("SOUL_SOIL")
                        || name.contains("STAIRS") || name.contains("SLAB")
                        || name.equals("BUBBLE_COLUMN")) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // 1. KIỂM TRA COMBAT SIẾT CHẶT
    // =========================================================================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombatChecks(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (attacker.getGameMode() == GameMode.SPECTATOR) return;

        Entity target = e.getEntity();
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        if (target instanceof LivingEntity && !attacker.hasLineOfSight(target) && isBlockedToEntity(attacker, target)) {
            e.setCancelled(true);
            handleFreecamViolation(attacker, "WALL-HIT", "Đánh xuyên tường đặc");
            return;
        }

        if (plugin.isExemptFromAnticheat(attacker)) return;

        if (attacker.isBlocking() || attacker.isHandRaised()) {
            e.setCancelled(true);
            plugin.flag(attacker, "ILLEGAL-ATTACK", "Đánh khi đang giương khiên hoặc ăn đồ");
            return;
        }

        long start = cpsWindowMap.computeIfAbsent(uuid, k -> now);
        int cps = cpsCountMap.merge(uuid, 1, Integer::sum);
        if (now - start >= 1000L) {
            int maxCps = plugin.getConfig().getInt("checks.combat.max-cps", 20);
            if (cps > maxCps) {
                e.setCancelled(true);
                plugin.flag(attacker, "KILLAURA-CPS", "CPS: " + cps);
                return;
            }
            cpsWindowMap.put(uuid, now);
            cpsCountMap.put(uuid, 0);
        }

        int targetId = target.getEntityId();
        Integer prevTargetId = lastTargetIdMap.put(uuid, targetId);
        Long prevAttackTime = lastAttackTimeMap.put(uuid, now);
        if (prevTargetId != null && prevAttackTime != null && prevTargetId != targetId) {
            long diff = now - prevAttackTime;
            if (diff < 100L) {
                e.setCancelled(true);
                plugin.flag(attacker, "KILLAURA-SWITCH", "Đổi mục tiêu siêu tốc (" + diff + "ms)");
                return;
            }
        }

        BoundingBox box = target.getBoundingBox();
        Location eye = attacker.getEyeLocation();
        double dist = getDistanceToBox(eye, box);

        double baseReach = plugin.getConfig().getDouble("checks.combat.reach-max", 3.15);
        double pingBuffer = Math.min(0.18, (attacker.getPing() / 1000.0) * 1.1);
        double maxReach = baseReach + pingBuffer;

        if (dist > maxReach) {
            e.setCancelled(true);
            plugin.flag(attacker, "REACH", String.format("Khoảng cách: %.2fm (Tối đa: %.2fm)", dist, maxReach));
            return;
        }

        if (!isRealBodyLookingAt(attacker, box, 0.30, maxReach + 0.8)) {
            e.setCancelled(true);
            plugin.flag(attacker, "KILLAURA-AIM", "Đánh lệch tâm nhìn xác thật");
            return;
        }

        float lastYawDelta = lastMoveYawDeltaMap.getOrDefault(uuid, 0f);
        if (lastYawDelta > 75.0f && dist > 1.5) {
            int snapStreak = snapAimStreakMap.merge(uuid, 1, Integer::sum);
            if (snapStreak >= 2) {
                snapAimStreakMap.put(uuid, 0);
                e.setCancelled(true);
                plugin.flag(attacker, "KILLAURA-SNAP", String.format("Giật tâm khóa mục tiêu (%.0f°)", lastYawDelta));
                return;
            }
        } else {
            snapAimStreakMap.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
        }

        boolean clientClaimsCrit = attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS);

        if (clientClaimsCrit) {
            int serverAirTicks = airTicksMap.getOrDefault(uuid, 0);
            double takeoffY = takeoffGroundYMap.getOrDefault(uuid, attacker.getLocation().getY());
            double maxReachedY = maxAirHeightReachedMap.getOrDefault(uuid, attacker.getLocation().getY());
            double lastDy = lastDeltaYMap.getOrDefault(uuid, 0.0);

            if (serverAirTicks < 2 || (maxReachedY - takeoffY < 0.12 && attacker.getLocation().getY() >= takeoffY - 0.05) || lastDy > 0.0) {
                e.setCancelled(true);
                attacker.setFallDistance(0f);
                plugin.flag(attacker, "CRITICALS", "Chí mạng giả (Packet/Mini-Jump Crit)");
            }
        }
    }

    // =========================================================================
    // 2. CHẶN AUTOTOTEM & INV-MOVE
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) {
            lastTotemPopTimeMap.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClickAndAutoTotem(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.isExemptFromAnticheat(p)) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean isTotemInvolved = (current != null && current.getType() == Material.TOTEM_OF_UNDYING)
                || (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING)
                || e.getClick() == ClickType.SWAP_OFFHAND;

        if (isTotemInvolved && (e.getSlot() == 40 || e.getRawSlot() == 45 || e.getClick() == ClickType.SWAP_OFFHAND)) {
            long sincePop = now - lastTotemPopTimeMap.getOrDefault(uuid, 0L);
            long sinceAtk = now - lastAttackTimeMap.getOrDefault(uuid, 0L);

            if (sincePop < 110L || sinceAtk < 75L) {
                e.setCancelled(true);
                p.closeInventory();
                plugin.flag(p, "AUTOTOTEM", "Tự động lắp Totem siêu tốc (" + Math.min(sincePop, sinceAtk) + "ms)");
                return;
            }
        }

        if (now < movementGraceTimeMap.getOrDefault(uuid, 0L)) return;
        long lastMove = lastMoveTimeMap.getOrDefault(uuid, 0L);
        if (now - lastMove < 50L && (p.isSprinting() || p.isSneaking() || p.isBlocking()) && !p.isSwimming() && !p.isFlying()) {
            e.setCancelled(true);
            p.closeInventory();
            plugin.flag(p, "INV-MOVE", "Thao tác túi đồ khi đang di chuyển");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandAutoTotem(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        ItemStack offhand = e.getOffHandItem();
        if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING) {
            long sincePop = System.currentTimeMillis() - lastTotemPopTimeMap.getOrDefault(p.getUniqueId(), 0L);
            if (sincePop < 85L) {
                e.setCancelled(true);
                plugin.flag(p, "AUTOTOTEM", "Đổi tay phụ Totem tức thời (" + sincePop + "ms)");
            }
        }
    }

    // =========================================================================
    // 3. BỘ ĐỆM MIỄN TRỪ KHI BỊ ĐÁNH VĂNG / WIND CHARGE / TELEPORT HỢP LỆ
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            UUID uuid = p.getUniqueId();
            movementGraceTimeMap.put(uuid, System.currentTimeMillis() + 1400L);
            airTicksMap.remove(uuid);
            lastDeltaYMap.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        movementGraceTimeMap.put(uuid, System.currentTimeMillis() + 1400L);
        airTicksMap.remove(uuid);
        lastDeltaYMap.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWindChargeOrPearlLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p) {
            String projName = e.getEntity().getType().name();
            if (projName.contains("WIND_CHARGE") || projName.equals("ENDER_PEARL")) {
                slimeOrJumpGraceMap.put(p.getUniqueId(), System.currentTimeMillis() + 2500L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        if (now < setbackLockUntilMap.getOrDefault(uuid, 0L)
                || e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }
        movementGraceTimeMap.put(uuid, now + 1000L);
        airTicksMap.remove(uuid);
        lastDeltaYMap.remove(uuid);
        if (e.getTo() != null) {
            lastValidLocationMap.put(uuid, e.getTo().clone());
            takeoffGroundYMap.put(uuid, e.getTo().getY());
            maxAirHeightReachedMap.put(uuid, e.getTo().getY());
        }
    }

    // =========================================================================
    // 4. KIỂM TRA DI CHUYỂN SIẾT CHẶT (SPEED, FLY, STRAFE, NOSLOW, STEP, JESUS)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Location from = e.getFrom();
        Location to = e.getTo();

        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        float yawDiff = Math.abs(to.getYaw() - from.getYaw()) % 360.0f;
        if (yawDiff > 180.0f) yawDiff = 360.0f - yawDiff;
        lastMoveYawDeltaMap.put(uuid, yawDiff);

        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        if (plugin.getSuspectGuiManager() != null && plugin.getSuspectGuiManager().isFrozen(uuid)) {
            return;
        }

        long now = System.currentTimeMillis();
        World world = p.getWorld();

        if (plugin.isExemptFromAnticheat(p)
                || p.getAllowFlight()
                || p.isInsideVehicle()
                || p.isGliding()
                || p.isRiptiding()) {
            airTicksMap.remove(uuid);
            lastDeltaXMap.remove(uuid);
            lastDeltaYMap.remove(uuid);
            lastDeltaZMap.remove(uuid);
            lastValidLocationMap.put(uuid, to.clone());
            takeoffGroundYMap.put(uuid, to.getY());
            maxAirHeightReachedMap.put(uuid, to.getY());
            movementGraceTimeMap.put(uuid, now + 1000L);
            return;
        }

        lastMoveTimeMap.put(uuid, now);

        boolean onSolidGround = isServerSideSolidGround(world, to, uuid);
        boolean inLiquidOrClimb = p.isSwimming() || p.isClimbing() || p.isInWater() || p.isInLava();
        boolean realNearGround = onSolidGround || inLiquidOrClimb;

        if (now < movementGraceTimeMap.getOrDefault(uuid, 0L)) {
            airTicksMap.remove(uuid);
            lastDeltaYMap.remove(uuid);
            if (onSolidGround) {
                lastValidLocationMap.put(uuid, to.clone());
                takeoffGroundYMap.put(uuid, to.getY());
                maxAirHeightReachedMap.put(uuid, to.getY());
            }
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontalSpeed = Math.sqrt(dx * dx + dz * dz);

        int airTicks;
        if (realNearGround) {
            airTicks = 0;
            airTicksMap.remove(uuid);
            lastDeltaYMap.remove(uuid);
        } else {
            airTicks = airTicksMap.merge(uuid, 1, Integer::sum);
            maxAirHeightReachedMap.merge(uuid, to.getY(), Math::max);
        }

        boolean hasJumpGrace = now < slimeOrJumpGraceMap.getOrDefault(uuid, 0L);
        double maxSingleTickUp = 0.60;
        PotionEffect jumpBoost = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jumpBoost != null) {
            maxSingleTickUp += (jumpBoost.getAmplifier() + 1) * 0.15;
        }
        if (!hasJumpGrace && dy > maxSingleTickUp) {
            forceSetback(p, e);
            plugin.flag(p, "STEP-HIGHJUMP", String.format("Nhảy/Bước cao bất thường (dY: %.2f)", dy));
            return;
        }

        if ((p.isHandRaised() || p.isBlocking()) && !hasJumpGrace) {
            double maxUseSpeed = 0.16;
            PotionEffect speedEff = p.getPotionEffect(PotionEffectType.SPEED);
            if (speedEff != null) maxUseSpeed += (speedEff.getAmplifier() + 1) * 0.04;

            if (horizontalSpeed > maxUseSpeed) {
                int nsStreak = noSlowStreakMap.merge(uuid, 1, Integer::sum);
                if (nsStreak >= 2) {
                    noSlowStreakMap.put(uuid, 0);
                    forceSetback(p, e);
                    plugin.flag(p, "NOSLOW", String.format("Chạy nhanh khi đang dùng vật phẩm (%.2f)", horizontalSpeed));
                    return;
                }
            } else {
                noSlowStreakMap.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
            }
        } else {
            noSlowStreakMap.remove(uuid);
        }

        if (!onSolidGround && isLiquidSurfaceOnly(world, to) && Math.abs(dy) < 0.005 && horizontalSpeed > 0.18) {
            int wwStreak = waterWalkStreakMap.merge(uuid, 1, Integer::sum);
            if (wwStreak >= 3) {
                waterWalkStreakMap.put(uuid, 0);
                forceSetback(p, e);
                plugin.flag(p, "JESUS", "Đi trên mặt chất lỏng (WaterWalk)");
                return;
            }
        } else {
            waterWalkStreakMap.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
        }

        Long lastAtk = lastAttackTimeMap.get(uuid);
        Double prevSpeed = preAttackSpeedMap.put(uuid, horizontalSpeed);
        if (lastAtk != null && prevSpeed != null && (now - lastAtk) < 90L && p.isSprinting() && onSolidGround) {
            if (horizontalSpeed > 0.30 && horizontalSpeed >= prevSpeed * 0.95) {
                int ksStreak = keepSprintStreakMap.merge(uuid, 1, Integer::sum);
                if (ksStreak >= 2) {
                    keepSprintStreakMap.put(uuid, 0);
                    forceSetback(p, e);
                    plugin.flag(p, "KILLAURA-SPRINT", "Chém liên tục không giảm tốc (KeepSprint)");
                    return;
                }
            } else {
                keepSprintStreakMap.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
            }
        }

        double limit;
        if (airTicks == 0) {
            limit = 0.375;
        } else if (airTicks == 1 && dy > 0.30) {
            limit = 0.635;
        } else if (airTicks <= 3) {
            limit = 0.49;
        } else {
            limit = 0.39;
        }

        if (hasSpecialSurfaceNearby(world, from) || hasSpecialSurfaceNearby(world, to)) {
            limit += 0.32;
        }
        if (!world.getBlockAt(to.getBlockX(), (int) Math.floor(to.getY() + 2.05), to.getBlockZ()).getType().isAir()) {
            limit += 0.22;
        }
        if (hasJumpGrace) {
            limit += 0.35;
        }
        PotionEffect speedEffect = p.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) {
            limit += (speedEffect.getAmplifier() + 1) * 0.14;
        }
        if (p.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            limit += 0.36;
        }

        if (horizontalSpeed > limit) {
            forceSetback(p, e);
            plugin.flag(p, "SPEED", String.format("Tốc độ: %.2f (Giới hạn: %.2f)", horizontalSpeed, limit));
            return;
        }

        Double prevDx = lastDeltaXMap.put(uuid, dx);
        Double prevDz = lastDeltaZMap.put(uuid, dz);
        if (!realNearGround && !hasJumpGrace && airTicks >= 2 && prevDx != null && prevDz != null) {
            double expectedDx = prevDx * 0.91;
            double expectedDz = prevDz * 0.91;
            double accelDiff = Math.hypot(dx - expectedDx, dz - expectedDz);

            if (accelDiff > 0.065 && horizontalSpeed > 0.25) {
                forceSetback(p, e);
                plugin.flag(p, "STRAFE", String.format("Bẻ lái trên không (Gia tốc: %.3f)", accelDiff));
                return;
            }
        }

        boolean hasFlyExemptEffect = p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || hasJumpGrace;

        Double lastDy = lastDeltaYMap.put(uuid, dy);

        if (!realNearGround && !hasFlyExemptEffect) {
            double maxJumpAscend = 1.28;
            double maxFirstTickDy = 0.43;
            int maxUpwardTicks = 5;

            if (jumpBoost != null) {
                int amp = jumpBoost.getAmplifier() + 1;
                maxJumpAscend += amp * 1.05;
                maxFirstTickDy += amp * 0.12;
                maxUpwardTicks += amp * 4;
            }

            double takeoffY = takeoffGroundYMap.getOrDefault(uuid, from.getY());
            double heightAboveTakeoff = to.getY() - takeoffY;

            boolean exceededMaxJumpHeight = (heightAboveTakeoff > maxJumpAscend && dy >= -0.02);
            boolean illegalVerticalBoost = (dy > maxFirstTickDy) || (airTicks > maxUpwardTicks && dy > 0.0);
            boolean illegalHoverOrGlide = (airTicks >= 2 && lastDy != null && dy > ((lastDy - 0.08) * 0.98) + 0.035);

            if (exceededMaxJumpHeight || illegalVerticalBoost || illegalHoverOrGlide) {
                forceSetback(p, e);
                plugin.flag(p, "FLY", String.format("Bay/Leo tường (AirTicks: %d, dY: %.2f)", airTicks, dy));
                return;
            }
        }

        if (onSolidGround && now >= setbackLockUntilMap.getOrDefault(uuid, 0L)) {
            lastValidLocationMap.put(uuid, to.clone());
            takeoffGroundYMap.put(uuid, to.getY());
            maxAirHeightReachedMap.put(uuid, to.getY());
        }
    }

    // =========================================================================
    // 5. CHẶN FREECAM CLICK MỞ GUI PLUGIN (SMARTSPAWNER...), PHÁ & ĐẶT BLOCK
    //    Chạy ở EventPriority.LOWEST để hủy TRƯỚC khi các plugin khác kịp mở GUI!
    // =========================================================================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamInteractBlock(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return;

        Block b = e.getClickedBlock();
        if (b == null) return;

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Material type = b.getType();
        BlockFace face = e.getBlockFace();
        ItemStack handItem = e.getItem();
        boolean holdingPlaceableBlock = (handItem != null && handItem.getType().isBlock() && !handItem.getType().isAir());
        boolean isGuiBlock = isGuiOrPluginBlock(type);

        // Nếu người chơi đang cầm block để đặt vào một khối thường (không phải khối mở GUI như Spawner/Chest),
        // cho phép kiểm tra tầm nhìn tới cả khối gốc (b) HOẶC ô không khí sát mặt đặt (adjacent) để không bao giờ kẹt đặt block
        boolean isNormalBlockPlacement = (action == Action.RIGHT_CLICK_BLOCK)
                && holdingPlaceableBlock
                && (!isGuiBlock || p.isSneaking())
                && type != Material.SPAWNER
                && type != Material.TRIAL_SPAWNER
                && type != Material.VAULT;

        BoundingBox blockBox = getSafeBlockBox(b);
        Block adjacent = (face != null && face != BlockFace.SELF) ? b.getRelative(face) : b;
        BoundingBox targetAimBox = isNormalBlockPlacement
                ? blockBox.clone().union(BoundingBox.of(adjacent))
                : blockBox;

        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.85;
        double dist = getDistanceToBox(p.getEyeLocation(), targetAimBox);

        // 1. Quá tầm với từ mắt xác thật
        if (dist > maxReach) {
            denyInteractionCompletely(e);
            handleFreecamViolation(p, "FREECAM-REACH", String.format("Click %s quá xa (%.1fm)", type.name(), dist));
            return;
        }

        // 2. Mắt xác thật không nhìn vào khối đang bấm (Bật Freecam bay hồn đi chỗ khác để bấm mở GUI/Spawner/Rương)
        double aimTolerance = isNormalBlockPlacement ? 0.60 : 0.42;
        if (!isRealBodyLookingAt(p, targetAimBox, aimTolerance, maxReach + 1.2)) {
            denyInteractionCompletely(e);
            handleFreecamViolation(p, "FREECAM-AIM", "Click " + type.name() + " lệch hướng nhìn xác thật (Freecam)");
            return;
        }

        // 3. Bị tường đặc che khuất
        boolean occluded = isBlockOccludedByWall(p, b, face);
        if (occluded && isNormalBlockPlacement && !adjacent.equals(b)) {
            // Với đặt block xây dựng hợp lệ, nếu ô không khí nơi đặt block nhìn thấy rõ thì cho phép
            occluded = isBlockOccludedByWall(p, adjacent, null);
        }

        if (occluded) {
            denyInteractionCompletely(e);
            handleFreecamViolation(p, "FREECAM-WALL", "Click " + type.name() + " xuyên tường");
        }
    }

    private void denyInteractionCompletely(PlayerInteractEvent e) {
        e.setCancelled(true);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
    }

    /**
     * CHỐT CHẶN CUỐI CÙNG: Khóa cứng InventoryOpenEvent ở EventPriority.LOWEST
     * Nếu bất kỳ plugin nào (SmartSpawner, Shop, Crate...) cố mở GUI ngay sau cú click xuyên tường/Freecam -> Đóng sập ngay lập tức!
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Nếu vừa bị chặn click Freecam/Xuyên tường trong vòng 300ms trước đó -> Cấm mở mọi GUI plugin!
        if (now - lastBlockedInteractMap.getOrDefault(uuid, 0L) < 300L) {
            e.setCancelled(true);
            return;
        }

        // 2. Nếu GUI gắn với một khối hoặc thực thể có tọa độ trong thế giới (Rương, Phễu, Lò nung, Shulker, Xe mỏ, Villager...)
        Location invLoc = e.getInventory().getLocation();
        if (invLoc != null && invLoc.getWorld() != null && invLoc.getWorld().equals(p.getWorld())) {
            // Bỏ qua nếu bản thân người chơi mở túi đồ cá nhân hoặc bàn chế tạo ảo tại chính vị trí đứng
            if (e.getInventory().getType() == InventoryType.CRAFTING
                    || e.getInventory().getType() == InventoryType.PLAYER) {
                return;
            }
            if (invLoc.distanceSquared(p.getLocation()) < 0.25) {
                return;
            }

            Block invBlock = invLoc.getBlock();
            BoundingBox box = getSafeBlockBox(invBlock);
            double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.6 : 4.9;
            double dist = getDistanceToBox(p.getEyeLocation(), box);

            if (dist > maxReach) {
                e.setCancelled(true);
                handleFreecamViolation(p, "FREECAM-GUI", String.format("Mở GUI %s quá xa (%.1fm)", invBlock.getType().name(), dist));
                return;
            }

            if (!isRealBodyLookingAt(p, box, 0.50, maxReach + 1.2)) {
                e.setCancelled(true);
                handleFreecamViolation(p, "FREECAM-GUI", "Mở GUI " + invBlock.getType().name() + " lệch hướng nhìn xác thật");
                return;
            }

            if (isBlockOccludedByWall(p, invBlock, null)) {
                e.setCancelled(true);
                handleFreecamViolation(p, "FREECAM-GUI", "Mở GUI " + invBlock.getType().name() + " xuyên tường");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamBlockDamage(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Block b = e.getBlock();
        BoundingBox blockBox = getSafeBlockBox(b);
        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.85;

        if (getDistanceToBox(p.getEyeLocation(), blockBox) > maxReach
                || !isRealBodyLookingAt(p, blockBox, 0.45, maxReach + 1.2)
                || isBlockOccludedByWall(p, b, null)) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-BREAK", "Đào " + b.getType().name() + " xuyên tường / Freecam");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Block b = e.getBlock();
        BoundingBox blockBox = getSafeBlockBox(b);
        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.9;
        double dist = getDistanceToBox(p.getEyeLocation(), blockBox);

        if (dist > maxReach) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-REACH", String.format("Phá %s quá xa (%.1fm)", b.getType().name(), dist));
            return;
        }

        if (!isRealBodyLookingAt(p, blockBox, 0.50, maxReach + 1.2)) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-AIM", "Phá " + b.getType().name() + " lệch hướng nhìn xác thật");
            return;
        }

        if (isBlockOccludedByWall(p, b, null)) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-BREAK", "Phá " + b.getType().name() + " xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamPlaceAndScaffold(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Block placed = e.getBlockPlaced();
        Block against = e.getBlockAgainst();

        BoundingBox placedBox = BoundingBox.of(placed);
        BoundingBox combinedBox = placedBox.clone().union(BoundingBox.of(against));

        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.6 : 5.0;
        double dist = getDistanceToBox(p.getEyeLocation(), placedBox);

        if (dist > maxReach) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-PLACE", String.format("Đặt %s quá xa (%.1fm)", placed.getType().name(), dist));
            return;
        }

        if (!isRealBodyLookingAt(p, combinedBox, 0.65, maxReach + 1.5)) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-AIM", "Đặt " + placed.getType().name() + " lệch hướng nhìn xác thật (Freecam)");
            return;
        }

        if (isBlockOccludedByWall(p, placed, null)) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-PLACE", "Đặt " + placed.getType().name() + " xuyên tường");
            return;
        }

        if (plugin.isExemptFromAnticheat(p)) return;

        UUID uuid = p.getUniqueId();
        Location loc = p.getLocation();
        boolean directlyUnderFeet = (placed.getX() == loc.getBlockX()
                && placed.getZ() == loc.getBlockZ()
                && placed.getY() == loc.getBlockY() - 1);

        if (directlyUnderFeet && against.getY() == placed.getY() && p.isSprinting() && loc.getPitch() < 20.0f) {
            int streak = scaffoldUnderFeetStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 3) {
                scaffoldUnderFeetStreak.put(uuid, 0);
                e.setCancelled(true);
                plugin.flag(p, "SCAFFOLD", "Tự đặt block dưới chân khi đang chạy nhìn thẳng");
            }
        } else {
            scaffoldUnderFeetStreak.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamInteractEntity(PlayerInteractEntityEvent e) {
        checkEntityWallInteraction(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamArmorStand(PlayerArmorStandManipulateEvent e) {
        checkEntityWallInteraction(e.getPlayer(), e.getRightClicked(), e);
    }

    private void checkEntityWallInteraction(Player p, Entity target, Cancellable event) {
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        BoundingBox box = target.getBoundingBox();
        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.85;
        double dist = getDistanceToBox(p.getEyeLocation(), box);

        if (dist > maxReach) {
            event.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-ENTITY", String.format("Tương tác thực thể quá xa (%.1fm)", dist));
            return;
        }

        if (!isRealBodyLookingAt(p, box, 0.45, maxReach + 1.2)) {
            event.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-AIM", "Tương tác " + target.getType().name() + " lệch hướng nhìn xác thật");
            return;
        }

        if (isBlockedToEntity(p, target)) {
            event.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-ENTITY", "Tương tác " + target.getType().name() + " xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamBucketEmpty(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Block target = e.getBlock();
        BoundingBox box = BoundingBox.of(target);
        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.9;

        if (getDistanceToBox(p.getEyeLocation(), box) > maxReach
                || !isRealBodyLookingAt(p, box.clone().union(BoundingBox.of(e.getBlockClicked())), 0.60, maxReach + 1.5)
                || isBlockOccludedByWall(p, target, e.getBlockFace())) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-BUCKET", "Đổ xô " + e.getBucket().name() + " xuyên tường / Freecam");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreecamBucketFill(PlayerBucketFillEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        Block clicked = e.getBlockClicked();
        BoundingBox box = BoundingBox.of(clicked);
        double maxReach = (p.getGameMode() == GameMode.CREATIVE) ? 5.5 : 4.9;

        if (getDistanceToBox(p.getEyeLocation(), box) > maxReach
                || !isRealBodyLookingAt(p, box, 0.55, maxReach + 1.5)
                || isBlockOccludedByWall(p, clicked, e.getBlockFace())) {
            e.setCancelled(true);
            handleFreecamViolation(p, "FREECAM-BUCKET", "Múc chất lỏng xuyên tường / Freecam");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        cpsWindowMap.remove(uuid);
        cpsCountMap.remove(uuid);
        lastTargetIdMap.remove(uuid);
        lastAttackTimeMap.remove(uuid);
        preAttackSpeedMap.remove(uuid);
        keepSprintStreakMap.remove(uuid);
        lastMoveYawDeltaMap.remove(uuid);
        snapAimStreakMap.remove(uuid);
        lastTotemPopTimeMap.remove(uuid);
        airTicksMap.remove(uuid);
        lastDeltaXMap.remove(uuid);
        lastDeltaYMap.remove(uuid);
        lastDeltaZMap.remove(uuid);
        takeoffGroundYMap.remove(uuid);
        maxAirHeightReachedMap.remove(uuid);
        lastValidLocationMap.remove(uuid);
        setbackLockUntilMap.remove(uuid);
        noSlowStreakMap.remove(uuid);
        waterWalkStreakMap.remove(uuid);
        lastBlockedInteractMap.remove(uuid);
        scaffoldUnderFeetStreak.remove(uuid);
        lastMoveTimeMap.remove(uuid);
        movementGraceTimeMap.remove(uuid);
        slimeOrJumpGraceMap.remove(uuid);
        opNotifyCooldownMap.remove(uuid);
    }
}
