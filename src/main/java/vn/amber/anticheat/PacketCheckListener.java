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
        pendingVelocityTime.remove(uuid);
        velocityStartY.remove(uuid);
        velocityFailStreak.remove(uuid);
    }

    private Player resolvePlayer(PacketSendEvent event) {
        if (event.getPlayer() instanceof Player p) return p;
        if (event.getUser() != null && event.getUser().getUUID() != null) {
            return Bukkit.getPlayer(event.getUser().getUUID());
        }
        return null;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Player player = resolvePlayer(event);

        // 1. Gói CHUNK_DATA: Áp dụng cho TẤT CẢ (Kể cả OP và Creative cũng không thể bypass Anti-ESP/Xray)
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            if (antiXrayManager != null) {
                try {
                    WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
                    if (antiXrayManager.obfuscateChunkPacket(player, chunkData)) {
                        event.markForReEncode(true);
                    }
                } catch (Exception ignored) {
                }
            }
            return;
        }

        if (player == null) return;

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

        // 2. Chặn gói dữ liệu TileEntity, Âm thanh, Hạt khác tầng Y cho TẤT CẢ mọi người (OP & Creative đều bị chặn)
        if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            WrapperPlayServerBlockEntityData bePacket = new WrapperPlayServerBlockEntityData(event);
            int bx = bePacket.getPosition().getX();
            int by = bePacket.getPosition().getY();
            int bz = bePacket.getPosition().getZ();
            if (plugin.isHiddenByYLayer(player, by, false)
                    || player.getLocation().distanceSquared(new org.bukkit.Location(player.getWorld(), bx, by, bz)) > 256.0) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT) {
            if (plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                    && plugin.getConfig().getBoolean("hien-tren-an-duoi.chan-am-thanh-khac-tang", true)) {
                WrapperPlayServerSoundEffect soundPacket = new WrapperPlayServerSoundEffect(event);
                double soundY = soundPacket.getEffectPosition().getY() / 8.0;
                if (plugin.isHiddenByYLayer(player, soundY, false)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.PARTICLE) {
            if (plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)) {
                WrapperPlayServerParticle particlePacket = new WrapperPlayServerParticle(event);
                double particleY = particlePacket.getPosition().getY();
                if (plugin.isHiddenByYLayer(player, particleY, false)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity vel = new WrapperPlayServerEntityVelocity(event);
            if (vel.getEntityId() == player.getEntityId() && vel.getVelocity().getY() > 0.25) {
                UUID uuid = player.getUniqueId();
                pendingVelocityTime.put(uuid, System.currentTimeMillis());
                velocityStartY.put(uuid, player.getLocation().getY());
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            plugin.updateFlyingPacketTime(uuid);

            if (!plugin.getConfig().getBoolean("he-thong.anticheat", true)) return;
            // OP và Sáng tạo được miễn trừ hoàn toàn các kiểm tra Cheat di chuyển / góc nhìn
            if (plugin.isExemptFromAnticheat(player)) return;
            if (plugin.getSuspectGuiManager() != null && plugin.getSuspectGuiManager().isFrozen(uuid)) {
                return;
            }

            long now = System.currentTimeMillis();

            // 1. Check Timer
            long start = timerWindowStart.computeIfAbsent(uuid, k -> now);
            int count = timerPacketCount.merge(uuid, 1, Integer::sum);
            int maxPackets = plugin.getConfig().getInt("checks.movement.timer-max-packets-per-sec", 25);

            if (count > maxPackets && !player.isInsideVehicle() && !player.isGliding()) {
                event.setCancelled(true);
            }

            if (now - start >= 1000L) {
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

            // 3. Check Invalid Pitch
            float pitch = Float.NaN;
            if (event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
                pitch = new WrapperPlayClientPlayerRotation(event).getPitch();
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                pitch = new WrapperPlayClientPlayerPositionAndRotation(event).getPitch();
            }

            if (!Float.isNaN(pitch) && Math.abs(pitch) > 90.05f) {
                event.setCancelled(true);
                float finalPitch = pitch;
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.flag(player, "INVALID-ROTATION", String.format("Góc nhìn bất hợp lệ: %.1f°", finalPitch)));
            }
        }
    }
}
