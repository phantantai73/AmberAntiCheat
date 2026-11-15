package vn.amber.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AmberAntiCheat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, PluginMessageListener {

    private final Map<UUID, Integer> playerLayerZone = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    private final Map<UUID, String> clientBrands = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenPlayersMap = new ConcurrentHashMap<>();
    private final Set<UUID> quittingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSeenTimestampMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> logCooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> alertCooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlyingPacketMap = new ConcurrentHashMap<>();

    private File punishmentFile;
    private FileConfiguration punishmentConfig;

    private AsyncOcclusionEngine occlusionEngine;
    private AntiXrayManager antiXrayManager;
    private AntiBlockEspListener blockEspListener;
    private SettingsGuiManager settingsGuiManager;
    private SuspectGuiManager suspectGuiManager;
    private PacketCheckListener packetCheckListener;

    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat vnDateFormat = new SimpleDateFormat("HH:mm:ss - dd/MM/yyyy");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initPunishmentStorage();

        this.occlusionEngine = new AsyncOcclusionEngine(this);
        this.antiXrayManager = new AntiXrayManager(this);
        this.blockEspListener = new AntiBlockEspListener(this, occlusionEngine);
        this.settingsGuiManager = new SettingsGuiManager(this);
        this.suspectGuiManager = new SuspectGuiManager(this);
        this.packetCheckListener = new PacketCheckListener(this, antiXrayManager);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(occlusionEngine, this);
        getServer().getPluginManager().registerEvents(antiXrayManager, this);
        getServer().getPluginManager().registerEvents(blockEspListener, this);
        getServer().getPluginManager().registerEvents(settingsGuiManager, this);
        getServer().getPluginManager().registerEvents(suspectGuiManager, this);
        getServer().getPluginManager().registerEvents(new CheckListener(this, occlusionEngine), this);

        PacketEvents.getAPI().getEventManager().registerListener(packetCheckListener, PacketListenerPriority.NORMAL);

        getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", this);

        PluginCommand mainCmd = getCommand("amberac");
        if (mainCmd != null) {
            mainCmd.setExecutor(this);
            mainCmd.setTabCompleter(this);
        }
        if (getCommand("acsettings") != null) getCommand("acsettings").setExecutor(this);
        if (getCommand("sus") != null) getCommand("sus").setExecutor(this);

        blockEspListener.startTask();
        Bukkit.getScheduler().runTaskTimer(this, this::runAntiPlayerEspTask, 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : violations.keySet()) {
                violations.computeIfPresent(id, (k, v) -> v > 1 ? v - 1 : null);
            }
        }, 600L, 600L);

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastFlyingPacketMap.put(p.getUniqueId(), now);
            playerLayerZone.put(p.getUniqueId(), computeZone(p.getLocation().getBlockY()));
            applyAllowedChunkRadius(p);
            refreshChunksAround(p, 4);
        }

        getLogger().info("Amber AntiCheat (All-in-One 20 TPS) da kich hoat thanh cong!");
    }

    @Override
    public void onDisable() {
        revealAllHiddenPlayers();
        if (blockEspListener != null) {
            blockEspListener.restoreAllHiddenBlocks();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setSendViewDistance(-1);
        }
    }

    /**
     * Chỉ miễn trừ kiểm tra Cheat PvP / Di chuyển cho người có OP, chế độ Sáng Tạo, Khán Giả hoặc quyền bypass.
     * Lưu ý: Các tính năng Anti-ESP, Anti-Xray, Ẩn Base và Phân tầng Y=35 KHÔNG dùng hàm này (áp dụng cho cả OP và Creative).
     */
    public boolean isExemptFromAnticheat(Player p) {
        if (p == null) return true;
        GameMode gm = p.getGameMode();
        return p.isOp()
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || p.hasPermission("amberac.bypass");
    }

    private void initPunishmentStorage() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        punishmentFile = new File(getDataFolder(), "punishments.yml");
        if (!punishmentFile.exists()) {
            try {
                punishmentFile.createNewFile();
            } catch (IOException ignored) {
            }
        }
        punishmentConfig = YamlConfiguration.loadConfiguration(punishmentFile);
    }

    private synchronized void savePunishmentData() {
        try {
            punishmentConfig.save(punishmentFile);
        } catch (IOException ignored) {
        }
    }

    public void updateFlyingPacketTime(UUID uuid) {
        lastFlyingPacketMap.put(uuid, System.currentTimeMillis());
    }

    public long getLastFlyingPacketTime(UUID uuid) {
        return lastFlyingPacketMap.getOrDefault(uuid, System.currentTimeMillis());
    }

    public AntiXrayManager getAntiXrayManager() {
        return antiXrayManager;
    }

    public AntiBlockEspListener getBlockEspListener() {
        return blockEspListener;
    }

    public SuspectGuiManager getSuspectGuiManager() {
        return suspectGuiManager;
    }

    public AsyncOcclusionEngine getOcclusionEngine() {
        return occlusionEngine;
    }

    public boolean isPlayerQuitting(UUID uuid) {
        return quittingPlayers.contains(uuid);
    }

    public String getClientBrand(UUID uuid) {
        return clientBrands.getOrDefault(uuid, "Vanilla");
    }

    public void clearPlayerViolations(UUID uuid) {
        violations.remove(uuid);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (antiXrayManager != null) {
            antiXrayManager.reloadStateMappings();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyAllowedChunkRadius(p);
            refreshChunksAround(p, 4);
        }
        if (!getConfig().getBoolean("anti-player-esp.bat", true)
                && !getConfig().getBoolean("hien-tren-an-duoi.bat", true)) {
            revealAllHiddenPlayers();
        }
        if (!getConfig().getBoolean("anti-block-esp.bat", true)
                && !getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                && blockEspListener != null) {
            blockEspListener.restoreAllHiddenBlocks();
        }
    }

    public boolean isFullSolidBlock(Material mat) {
        if (mat == null || mat.isAir() || !mat.isBlock() || !mat.isSolid() || !mat.isOccluding()) {
            return false;
        }
        String name = mat.name();
        return !name.contains("GLASS")
                && !name.contains("LEAVES")
                && !name.contains("FENCE")
                && !name.contains("WALL")
                && !name.contains("STAIRS")
                && !name.contains("SLAB")
                && !name.contains("DOOR")
                && !name.contains("TRAPDOOR")
                && !name.contains("BARS")
                && !name.contains("CHEST")
                && !name.contains("SHULKER")
                && !name.equals("SPAWNER")
                && !name.equals("HOPPER")
                && !name.equals("SLIME_BLOCK")
                && !name.equals("HONEY_BLOCK")
                && !name.equals("ICE");
    }

    // =========================================================================
    // PHÂN TẦNG Y=35 (ÁP DỤNG CHO CẢ OP VÀ SÁNG TẠO)
    // =========================================================================
    public int computeZone(int blockY) {
        int splitY = getConfig().getInt("hien-tren-an-duoi.moc-do-cao-y", 35);
        if (blockY >= splitY + 4) return 1;
        if (blockY <= splitY - 3) return -1;
        return 0;
    }

    public int getPlayerZone(Player viewer) {
        if (viewer == null) return 1;
        return playerLayerZone.computeIfAbsent(viewer.getUniqueId(), k -> computeZone(viewer.getLocation().getBlockY()));
    }

    public boolean isHiddenByYLayer(Player viewer, double targetY, boolean isPlayerTarget) {
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) return false;
        if (isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-nguoi-choi", true)) return false;
        if (!isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-ruong-block", true)) return false;

        double splitY = getConfig().getDouble("hien-tren-an-duoi.moc-do-cao-y", 35.0);
        int zone = getPlayerZone(viewer);
        if (zone == 1) return targetY <= splitY;
        if (zone == -1) return targetY > splitY;
        return (viewer.getLocation().getY() > splitY) != (targetY > splitY);
    }

    public void applyAllowedChunkRadius(Player p) {
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) {
            p.setSendViewDistance(-1);
            return;
        }
        int zone = getPlayerZone(p);
        int surfaceChunks = Math.max(2, getConfig().getInt("hien-tren-an-duoi.chunk-cho-phep-tren-mat-dat", 6));
        int undergroundChunks = Math.max(2, getConfig().getInt("hien-tren-an-duoi.chunk-cho-phep-duoi-ham", 2));

        int allowedRadius = (zone >= 0) ? surfaceChunks : undergroundChunks;
        if (p.getSendViewDistance() != allowedRadius) {
            p.setSendViewDistance(allowedRadius);
        }
    }

    public void refreshChunksAround(Player p, int radius) {
        if (p == null || !p.isOnline()) return;
        int cx = p.getLocation().getBlockX() >> 4;
        int cz = p.getLocation().getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (p.getWorld().isChunkLoaded(cx + dx, cz + dz)) {
                    p.getWorld().refreshChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrossYThreshold(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) return;

        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        int newZone = computeZone(e.getTo().getBlockY());
        int oldZone = playerLayerZone.getOrDefault(uuid, computeZone(e.getFrom().getBlockY()));

        if (newZone != oldZone) {
            playerLayerZone.put(uuid, newZone);
            applyAllowedChunkRadius(p);
            if (blockEspListener != null) {
                blockEspListener.clearPlayerCache(uuid);
            }
            refreshChunksAround(p, 4);
        }
    }

    // =========================================================================
    // ANTI-PLAYER ESP (KHÔNG CHO OP HAY CREATIVE BYPASS)
    // =========================================================================
    private void runAntiPlayerEspTask() {
        boolean wallEspEnabled = getConfig().getBoolean("anti-player-esp.bat", true);
        boolean layerEspEnabled = getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                && getConfig().getBoolean("hien-tren-an-duoi.an-nguoi-choi", true);

        if (!wallEspEnabled && !layerEspEnabled) {
            revealAllHiddenPlayers();
            return;
        }

        double maxViewDist = getConfig().getDouble("anti-player-esp.khoang-cach-toi-da", 64.0);
        double wallHideDist = getConfig().getDouble("anti-player-esp.khoang-cach-xuyen-tuong", 8.0);
        long now = System.currentTimeMillis();
        int rayBudget = 250;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (Player viewer : players) {
            UUID viewerId = viewer.getUniqueId();
            Set<UUID> hiddenSet = hiddenPlayersMap.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
            Location eye = viewer.getEyeLocation();

            for (Player target : players) {
                if (viewer.equals(target)) continue;
                UUID targetId = target.getUniqueId();

                if (!viewer.getWorld().equals(target.getWorld())) {
                    hiddenSet.remove(targetId);
                    continue;
                }

                String pairKey = viewerId + ":" + targetId;
                boolean occludedNow = false;

                if (!target.isGlowing()) {
                    double dist = eye.distance(target.getLocation());

                    if (layerEspEnabled && isHiddenByYLayer(viewer, target.getLocation().getY(), true)) {
                        occludedNow = true;
                        lastSeenTimestampMap.remove(pairKey);
                    } else if (wallEspEnabled) {
                        if (dist > maxViewDist) {
                            occludedNow = true;
                        } else if (dist > wallHideDist && rayBudget > 0) {
                            rayBudget -= 3;
                            occludedNow = isCompletelyOccluded(viewer, target);
                        }
                    }
                }

                if (!occludedNow) {
                    lastSeenTimestampMap.put(pairKey, now);
                    if (hiddenSet.remove(targetId)) {
                        showEntityModelOnly(viewer, target);
                    }
                } else {
                    long lastSeen = lastSeenTimestampMap.getOrDefault(pairKey, 0L);
                    if (now - lastSeen >= 1200L) {
                        if (hiddenSet.add(targetId)) {
                            hideEntityModelKeepTab(viewer, target);
                        }
                    }
                }
            }
        }
    }

    private boolean isCompletelyOccluded(Player viewer, Player target) {
        if (occlusionEngine == null) return false;
        Location eye = viewer.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        return occlusionEngine.isTargetOccludedAsync(
                viewer.getWorld().getName(),
                eye.getX(), eye.getY(), eye.getZ(),
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()
        );
    }

    private void hideEntityModelKeepTab(Player viewer, Player target) {
        viewer.hidePlayer(this, target);
    }

    private void showEntityModelOnly(Player viewer, Player target) {
        viewer.showPlayer(this, target);
    }

    public void revealAllHiddenPlayers() {
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenPlayersMap.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) continue;
            for (UUID targetId : entry.getValue()) {
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) {
                    viewer.showPlayer(this, target);
                }
            }
            entry.getValue().clear();
        }
        hiddenPlayersMap.clear();
        lastSeenTimestampMap.clear();
    }

    // =========================================================================
    // CẢNH BÁO & XỬ PHẠT TỰ ĐỘNG KHI VƯỢT QUÁ 30 VL (MIỄN TRỪ OP & CREATIVE)
    // =========================================================================
    public void flag(Player p, String checkName, String detail) {
        if (!getConfig().getBoolean("he-thong.anticheat", true)) return;
        // Người có OP hoặc đang ở chế độ Sáng Tạo sẽ không bị thông báo hay Kick/Ban
        if (isExemptFromAnticheat(p)) return;

        int maxPing = getConfig().getInt("chong-ban-nham.ping-toi-da-kiem-tra", 280);
        double minTps = getConfig().getDouble("chong-ban-nham.tps-toi-thieu-kiem-tra", 17.5);
        double currentTps = Bukkit.getTPS()[0];

        if (p.getPing() > maxPing || currentTps < minTps) return;

        UUID uuid = p.getUniqueId();
        int vl = violations.merge(uuid, 1, Integer::sum);

        if (suspectGuiManager != null) {
            suspectGuiManager.recordViolation(p, checkName, detail, vl);
        }

        long now = System.currentTimeMillis();
        if (getConfig().getBoolean("he-thong.thong-bao-staff", true)) {
            if (now - alertCooldownMap.getOrDefault(uuid, 0L) >= 2500L) {
                alertCooldownMap.put(uuid, now);
                sendClickableAlert(p, checkName, detail, vl);
            }
        }

        if (now - logCooldownMap.getOrDefault(uuid, 0L) >= 4000L) {
            logCooldownMap.put(uuid, now);
            logViolationAsync(p.getName(), checkName, detail, vl, p.getPing(), currentTps);
        }

        boolean safeMode = getConfig().getBoolean("chong-ban-nham.che-do-an-toan-khong-ban", false);
        int maxVl = getConfig().getInt("chong-ban-nham.nguong-vi-pham-toi-da", 30);

        // Khi vượt quá 30 VL -> Thực thi Kick / Ban
        if (!safeMode && vl > maxVl) {
            violations.remove(uuid);
            Bukkit.getScheduler().runTask(this, () -> executeEscalatingPunishment(p, checkName, detail));
        }
    }

    private void executeEscalatingPunishment(Player p, String checkName, String detail) {
        if (!p.isOnline()) return;
        String serverName = getConfig().getString("he-thong.ten-server", "&6&lAMBER SMP");
        String nameKey = p.getName().toLowerCase(Locale.ROOT);
        int offenseCount = punishmentConfig.getInt("players." + nameKey + ".offenses", 0) + 1;
        punishmentConfig.set("players." + nameKey + ".offenses", offenseCount);
        punishmentConfig.set("players." + nameKey + ".lastCheck", checkName);

        int banThreshold = getConfig().getInt("chong-ban-nham.so-lan-tai-pham-ban", 5);
        int banHours = getConfig().getInt("chong-ban-nham.ban-theo-gio", 6);
        int banDays = getConfig().getInt("chong-ban-nham.ban-theo-ngay", 3);
        long now = System.currentTimeMillis();

        // Lần 1 -> 4: KICK CẢNH CÁO
        if (offenseCount < banThreshold) {
            savePunishmentData();
            String kickScreen = color(serverName + " &8- &c&lHỆ THỐNG CHỐNG GIAN LẬN\n\n"
                    + "&fTài khoản: &e" + p.getName() + "\n"
                    + "&fHình thức xử phạt: &c&lKICK CẢNH CÁO &7(Lần " + offenseCount + "/" + banThreshold + ")\n"
                    + "&fLý do vi phạm: &c" + checkName + " &7(" + detail + ")\n\n"
                    + "&e⚠ Lưu ý: Tái phạm đủ " + banThreshold + " lần sẽ bị Khóa Tài Khoản (Ban)!");
            p.kickPlayer(kickScreen);

            broadcastPunishmentBox(
                    serverName,
                    p.getName(),
                    checkName + " &7(" + detail + ")",
                    "&e&lKICK CẢNH CÁO &7(Vi phạm lần &c" + offenseCount + "/" + banThreshold + "&7)"
            );
            return;
        }

        // Lần 5 trở đi: BAN THEO GIỜ -> THEO NGÀY -> VĨNH VIỄN
        long expireAt;
        String durationLabel;
        String unbanDateText;

        if (offenseCount == banThreshold) {
            expireAt = now + (banHours * 3600_000L);
            durationLabel = "BAN " + banHours + " GIỜ";
            unbanDateText = vnDateFormat.format(new Date(expireAt)) + " &7(Còn " + banHours + " giờ)";
        } else if (offenseCount == banThreshold + 1) {
            expireAt = now + (banDays * 86400_000L);
            durationLabel = "BAN " + banDays + " NGÀY";
            unbanDateText = vnDateFormat.format(new Date(expireAt)) + " &7(Còn " + banDays + " ngày)";
        } else {
            expireAt = -1L;
            durationLabel = "BAN VĨNH VIỄN";
            unbanDateText = "&4&lKHÔNG MỞ KHÓA (VĨNH VIỄN)";
        }

        String fullReason = checkName + " (" + detail + ")";
        punishmentConfig.set("players." + nameKey + ".banUntil", expireAt);
        punishmentConfig.set("players." + nameKey + ".banReason", fullReason);
        savePunishmentData();

        String banScreen = color(serverName + " &8- &4&lTHÔNG BÁO KHÓA TÀI KHOẢN\n\n"
                + "&fNgười chơi: &e" + p.getName() + "\n"
                + "&fLý do bị phạt: &c" + fullReason + "\n"
                + "&fHình phạt áp dụng: &4&l" + durationLabel + " &7(Tái phạm lần " + offenseCount + ")\n"
                + "&fThời gian mở khóa (Unban): &a" + unbanDateText + "\n\n"
                + "&7Nếu bạn cho rằng đây là sự nhầm lẫn, vui lòng gửi ticket hỗ trợ tại Discord của Server.");
        p.kickPlayer(banScreen);

        broadcastPunishmentBox(
                serverName,
                p.getName(),
                checkName + " &7(" + detail + ")",
                "&4&l" + durationLabel + " &7(Tái phạm lần thứ &e" + offenseCount + "&7)"
        );
    }

    private void broadcastPunishmentBox(String serverName, String playerName, String reason, String punishmentText) {
        Bukkit.broadcastMessage(color("&8&m-----------------------------------------------------"));
        Bukkit.broadcastMessage(color(" " + serverName + " &8| &c&lTHÔNG BÁO XỬ PHẠT GIAN LẬN"));
        Bukkit.broadcastMessage(color(" &f• Người chơi: &e&l" + playerName));
        Bukkit.broadcastMessage(color(" &f• Lý do vi phạm: &c" + reason));
        Bukkit.broadcastMessage(color(" &f• Hình phạt: " + punishmentText));
        Bukkit.broadcastMessage(color("&8&m-----------------------------------------------------"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLoginCheckBan(PlayerLoginEvent e) {
        String nameKey = e.getPlayer().getName().toLowerCase(Locale.ROOT);
        if (!punishmentConfig.contains("players." + nameKey + ".banUntil")) return;

        long banUntil = punishmentConfig.getLong("players." + nameKey + ".banUntil", 0L);
        long now = System.currentTimeMillis();
        if (banUntil == 0L) return;

        if (banUntil > 0 && now >= banUntil) {
            punishmentConfig.set("players." + nameKey + ".banUntil", 0L);
            savePunishmentData();
            return;
        }

        String serverName = getConfig().getString("he-thong.ten-server", "&6&lAMBER SMP");
        String reason = punishmentConfig.getString("players." + nameKey + ".banReason", "Sử dụng phần mềm gian lận");
        int offenses = punishmentConfig.getInt("players." + nameKey + ".offenses", 5);

        String unbanTimeStr;
        String remainingStr;

        if (banUntil == -1L) {
            unbanTimeStr = "&4&lVĨNH VIỄN (Không mở khóa)";
            remainingStr = "&4&lVô thời hạn";
        } else {
            unbanTimeStr = "&a" + vnDateFormat.format(new Date(banUntil));
            long diffSeconds = Math.max(1L, (banUntil - now) / 1000L);
            long days = diffSeconds / 86400L;
            long hours = (diffSeconds % 86400L) / 3600L;
            long mins = (diffSeconds % 3600L) / 60L;

            StringBuilder sb = new StringBuilder("&e");
            if (days > 0) sb.append(days).append(" ngày ");
            if (hours > 0) sb.append(hours).append(" giờ ");
            sb.append(mins).append(" phút");
            remainingStr = sb.toString();
        }

        String loginBanScreen = color(serverName + " &8- &4&lTÀI KHOẢN ĐANG BỊ KHÓA\n\n"
                + "&fTài khoản: &e" + e.getPlayer().getName() + "\n"
                + "&fLý do bị khóa: &c" + reason + "\n"
                + "&fMức độ vi phạm: &6Tái phạm lần thứ " + offenses + "\n"
                + "&fThời điểm mở khóa (Unban): " + unbanTimeStr + "\n"
                + "&fThời gian còn lại: " + remainingStr + "\n\n"
                + "&7Vui lòng chờ hết thời gian phạt hoặc liên hệ Admin tại Discord " + serverName + "&7.");
        e.disallow(PlayerLoginEvent.Result.KICK_BANNED, loginBanScreen);
    }

    private void sendClickableAlert(Player suspect, String check, String detail, int vl) {
        int maxVl = getConfig().getInt("chong-ban-nham.nguong-vi-pham-toi-da", 30);
        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&6&lAmber AntiCheat&8] &e" + suspect.getName() + " &7vi phạm &c&l" + check
                        + " &8(&f" + detail + "&8) &7[&c" + vl + "/" + maxVl + " VL&7] ");

        TextComponent mainComp = new TextComponent(msg);
        TextComponent btnWatch = new TextComponent(ChatColor.translateAlternateColorCodes('&', "&b&l[Theo dõi]"));
        btnWatch.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/amberac watch " + suspect.getName()));
        btnWatch.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(ChatColor.translateAlternateColorCodes('&', "&aChạm để chuyển sang &bChế độ Khán Giả &avà TP tới &e" + suspect.getName()))));
        mainComp.addExtra(btnWatch);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("amberac.staff") || staff.hasPermission("amberac.admin")) {
                staff.spigot().sendMessage(mainComp);
            }
        }
    }

    public void executeStaffWatch(Player staff, Player target) {
        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(target.getLocation());
        staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã chuyển sang &bChế độ Khán Giả &avà dịch chuyển tới &e" + target.getName() + "&a!"));
    }

    private void logViolationAsync(String playerName, String check, String detail, int vl, int ping, double tps) {
        boolean saveFile = getConfig().getBoolean("he-thong.luu-file-log", true);
        String webhook = getConfig().getString("he-thong.discord-webhook-url", "");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String timeStr = logDateFormat.format(new Date());
            String line = String.format("[%s] %s | %s (%s) | VL:%d | Ping:%dms | TPS:%.1f",
                    timeStr, playerName, check, detail, vl, ping, tps);

            if (saveFile) {
                try {
                    File file = new File(getDataFolder(), "violations.log");
                    try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                        out.println(line);
                    }
                } catch (Exception ignored) {
                }
            }

            if (webhook != null && webhook.startsWith("http")) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(webhook).toURL().openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setDoOutput(true);
                    String json = "{\"content\": \"**[Amber AntiCheat]** `" + playerName + "` vi phạm **" + check
                            + "** (`" + detail + "`) - **" + vl + " VL** (Ping: `" + ping + "ms`)\"}";
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand") || message == null || message.length <= 1) return;
        String brand = new String(message, 1, message.length - 1, StandardCharsets.UTF_8).trim();
        clientBrands.put(player.getUniqueId(), brand);

        if (!getConfig().getBoolean("kiem-tra-client.bat", true) || isExemptFromAnticheat(player)) return;

        String lower = brand.toLowerCase(Locale.ROOT);
        for (String banned : getConfig().getStringList("kiem-tra-client.danh-sach-cam")) {
            if (lower.contains(banned.toLowerCase(Locale.ROOT))) {
                flag(player, "CLIENT-BRAND", "Client cấm: " + brand);
                if (getConfig().getBoolean("kiem-tra-client.tu-dong-kick", false)) {
                    Bukkit.getScheduler().runTask(this, () ->
                            player.kickPlayer(color("&c[Amber AntiCheat] Máy chủ không cho phép sử dụng Client: &e" + brand)));
                }
                break;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        quittingPlayers.remove(uuid);
        lastFlyingPacketMap.put(uuid, System.currentTimeMillis());
        playerLayerZone.put(uuid, computeZone(p.getLocation().getBlockY()));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                applyAllowedChunkRadius(p);
                refreshChunksAround(p, 4);
            }
        }, 8L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        quittingPlayers.add(uuid);
        hiddenPlayersMap.remove(uuid);
        playerLayerZone.remove(uuid);
        clientBrands.remove(uuid);
        logCooldownMap.remove(uuid);
        alertCooldownMap.remove(uuid);
        lastFlyingPacketMap.remove(uuid);
        if (packetCheckListener != null) {
            packetCheckListener.clearPlayerData(uuid);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("acsettings")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("amberac.admin")) {
                p.sendMessage(color("&cBạn không có quyền mở bảng cài đặt Amber AntiCheat!"));
                return true;
            }
            settingsGuiManager.openMainGui(p);
            return true;
        }

        if (cmdName.equals("sus")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("amberac.staff") && !p.hasPermission("amberac.admin")) {
                p.sendMessage(color("&cBạn không có quyền mở danh sách nghi phạm!"));
                return true;
            }
            suspectGuiManager.openSuspectGui(p, 0);
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("watch")) {
            if (!(sender instanceof Player staff)) return true;
            if (!staff.hasPermission("amberac.staff") && !staff.hasPermission("amberac.admin")) return true;
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cNgười chơi &e" + args[1] + " &ckhông online!"));
                return true;
            }
            executeStaffWatch(staff, target);
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("freeze")) {
            if (!sender.hasPermission("amberac.staff") && !sender.hasPermission("amberac.admin")) return true;
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cNgười chơi &e" + args[1] + " &ckhông online!"));
                return true;
            }
            suspectGuiManager.toggleFreeze(sender, target);
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("unban")) {
            if (!sender.hasPermission("amberac.admin")) return true;
            String nameKey = args[1].toLowerCase(Locale.ROOT);
            punishmentConfig.set("players." + nameKey + ".banUntil", 0L);
            punishmentConfig.set("players." + nameKey + ".offenses", 0);
            savePunishmentData();
            sender.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã mở khóa (Unban) và xóa lịch sử tái phạm cho &e" + args[1] + "&a!"));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("amberac.admin")) {
                sender.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cBạn cần quyền &eamberac.admin &cđể nạp lại cấu hình!"));
                return true;
            }
            reloadPluginConfig();
            sender.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã nạp lại cấu hình thành công!"));
            return true;
        }

        sender.sendMessage(color("&8&m----------------------------------------"));
        sender.sendMessage(color(" &6&lAmber AntiCheat &7- &fAMBER SMP (20.0 TPS)"));
        sender.sendMessage(color(" &e/sus &7- Mở bảng danh sách nghi phạm (Đầu Steve)"));
        sender.sendMessage(color(" &e/acsettings &7- Mở bảng bật/tắt 7 Module"));
        sender.sendMessage(color(" &e/amberac watch <tên> &7- Chuyển Khán Giả + TP"));
        sender.sendMessage(color(" &e/amberac freeze <tên> &7- Đóng băng / Mở băng kiểm tra"));
        sender.sendMessage(color(" &e/amberac unban <tên> &7- Mở khóa tài khoản bị Ban"));
        sender.sendMessage(color(" &e/amberac reload &7- Nạp lại tệp config.yml"));
        sender.sendMessage(color("&8&m----------------------------------------"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("amberac.staff") && !sender.hasPermission("amberac.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("watch", "freeze"));
            if (sender.hasPermission("amberac.admin")) {
                subCommands.add("unban");
                subCommands.add("reload");
            }
            return StringUtil.copyPartialMatches(args[0], subCommands, new ArrayList<>());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("watch") || args[0].equalsIgnoreCase("freeze"))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }

        return Collections.emptyList();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
