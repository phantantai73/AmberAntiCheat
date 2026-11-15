package vn.amber.anticheat;

import org.bukkit.GameMode;
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

    private boolean isWallBetween(Player player, Location targetCenter, double halfWidth, double halfHeight) {
        if (occlusionEngine == null) return false;
        Location eye = player.getEyeLocation();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double tx = targetCenter.getX(), ty = targetCenter.getY(), tz = targetCenter.getZ();

        double distSq = Math.pow(ex - tx, 2) + Math.pow(ey - ty, 2) + Math.pow(ez - tz, 2);
        if (distSq <= 2.25) return false;

        return occlusionEngine.isTargetOccludedAsync(
                player.getWorld().getName(), ex, ey, ez,
                tx - halfWidth, ty - halfHeight, tz - halfWidth,
                tx + halfWidth, ty + halfHeight, tz + halfWidth
        );
    }

    private boolean isSensitiveInteractMaterial(Material type) {
        String name = type.name();
        return name.contains("CHEST")
                || name.endsWith("SHULKER_BOX")
                || name.equals("BARREL")
                || name.equals("HOPPER")
                || name.contains("FURNACE")
                || name.equals("SMOKER")
                || name.equals("DISPENSER")
                || name.equals("DROPPER")
                || name.equals("BREWING_STAND")
                || name.equals("BEACON")
                || name.equals("CRAFTER")
                || name.contains("ANVIL")
                || name.endsWith("_BUTTON")
                || name.equals("LEVER")
                || name.endsWith("_BED")
                || name.equals("RESPAWN_ANCHOR");
    }

    // =========================================================================
    // 1. KIỂM TRA COMBAT (REACH, CPS/KILLAURA, WALL-HIT)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatChecks(EntityDamageByEntityEvent e) {
        // Chỉ kiểm tra đòn đánh cận chiến trực tiếp (Loại bỏ bắt nhầm Giáp Gai Thorns & Chém lan Sweep)
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player attacker)) return;

        if (attacker.hasPermission("amberac.bypass")
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Entity target = e.getEntity();
        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. CPS / KillAura
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

        // 2. Reach (Tính từ mắt tới cạnh Hitbox gần nhất của mục tiêu)
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

        // 3. Đánh xuyên tường đặc (Wall-Hit / Freecam)
        if (target instanceof LivingEntity && isWallBetween(attacker, box.getCenter().toLocation(attacker.getWorld()), 0.35, 0.8)) {
            e.setCancelled(true);
            plugin.flag(attacker, "WALL-HIT", "Đánh xuyên tường đặc");
        }
    }

    // =========================================================================
    // 2. KIỂM TRA DI CHUYỂN (FLY, SPEED, INV-MOVE) & BỘ ĐỆM CHỐNG BẮT NHẦM
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            // Miễn trừ kiểm tra Speed/Fly trong 1.5s sau khi bị đánh văng / nổ TNT / Wind Charge
            movementGraceTimeMap.put(p.getUniqueId(), System.currentTimeMillis() + 1500L);
            airTicksMap.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        movementGraceTimeMap.put(uuid, System.currentTimeMillis() + 1500L);
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
        if (p.hasPermission("amberac.bypass")
                || p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR
                || p.getAllowFlight()
                || p.isInsideVehicle()
                || p.isGliding()
                || p.isRiptiding()) {
            airTicksMap.remove(uuid);
            movementGraceTimeMap.put(uuid, now + 1200L);
            return;
        }

        lastMoveTimeMap.put(uuid, now);

        // Đang trong thời gian miễn trừ sau khi bị đẩy lùi / nổ / dịch chuyển
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

        // 1. Check Speed
        double maxSpeed = 0.78;
        PotionEffect speedEffect = p.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) {
            maxSpeed += (speedEffect.getAmplifier() + 1) * 0.18;
        }
        if (horizontalSpeed > maxSpeed && p.getVelocity().length() < 0.1) {
            plugin.flag(p, "SPEED", String.format("Tốc độ: %.2f", horizontalSpeed));
        }

        // 2. Check Fly
        Block below = to.clone().subtract(0, 0.55, 0).getBlock();
        boolean nearGround = !below.getType().isAir()
                || p.isOnGround()
                || p.isSwimming()
                || p.isClimbing()
                || p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        if (!nearGround) {
            int airTicks = airTicksMap.merge(uuid, 1, Integer::sum);
            if (airTicks > 20 && dy >= -0.02 && p.getVelocity().getY() <= 0.0) {
                plugin.flag(p, "FLY", "Lơ lửng trên không (" + airTicks + " ticks)");
            }
        } else {
            airTicksMap.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClickWhileMoving(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.CREATIVE) return;

        long now = System.currentTimeMillis();
        if (now < movementGraceTimeMap.getOrDefault(p.getUniqueId(), 0L)) return;

        long lastMove = lastMoveTimeMap.getOrDefault(p.getUniqueId(), 0L);
        if (now - lastMove < 50L && p.isSprinting() && !p.isSwimming() && !p.isFlying()) {
            plugin.flag(p, "INV-MOVE", "Thao tác túi đồ khi đang chạy nước rút");
        }
    }

    // =========================================================================
    // 3. CHẶN FREECAM TƯƠNG TÁC XUYÊN TƯỜNG & SCAFFOLD
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        Player p = e.getPlayer();
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.SPECTATOR) return;

        Material type = b.getType();
        if (!isSensitiveInteractMaterial(type)) return;

        Location blockCenter = b.getLocation().add(0.5, 0.5, 0.5);
        double dist = p.getEyeLocation().distance(blockCenter);
        if (dist > 6.0) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-REACH", String.format("Mở khối quá xa (%.1fm)", dist));
            return;
        }

        if (isWallBetween(p, blockCenter, 0.45, 0.45)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-WALL", "Tương tác " + type.name() + " xuyên tường");
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
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.SPECTATOR) return;

        BoundingBox box = target.getBoundingBox();
        Location center = box.getCenter().toLocation(p.getWorld());
        double dist = p.getEyeLocation().distance(center);

        if (dist > 6.0) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-ENTITY", String.format("Chuột phải thực thể quá xa (%.1fm)", dist));
            return;
        }

        double halfW = Math.max(0.25, box.getWidthX() * 0.45);
        double halfH = Math.max(0.25, box.getHeight() * 0.45);
        if (isWallBetween(p, center, halfW, halfH)) {
            event.setCancelled(true);
            plugin.flag(p, "FREECAM-ENTITY", "Tương tác " + target.getType().name() + " xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.SPECTATOR) return;

        Location center = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(center) > 6.2 || isWallBetween(p, center, 0.45, 0.45)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-BREAK", "Đập " + e.getBlock().getType().name() + " xuyên tường");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFreecamPlaceAndScaffold(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.SPECTATOR) return;

        Location againstCenter = e.getBlockAgainst().getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(againstCenter) > 6.5 || isWallBetween(p, againstCenter, 0.48, 0.48)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-PLACE", "Đặt " + e.getBlockPlaced().getType().name() + " xuyên tường");
            return;
        }

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        long diff = now - lastPlaceTimeMap.getOrDefault(uuid, 0L);
        lastPlaceTimeMap.put(uuid, now);

        if (diff < 45L && p.getGameMode() != GameMode.CREATIVE) {
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
        if (p.hasPermission("amberac.bypass") || p.getGameMode() == GameMode.SPECTATOR) return;

        Location center = e.getBlockClicked().getLocation().add(0.5, 0.5, 0.5);
        if (p.getEyeLocation().distance(center) > 6.2 || isWallBetween(p, center, 0.45, 0.45)) {
            e.setCancelled(true);
            plugin.flag(p, "FREECAM-BUCKET", "Đổ xô " + e.getBucket().name() + " xuyên tường");
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
