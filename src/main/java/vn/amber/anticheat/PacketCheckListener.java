package vn.amber.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PacketCheckListener implements PacketListener {

    private final AmberAntiCheat plugin;
    private final AntiXrayManager antiXrayManager;

    private final Map<UUID, Long> timerWindowStart = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> timerPacketCount = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitchMap = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastDeltaPitchMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> gcdStreakMap = new ConcurrentHashMap<>();

    private final Map<UUID, Long> pendingVelocityTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> velocityStartY = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> velocityFailStreak = new ConcurrentHashMap<>();

    public PacketCheckListener(AmberAntiCheat plugin, AntiXrayManager antiXrayManager) {
        this.plugin = plugin;
        this.antiXrayManager = antiXrayManager;
    }

    public void clearPlayerData(UUID uuid) {
        timerWindowStart.remove(uuid);
        timerPacketCount.remove(uuid);
        lastPitchMap.remove(uuid);
        lastDeltaPitchMap.remove(uuid);
        gcdStreakMap.remove(uuid);
        pendingVelocityTime.remove(uuid);
        velocityStartY.remove(uuid);
        velocityFailStreak.remove(uuid);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // 1. Giữ nguyên tên trên bảng Tab khi ẩn mô hình 3D bằng Anti-PlayerESP
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            WrapperPlayServerPlayerInfoRemove removePacket = new WrapperPlayServerPlayerInfoRemove(event);
            List<UUID> uuids = new ArrayList<>(removePacket.getProfileIds());
            boolean modified = uuids.removeIf(targetId -> {
                Player target = Bukkit.getPlayer(targetId);
                return target != null && target.isOnline() && !plugin.isPlayerQuitting(targetId);
            });
            if (modified) {
                if (uuids.isEmpty()) {
                    event.setCancelled(true);
                } else {
                    removePacket.setProfileIds(uuids);
                    event.markForReEncode(true);
                }
            }
            return;
        }

        boolean bypassVisuals = player.hasPermission("amberac.bypass")
                || player.getGameMode() == GameMode.SPECTATOR
                || player.getGameMode() == GameMode.CREATIVE;

        // 2. Chặn gói NBT của TileEntity khác tầng Y (Chống lộ căn cứ qua Shift+F3 Pie-Chart)
        if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            if (!bypassVisuals
                    && plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                    && plugin.getConfig().getBoolean("hien-tren-an-duoi.chong-pie-chart-f3", true)) {
                WrapperPlayServerBlockEntityData bePacket = new WrapperPlayServerBlockEntityData(event);
                int blockY = bePacket.getPosition().getY();
                if (plugin.isHiddenByYLayer(player, blockY, false)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        // 3. Chặn âm thanh phát ra từ tầng bị ẩn (Chống SoundESP)
        if (event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT) {
            if (!bypassVisuals
                    && plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                    && plugin.getConfig().getBoolean("hien-tren-an-duoi.chan-am-thanh-khac-tang", true)) {
                WrapperPlayServerSoundEffect soundPacket = new WrapperPlayServerSoundEffect(event);
                double soundY = soundPacket.getEffectPosition().getY() / 8.0;
                if (plugin.isHiddenByYLayer(player, soundY, false)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        // 4. Chặn hạt hiệu ứng (Particle) phát ra từ tầng bị ẩn
        if (event.getPacketType() == PacketType.Play.Server.PARTICLE) {
            if (!bypassVisuals && plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)) {
                WrapperPlayServerParticle particlePacket = new WrapperPlayServerParticle(event);
                double particleY = particlePacket.getPosition().getY();
                if (plugin.isHiddenByYLayer(player, particleY, false)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        // 5. Ghi nhận thời điểm gửi gói đẩy lùi (Velocity) để bắt Anti-Knockback
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity vel = new WrapperPlayServerEntityVelocity(event);
            if (vel.getEntityId() == player.getEntityId() && vel.getVelocity().getY() > 0.25) {
                UUID uuid = player.getUniqueId();
                pendingVelocityTime.put(uuid, System.currentTimeMillis());
                velocityStartY.put(uuid, player.getLocation().getY());
            }
            return;
        }

        // 6. Xử lý gói CHUNK_DATA (Anti-Xray 1 Mode + Khóa Rương/Máy móc thành Đá)
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            if (!bypassVisuals && antiXrayManager != null) {
                try {
                    WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
                    if (antiXrayManager.obfuscateChunkPacket(player, chunkData)) {
                        event.markForReEncode(true);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!plugin.getConfig().getBoolean("he-thong.anticheat", true)) return;
        if (player.hasPermission("amberac.bypass")) return;

        UUID uuid = player.getUniqueId();
        if (plugin.getSuspectGuiManager() != null && plugin.getSuspectGuiManager().isFrozen(uuid)) {
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            long now = System.currentTimeMillis();

            // 1. Check Timer (Tua nhanh tốc độ game)
            long start = timerWindowStart.computeIfAbsent(uuid, k -> now);
            int count = timerPacketCount.merge(uuid, 1, Integer::sum);
            if (now - start >= 1000L) {
                int maxPackets = plugin.getConfig().getInt("checks.movement.timer-max-packets-per-sec", 26);
                if (count > maxPackets && !player.isInsideVehicle() && !player.isGliding()) {
                    int finalCount = count;
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.flag(player, "TIMER", finalCount + " gói/giây"));
                }
                timerWindowStart.put(uuid, now);
                timerPacketCount.put(uuid, 0);
            }

            // 2. Check Velocity (Anti-Knockback)
            Long velTime = pendingVelocityTime.get(uuid);
            if (velTime != null) {
                long elapsed = now - velTime;
                double currentY = player.getLocation().getY();
                if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
                    currentY = new WrapperPlayClientPlayerPosition(event).getPosition().getY();
                } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                    currentY = new WrapperPlayClientPlayerPositionAndRotation(event).getPosition().getY();
                }

                double startY = velocityStartY.getOrDefault(uuid, currentY);
                if (currentY - startY > 0.08) {
                    pendingVelocityTime.remove(uuid);
                    velocityFailStreak.put(uuid, 0);
                } else if (elapsed > Math.max(350L, player.getPing() + 180L)) {
                    pendingVelocityTime.remove(uuid);
                    if (!player.isInsideVehicle() && !player.isSwimming() && !player.isClimbing()) {
                        int streak = velocityFailStreak.merge(uuid, 1, Integer::sum);
                        if (streak >= 3) {
                            velocityFailStreak.put(uuid, 0);
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    plugin.flag(player, "VELOCITY", "Không nhận lực đẩy lùi (Anti-KB)"));
                        }
                    }
                }
            }

            // 3. Check Aim-GCD (Aimbot khóa tâm)
            float pitch = Float.NaN;
            if (event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
                pitch = new WrapperPlayClientPlayerRotation(event).getPitch();
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                pitch = new WrapperPlayClientPlayerPositionAndRotation(event).getPitch();
            }

            if (!Float.isNaN(pitch) && Math.abs(pitch) < 89.0f) {
                Float prevPitch = lastPitchMap.put(uuid, pitch);
                if (prevPitch != null) {
                    float deltaPitch = Math.abs(pitch - prevPitch);
                    if (deltaPitch > 0.5f && deltaPitch < 25.0f) {
                        Float prevDelta = lastDeltaPitchMap.put(uuid, deltaPitch);
                        if (prevDelta != null) {
                            long expandedCurr = (long) (deltaPitch * 16777216.0f);
                            long expandedPrev = (long) (prevDelta * 16777216.0f);
                            long gcd = computeGcd(expandedCurr, expandedPrev);
                            if (gcd > 0 && gcd < 131072L) {
                                int streak = gcdStreakMap.merge(uuid, 1, Integer::sum);
                                if (streak >= 14) {
                                    gcdStreakMap.put(uuid, 0);
                                    Bukkit.getScheduler().runTask(plugin, () ->
                                            plugin.flag(player, "AIM-GCD", "Khóa tâm bất thường"));
                                }
                            } else {
                                gcdStreakMap.computeIfPresent(uuid, (k, v) -> v > 0 ? v - 1 : 0);
                            }
                        }
                    }
                }
            }
        }
    }

    private long computeGcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
