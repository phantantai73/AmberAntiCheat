package vn.amber.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
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
    private final Map<UUID, Long> lastInvOpenTimeMap = new ConcurrentHashMap<>();

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

    // Interaction / Scaffold Maps
    private final Map<UUID, Long> lastPlaceTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastPlaceStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movementGraceTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> slimeOrJumpGraceMap = new ConcurrentHashMap<>();

    public CheckListener(AmberAntiCheat plugin, AsyncOcclusionEngine occlusionEngine) {
        this.plugin = plugin;
        this.occlusionEngine = occlusionEngine;
    }

    /**
     * KHÓA CỨNG TỌA ĐỘ (HARD-LOCK SETBACK):
     * Kéo thẳng người chơi về điểm hợp lệ cuối cùng dưới mặt đất và cắt toàn bộ đà di chuyển.
     */
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

    private boolean isRealBodyLookingAt(Player p, BoundingBox targetBox, double expandTolerance, double maxDist) {
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();
        BoundingBox expanded = targetBox.clone().expand(expandTolerance);
        if (expanded.contains(eyeVec)) return true;

        RayTraceResult hit = expanded.rayTrace(eyeVec, eye.getDirection(), maxDist);
        return hit != null;
    }

    private boolean isBlockedBySolidBlock(Player p, Block targetBlock) {
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();

        RayTraceResult lookRay = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 6.0, FluidCollisionMode.NEVER, true);
        if (lookRay != null && lookRay.getHitBlock() != null && lookRay.getHitBlock().equals(targetBlock)) {
            return false;
        }

        double bx = targetBlock.getX();
        double by = targetBlock.getY();
        double bz = targetBlock.getZ();
        double[][] points = {
                {bx + 0.5, by + 0.5, bz + 0.5},
                {bx + 0.5, by + 0.85, bz + 0.5},
                {bx + 0.5, by + 0.15, bz + 0.5},
                {bx + 0.2, by + 0.5, bz + 0.2},
                {bx + 0.8, by + 0.5, bz + 0.8}
        };

        for (double[] pt : points) {
            Vector targetVec = new Vector(pt[0], pt[1], pt[2]);
            Vector dir = targetVec.clone().subtract(eyeVec);
            double dist = dir.length();
            if (dist < 0.2) return false;

            RayTraceResult res = p.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
            if (res == null || res.getHitBlock() == null || res.getHitBlock().equals(targetBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlockedToEntity(Player p, Entity target) {
        Location eye = p.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        Location center = box.getCenter().toLocation(p.getWorld());
        Vector dir = center.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.5) return false;

        RayTraceResult res = p.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return res != null && res.getHitBlock() != null;
    }

    /**
     * Bán kính 0.28 (Nằm gọn bên trong Hitbox 0.30 của người chơi):
     * Chống 100% lỗi chạm vào tường dọc bên cạnh làm lọt Spider / Wall-Fly.
     */
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
    // 1. KIỂM TRA COMBAT SIẾT CHẶT (REACH 3.15m, CRITICALS, SHIELD-HIT, SNAP-AIM)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatChecks(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (plugin.isExemptFromAnticheat(attacker)) return;

        Entity target = e.getEntity();
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Chặn vừa Giương Khiên hoặc vừa Ăn/Uống vừa vung kiếm (NoSlow-Attack / AutoBlock)
        if (attacker.isBlocking() || attacker.isHandRaised()) {
            e.setCancelled(true);
            plugin.flag(attacker, "ILLEGAL-ATTACK", "Đánh khi đang giương khiên hoặc ăn đồ");
            return;
        }

        // 2. CPS Check (Giới hạn 20 CPS)
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

        // 3. KillAura Multi-Target Switch (Đánh 2 mục tiêu khác nhau trong < 100ms)
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

        // 4. Siết chặt Reach (3.15m chuẩn Survival + Bù Ping động tối đa 0.15m)
        BoundingBox box = target.getBoundingBox();
        Location eye = attacker.getEyeLocation();
        double dx = Math.max(box.getMinX() - eye.getX(), Math.max(0, eye.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - eye.getY(), Math.max(0, eye.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - eye.getZ(), Math.max(0, eye.getZ() - box.getMaxZ()));
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double baseReach = plugin.getConfig().getDouble("checks.combat.reach-max", 3.15);
        double pingBuffer = Math.min(0.18, (attacker.getPing() / 1000.0) * 1.1);
        double maxReach = baseReach + pingBuffer;

        if (dist > maxReach) {
            e.setCancelled(true);
            plugin.flag(attacker, "REACH", String.format("Khoảng cách: %.2fm (Tối đa: %.2fm)", dist, maxReach));
            return;
        }

        // 5. Kiểm tra hướng nhìn xác thật (Độ mở rộng thu hẹp về 0.30m)
        if (!isRealBodyLookingAt(attacker, box, 0.30, maxReach + 0.8)) {
            e.setCancelled(true);
            plugin.flag(attacker, "KILLAURA-AIM", "Đánh lệch tâm nhìn xác thật");
            return;
        }

        // 6. KillAura Snap-Aim (Giật góc nhìn > 75 độ trong 1 tick rồi đánh trúng mục tiêu cách > 1.5m)
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

        // 7. Chặn Packet Criticals (Gây đòn chí mạng giả khi chưa thực sự nhảy lên trên Server)
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

            // Nếu trên Server người chơi vẫn đang chạm đất (airTicks < 2) hoặc chỉ nhấp nhô < 0.12 block (Mini-Jump Crit)
            if (serverAirTicks < 2 || (maxReachedY - takeoffY < 0.12 && attacker.getLocation().getY() >= takeoffY - 0.05) || lastDy > 0.0) {
                e.setCancelled(true);
                attacker.setFallDistance(0f);
                plugin.flag(attacker, "CRITICALS", "Chí mạng giả (Packet/Mini-Jump Crit)");
                return;
            }
        }

        // 8. Đánh xuyên tường đặc
        if (target instanceof LivingEntity && !attacker.hasLineOfSight(target) && isBlockedToEntity(attacker, target)) {
            e.setCancelled(true);
            plugin.flag(attacker, "WALL-HIT", "Đánh xuyên tường đặc");
        }
    }

    // =========================================================================
    // 2. CHẶN AUTOTOTEM (LẮP TOTEM SIÊU TỐC KHI ĐANG CHẠY HOẶC VỪA NỔ TOTEM)
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p) {
            lastTotemPopTimeMap.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) {
            lastInvOpenTimeMap.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClickAndAutoTotem(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.isExemptFromAnticheat(p)) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        // Kiểm tra thao tác lắp Totem vào tay phụ (Slot 40 hoặc phím F / Offhand Swap)
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean isTotemInvolved = (current != null && current.getType() == Material.TOTEM_OF_UNDYING)
                || (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING)
                || e.getClick() == ClickType.SWAP_OFFHAND;

        if (isTotemInvolved && (e.getSlot() == 40 || e.getRawSlot() == 45 || e.getClick() == ClickType.SWAP_OFFHAND)) {
            long sincePop = now - lastTotemPopTimeMap.getOrDefault(uuid, 0L);
            long sinceAtk = now - lastAttackTimeMap.getOrDefault(uuid, 0L);

            // AutoTotem lắp lại Totem trong < 110ms ngay sau khi nổ hoặc vừa đánh người trong < 75ms
            if (sincePop < 110L || sinceAtk < 75L) {
                e.setCancelled(true);
                p.closeInventory();
                plugin.flag(p, "AUTOTOTEM", "Tự động lắp Totem siêu tốc (" + Math.min(sincePop, sinceAtk) + "ms)");
                return;
            }
        }

        // Kiểm tra Inv-Move (Vừa chạy/di chuyển vừa thao tác túi đồ)
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
        // Tuyệt đối không cấp miễn trừ nếu cú Teleport là do chính AntiCheat đang kéo Setback
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

        // Lưu độ lệch góc xoay Yaw ở mỗi tick để bắt KillAura Snap-Aim
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

        // 1. CHẶN STEP / HIGH-JUMP (Bước vọt lên tường > 0.60 block trong 1 tick)
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

        // 2. CHẶN NOSLOW (Vừa ăn đồ / uống thuốc / kéo cung / giương khiên vừa chạy nhanh)
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

        // 3. CHẶN JESUS (Đi bộ lướt trên mặt nước / mặt Lava mà không chìm)
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

        // 4. CHẶN KEEPSPRINT (Vừa chém người vừa giữ nguyên 100% tốc độ chạy nước rút)
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

        // 5. CHẶN SPEED -> KÉO GIẬT LÙI NGAY TỪ TICK ĐẦU TIÊN VƯỢT NGƯỠNG
        double limit;
        if (airTicks == 0) {
            limit = 0.375; // Tốc độ chạy bộ chuẩn trên mặt đất
        } else if (airTicks == 1 && dy > 0.30) {
            limit = 0.635; // Tick bật nhảy Sprint-Jump đầu tiên
        } else if (airTicks <= 3) {
            limit = 0.49;  // Quán tính giảm tốc sau cú nhảy
        } else {
            limit = 0.39;  // Đang rơi trên không
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

        // 6. CHẶN AIR-STRAFE (Bẻ lái đổi hướng đột ngột trên không trung trái quy luật quán tính)
        Double prevDx = lastDeltaXMap.put(uuid, dx);
        Double prevDz = lastDeltaZMap.put(uuid, dz);
        if (!realNearGround && !hasJumpGrace && airTicks >= 2 && prevDx != null && prevDz != null) {
            double expectedDx = prevDx * 0.91;
            double expectedDz = prevDz * 0.91;
            double accelDiff = Math.hypot(dx - expectedDx, dz - expectedDz);

            // Trong Vanilla, lực điều hướng trên không tối đa chỉ đạt ~0.026 mỗi tick. Vượt 0.065 khi đang bay nhanh là hack Strafe
            if (accelDiff > 0.065 && horizontalSpeed > 0.25) {
                forceSetback(p, e);
                plugin.flag(p, "STRAFE", String.format("Bẻ lái trên không (Gia tốc: %.3f)", accelDiff));
                return;
            }
        }

        // 7. CHẶN FLY & SPIDER -> KÉO SẬP XUỐNG MẶT ĐẤT NGAY LẬP TỨC
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

        // 8. CẬP NHẬT ĐIỂM NEO HỢP LỆ (Chỉ lưu khi thực sự đứng trên mặt đất cứng)
        if (onSolidGround && now >= setbackLockUntilMap.getOrDefault(uuid, 0L)) {
            lastValidLocationMap.put(uuid, to.clone());
            takeoffGroundYMap.put(uuid, to.getY());
            maxAirHeightReachedMap.put(uuid, to.getY());
        }
    }

    // =========================================================================
    // 5. CHẶN FREECAM & SCAFFOLD
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        Material type = b.getType();
        if (!type.isInteractable() && !type.name().contains("CHEST") && !type.name().endsWith("SHULKER_BOX")) {
            return;
        }

        Location blockCenter = b.getLocation().add(0.5, 0.5, 0.5);
        double dist = p.getEyeLocation().distance(blockCenter);
        if (dist > 5.5) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-REACH", String.format("Mở %s quá xa (%.1fm)", type.name(), dist));
            return;
        }

        BoundingBox blockBox = BoundingBox.of(b);
        if (!isRealBodyLookingAt(p, blockBox, 0.35, 6.0)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-AIM", "Mở " + type.name() + " lệch góc nhìn xác thật (Freecam)");
            return;
        }

        if (isBlockedBySolidBlock(p, b)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-WALL", "Mở " + type.name() + " xuyên vật cản");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamInteractEntity(PlayerInteractEntityEvent e) {
        checkEntityWallInteraction(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamArmorStand(PlayerArmorStandManipulateEvent e) {
        checkEntityWallInteraction(e.getPlayer(), e.getRightClicked(), e);
    }

    private void checkEntityWallInteraction(Player p, Entity target, Cancellable event) {
        if (plugin.isExemptFromAnticheat(p)) return;

        BoundingBox box = target.getBoundingBox();
        Location center = box.getCenter().toLocation(p.getWorld());
        double dist = p.getEyeLocation().distance(center);

        if (dist > 5.5) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-ENTITY", String.format("Tương tác thực thể quá xa (%.1fm)", dist));
            return;
        }

        if (!isRealBodyLookingAt(p, box, 0.38, 6.0)) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-AIM", "Tương tác " + target.getType().name() + " lệch góc nhìn xác thật");
            return;
        }

        if (isBlockedToEntity(p, target)) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-ENTITY", "Tương tác " + target.getType().name() + " xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        Block b = e.getBlock();
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(center) > 5.6
                || !isRealBodyLookingAt(p, BoundingBox.of(b), 0.38, 6.0)
                || isBlockedBySolidBlock(p, b)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-BREAK", "Đập " + b.getType().name() + " bằng Freecam / xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamPlaceAndScaffold(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        Block against = e.getBlockAgainst();
        Location againstCenter = against.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(againstCenter) > 5.8 || isBlockedBySolidBlock(p, against)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PLACE", "Đặt " + e.getBlockPlaced().getType().name() + " xuyên tường");
            return;
        }

        // Chặn Scaffold đặt block dưới chân khi đang chạy nước rút mà không cúi nhìn xuống
        Block placed = e.getBlockPlaced();
        if (placed.getY() < p.getLocation().getBlockY()
                && p.isSprinting()
                && p.getLocation().getPitch() < 45.0f) {
            e.setCancelled(true);
            plugin.flag(p, "SCAFFOLD-ANGLE", "Đặt block dưới chân khi đang chạy và nhìn ngang");
            return;
        }

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        long diff = now - lastPlaceTimeMap.getOrDefault(uuid, 0L);
        lastPlaceTimeMap.put(uuid, now);

        if (diff < 55L) {
            int streak = fastPlaceStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 4) {
                e.setCancelled(true);
                fastPlaceStreak.put(uuid, 0);
                plugin.flag(p, "SCAFFOLD", "Đặt block siêu tốc (" + diff + "ms)");
            }
        } else {
            fastPlaceStreak.put(uuid, 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamBucket(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        Block clicked = e.getBlockClicked();
        Location center = clicked.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(center) > 5.6
                || !isRealBodyLookingAt(p, BoundingBox.of(clicked), 0.38, 6.0)
                || isBlockedBySolidBlock(p, clicked)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-BUCKET", "Đổ xô " + e.getBucket().name() + " bằng Freecam / xuyên tường");
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
        lastInvOpenTimeMap.remove(uuid);
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
        lastPlaceTimeMap.remove(uuid);
        fastPlaceStreak.remove(uuid);
        lastMoveTimeMap.remove(uuid);
        movementGraceTimeMap.remove(uuid);
        slimeOrJumpGraceMap.remove(uuid);
    }
}
