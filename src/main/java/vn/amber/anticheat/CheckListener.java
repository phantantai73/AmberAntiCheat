package vn.amber.anticheat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

    private final Map<UUID, Long> cpsWindowMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cpsCountMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> airTicksMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPlaceTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastPlaceStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movementGraceTimeMap = new ConcurrentHashMap<>();

    public CheckListener(AmberAntiCheat plugin, AsyncOcclusionEngine occlusionEngine) {
        this.plugin = plugin;
        this.occlusionEngine = occlusionEngine;
    }

    private boolean isFreecamPacketFrozen(Player p) {
        if (p.getPing() > 250) return false;
        long sinceLastFlying = System.currentTimeMillis() - plugin.getLastFlyingPacketTime(p.getUniqueId());
        return sinceLastFlying > 1350L;
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

    // =========================================================================
    // 1. KIỂM TRA COMBAT (OP & SÁNG TẠO KHÔNG BỊ PHẠT HAY THÔNG BÁO)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatChecks(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (plugin.isExemptFromAnticheat(attacker)) return;

        Entity target = e.getEntity();
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        if (isFreecamPacketFrozen(attacker)) {
            e.setCancelled(true);
            plugin.flag(attacker, "FREECAM-PACKET", "Đánh thực thể khi đang đóng băng gói di chuyển (Freecam)");
            return;
        }

        long start = cpsWindowMap.computeIfAbsent(uuid, k -> now);
        int cps = cpsCountMap.merge(uuid, 1, Integer::sum);
        if (now - start >= 1000L) {
            int maxCps = plugin.getConfig().getInt("checks.combat.max-cps", 22);
            if (cps > maxCps) {
                e.setCancelled(true);
                plugin.flag(attacker, "KILLAURA-CPS", "CPS: " + cps);
            }
            cpsWindowMap.put(uuid, now);
            cpsCountMap.put(uuid, 0);
        }

        BoundingBox box = target.getBoundingBox();
        Location eye = attacker.getEyeLocation();
        double dx = Math.max(box.getMinX() - eye.getX(), Math.max(0, eye.getX() - box.getMaxX()));
        double dy = Math.max(box.getMinY() - eye.getY(), Math.max(0, eye.getY() - box.getMaxY()));
        double dz = Math.max(box.getMinZ() - eye.getZ(), Math.max(0, eye.getZ() - box.getMaxZ()));
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double maxReach = plugin.getConfig().getDouble("checks.combat.reach-max", 3.55);
        if (attacker.getPing() > 120) maxReach += 0.25;

        if (dist > maxReach) {
            e.setCancelled(true);
            plugin.flag(attacker, "REACH", String.format("Khoảng cách: %.2fm", dist));
            return;
        }

        if (!isRealBodyLookingAt(attacker, box, 0.45, 5.0)) {
            e.setCancelled(true);
            plugin.flag(attacker, "FREECAM-AIM", "Đánh mục tiêu ngoài hướng nhìn xác thật");
            return;
        }

        if (target instanceof LivingEntity && !attacker.hasLineOfSight(target) && isBlockedToEntity(attacker, target)) {
            e.setCancelled(true);
            plugin.flag(attacker, "WALL-HIT", "Đánh xuyên vật cản");
        }
    }

    // =========================================================================
    // 2. KIỂM TRA DI CHUYỂN (OP & SÁNG TẠO KHÔNG BỊ PHẠT HAY THÔNG BÁO)
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            movementGraceTimeMap.put(p.getUniqueId(), System.currentTimeMillis() + 1500L);
            airTicksMap.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        movementGraceTimeMap.put(uuid, System.currentTimeMillis() + 1200L);
        airTicksMap.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (e.getFrom().getX() == e.getTo().getX()
                && e.getFrom().getY() == e.getTo().getY()
                && e.getFrom().getZ() == e.getTo().getZ()) {
            return;
        }

        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        if (plugin.getSuspectGuiManager() != null && plugin.getSuspectGuiManager().isFrozen(uuid)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (plugin.isExemptFromAnticheat(p)
                || p.getAllowFlight()
                || p.isInsideVehicle()
                || p.isGliding()
                || p.isRiptiding()) {
            airTicksMap.remove(uuid);
            movementGraceTimeMap.put(uuid, now + 1200L);
            return;
        }

        lastMoveTimeMap.put(uuid, now);

        if (now < movementGraceTimeMap.getOrDefault(uuid, 0L)) {
            airTicksMap.remove(uuid);
            return;
        }

        Location from = e.getFrom();
        Location to = e.getTo();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontalSpeed = Math.sqrt(dx * dx + dz * dz);

        double maxSpeed = 0.72;
        PotionEffect speedEffect = p.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) {
            maxSpeed += (speedEffect.getAmplifier() + 1) * 0.16;
        }
        if (horizontalSpeed > maxSpeed && p.getVelocity().length() < 0.15) {
            e.setTo(from);
            plugin.flag(p, "SPEED", String.format("Tốc độ: %.2f", horizontalSpeed));
            return;
        }

        Block below = to.clone().subtract(0, 0.55, 0).getBlock();
        boolean nearGround = !below.getType().isAir()
                || p.isOnGround()
                || p.isSwimming()
                || p.isClimbing()
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        if (!nearGround) {
            int airTicks = airTicksMap.merge(uuid, 1, Integer::sum);
            if (airTicks > 16 && dy >= -0.03 && p.getVelocity().getY() <= 0.0) {
                Location pullback = from.clone();
                pullback.setY(Math.min(from.getY(), to.getY()) - 0.25);
                e.setTo(pullback);
                plugin.flag(p, "FLY", "Lơ lửng trên không (" + airTicks + " ticks)");
            }
        } else {
            airTicksMap.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClickWhileMoving(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.isExemptFromAnticheat(p)) return;

        long now = System.currentTimeMillis();
        if (now < movementGraceTimeMap.getOrDefault(p.getUniqueId(), 0L)) return;

        long lastMove = lastMoveTimeMap.getOrDefault(p.getUniqueId(), 0L);
        if (now - lastMove < 50L && p.isSprinting() && !p.isSwimming() && !p.isFlying()) {
            e.setCancelled(true);
            p.closeInventory();
            plugin.flag(p, "INV-MOVE", "Thao tác túi đồ khi đang chạy nước rút");
        }
    }

    // =========================================================================
    // 3. CHẶN FREECAM TƯƠNG TÁC (OP & SÁNG TẠO ĐƯỢC MIỄN TRỪ THÔNG BÁO/BAN)
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

        if (isFreecamPacketFrozen(p)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PACKET", "Mở " + type.name() + " khi xác thật ngưng gửi gói vị trí");
            return;
        }

        Location blockCenter = b.getLocation().add(0.5, 0.5, 0.5);
        double dist = p.getEyeLocation().distance(blockCenter);
        if (dist > 5.8) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-REACH", String.format("Mở %s quá xa (%.1fm)", type.name(), dist));
            return;
        }

        BoundingBox blockBox = BoundingBox.of(b);
        if (!isRealBodyLookingAt(p, blockBox, 0.35, 6.2)) {
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

        if (isFreecamPacketFrozen(p)) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-PACKET", "Tương tác thực thể khi đang bật Freecam");
            return;
        }

        BoundingBox box = target.getBoundingBox();
        Location center = box.getCenter().toLocation(p.getWorld());
        double dist = p.getEyeLocation().distance(center);

        if (dist > 5.8) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-ENTITY", String.format("Tương tác thực thể quá xa (%.1fm)", dist));
            return;
        }

        if (!isRealBodyLookingAt(p, box, 0.4, 6.2)) {
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
        if (isFreecamPacketFrozen(p)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PACKET", "Đập " + b.getType().name() + " khi đang bật Freecam");
            return;
        }

        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(center) > 6.0
                || !isRealBodyLookingAt(p, BoundingBox.of(b), 0.4, 6.2)
                || isBlockedBySolidBlock(p, b)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-BREAK", "Đập " + b.getType().name() + " bằng Freecam / xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamPlaceAndScaffold(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExemptFromAnticheat(p)) return;

        if (isFreecamPacketFrozen(p)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PACKET", "Đặt " + e.getBlockPlaced().getType().name() + " khi đang bật Freecam");
            return;
        }

        Block against = e.getBlockAgainst();
        Location againstCenter = against.getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(againstCenter) > 6.2 || isBlockedBySolidBlock(p, against)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PLACE", "Đặt " + e.getBlockPlaced().getType().name() + " xuyên tường");
            return;
        }

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        long diff = now - lastPlaceTimeMap.getOrDefault(uuid, 0L);
        lastPlaceTimeMap.put(uuid, now);

        if (diff < 45L) {
            int streak = fastPlaceStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 5) {
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
        if (isFreecamPacketFrozen(p)
                || p.getEyeLocation().distance(center) > 6.0
                || !isRealBodyLookingAt(p, BoundingBox.of(clicked), 0.4, 6.2)
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
        airTicksMap.remove(uuid);
        lastPlaceTimeMap.remove(uuid);
        fastPlaceStreak.remove(uuid);
        lastMoveTimeMap.remove(uuid);
        movementGraceTimeMap.remove(uuid);
    }
}
