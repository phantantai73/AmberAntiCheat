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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AmberAntiCheat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, PluginMessageListener {

    private final Map<UUID, Integer> violations = new ConcurrentHashMap<>();
    private final Map<UUID, String> clientBrands = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenPlayersMap = new ConcurrentHashMap<>();
    private final Set<UUID> quittingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> playerAboveLayerState = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenTimestampMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> logCooldownMap = new ConcurrentHashMap<>();

    private AsyncOcclusionEngine occlusionEngine;
    private AntiXrayManager antiXrayManager;
    private AntiBlockEspListener blockEspListener;
    private SettingsGuiManager settingsGuiManager;
    private SuspectGuiManager suspectGuiManager;
    private PacketCheckListener packetCheckListener;

    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        saveDefaultConfig();

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

        for (Player p : Bukkit.getOnlinePlayers()) {
            applyAllowedChunkRadius(p);
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

    public boolean isHiddenByYLayer(Player viewer, double targetY, boolean isPlayerTarget) {
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) return false;
        if (isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-nguoi-choi", true)) return false;
        if (!isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-ruong-block", true)) return false;

        double splitY = getConfig().getDouble("hien-tren-an-duoi.moc-do-cao-y", 35.0);
        boolean viewerAbove = playerAboveLayerState.computeIfAbsent(
                viewer.getUniqueId(), k -> viewer.getLocation().getY() > splitY
        );
        boolean targetAbove = targetY > splitY;
        return viewerAbove != targetAbove;
    }

    public boolean isHiddenByYLayer(double viewerY, double targetY, boolean isPlayerTarget) {
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) return false;
        if (isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-nguoi-choi", true)) return false;
        if (!isPlayerTarget && !getConfig().getBoolean("hien-tren-an-duoi.an-ruong-block", true)) return false;

        double splitY = getConfig().getDouble("hien-tren-an-duoi.moc-do-cao-y", 35.0);
        return (viewerY > splitY) != (targetY > splitY);
    }

    public void applyAllowedChunkRadius(Player p) {
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true) || p.hasPermission("amberac.bypass")) {
            p.setSendViewDistance(-1);
            return;
        }
        int splitY = getConfig().getInt("hien-tren-an-duoi.moc-do-cao-y", 35);
        boolean isAbove = playerAboveLayerState.computeIfAbsent(p.getUniqueId(), k -> p.getLocation().getBlockY() > splitY);

        int surfaceChunks = Math.max(2, getConfig().getInt("hien-tren-an-duoi.chunk-cho-phep-tren-mat-dat", 6));
        int undergroundChunks = Math.max(2, getConfig().getInt("hien-tren-an-duoi.chunk-cho-phep-duoi-ham", 2));

        int allowedRadius = isAbove ? surfaceChunks : undergroundChunks;
        if (p.getSendViewDistance() != allowedRadius) {
            p.setSendViewDistance(allowedRadius);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrossYThreshold(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        if (!getConfig().getBoolean("hien-tren-an-duoi.bat", true)) return;

        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        int splitY = getConfig().getInt("hien-tren-an-duoi.moc-do-cao-y", 35);
        int hysteresis = getConfig().getInt("hien-tren-an-duoi.do-tre-do-cao-y", 2);
        int currentY = e.getTo().getBlockY();

        boolean wasAbove = playerAboveLayerState.computeIfAbsent(uuid, k -> e.getFrom().getBlockY() > splitY);
        boolean switched = false;

        if (wasAbove && currentY <= (splitY - hysteresis)) {
            playerAboveLayerState.put(uuid, false);
            switched = true;
        } else if (!wasAbove && currentY >= (splitY + hysteresis)) {
            playerAboveLayerState.put(uuid, true);
            switched = true;
        }

        if (switched) {
            applyAllowedChunkRadius(p);
            if (blockEspListener != null) {
                blockEspListener.clearPlayerCache(uuid);
            }
        }
    }

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

            if (viewer.hasPermission("amberac.bypass")
                    || viewer.getGameMode() == GameMode.SPECTATOR
                    || viewer.getGameMode() == GameMode.CREATIVE) {
                for (UUID targetId : new HashSet<>(hiddenSet)) {
                    Player t = Bukkit.getPlayer(targetId);
                    if (t != null) showEntityModelOnly(viewer, t);
                    hiddenSet.remove(targetId);
                }
                continue;
            }

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
                        if (dist <= 10.0 && !isCompletelyOccluded(viewer, target)) {
                            occludedNow = false;
                        } else {
                            occludedNow = true;
                            lastSeenTimestampMap.remove(pairKey);
                        }
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
                    if (now - lastSeen >= 1500L) {
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

    public void flag(Player p, String checkName, String detail) {
        if (!getConfig().getBoolean("he-thong.anticheat", true)) return;
        if (p.hasPermission("amberac.bypass")) return;

        int maxPing = getConfig().getInt("chong-ban-nham.ping-toi-da-kiem-tra", 280);
        double minTps = getConfig().getDouble("chong-ban-nham.tps-toi-thieu-kiem-tra", 17.5);
        double currentTps = Bukkit.getTPS()[0];

        if (p.getPing() > maxPing || currentTps < minTps) return;

        UUID uuid = p.getUniqueId();
        int vl = violations.merge(uuid, 1, Integer::sum);

        if (suspectGuiManager != null) {
            suspectGuiManager.recordViolation(p, checkName, detail, vl);
        }

        if (getConfig().getBoolean("he-thong.thong-bao-staff", true)) {
            sendClickableAlert(p, checkName, detail, vl);
        }

        long now = System.currentTimeMillis();
        if (now - logCooldownMap.getOrDefault(uuid, 0L) >= 4000L) {
            logCooldownMap.put(uuid, now);
            logViolationAsync(p.getName(), checkName, detail, vl, p.getPing(), currentTps);
        }

        boolean safeMode = getConfig().getBoolean("chong-ban-nham.che-do-an-toan-khong-ban", true);
        int maxVl = getConfig().getInt("chong-ban-nham.nguong-vi-pham-toi-da", 25);

        if (!safeMode && vl >= maxVl) {
            violations.remove(uuid);
            String cmd = getConfig().getString("chong-ban-nham.lenh-xu-phat", "kick %player% [Amber AntiCheat] Gian lan (%check%)")
                    .replace("%player%", p.getName())
                    .replace("%check%", checkName);
            Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
    }

    private void sendClickableAlert(Player suspect, String check, String detail, int vl) {
        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&6&lAmber AntiCheat&8] &e" + suspect.getName() + " &7vi phạm &c&l" + check
                        + " &8(&f" + detail + "&8) &7[&c" + vl + " VL&7] ");

        TextComponent mainComp = new TextComponent(msg);
        TextComponent btnWatch = new TextComponent(ChatColor.translateAlternateColorCodes('&', "&b&l[Theo dõi]"));
        btnWatch.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ac watch " + suspect.getName()));
        btnWatch.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(ChatColor.translateAlternateColorCodes('&', "&aChạm để tự bật &f/vanish &avà dịch chuyển tới &e" + suspect.getName()))));
        mainComp.addExtra(btnWatch);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("amberac.staff") || staff.hasPermission("amberac.admin")) {
                staff.spigot().sendMessage(mainComp);
            }
        }
    }

    public void executeStaffWatch(Player staff, Player target) {
        if (Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vanish " + staff.getName() + " on");
        }
        staff.setAllowFlight(true);
        staff.setFlying(true);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (staff.isOnline() && target.isOnline()) {
                staff.teleport(target.getLocation());
                staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã bật &fVanish + Fly &avà dịch chuyển tới &e" + target.getName() + "&a!"));
            }
        }, 1L);
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

        if (!getConfig().getBoolean("kiem-tra-client.bat", true) || player.hasPermission("amberac.bypass")) return;

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
        quittingPlayers.remove(p.getUniqueId());
        int splitY = getConfig().getInt("hien-tren-an-duoi.moc-do-cao-y", 35);
        playerAboveLayerState.put(p.getUniqueId(), p.getLocation().getBlockY() > splitY);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) applyAllowedChunkRadius(p);
        }, 5L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        quittingPlayers.add(uuid);
        hiddenPlayersMap.remove(uuid);
        playerAboveLayerState.remove(uuid);
        clientBrands.remove(uuid);
        logCooldownMap.remove(uuid);
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
        sender.sendMessage(color(" &e/ac watch <tên> &7- Tự bật Vanish + Fly + TP"));
        sender.sendMessage(color(" &e/ac freeze <tên> &7- Đóng băng / Mở băng kiểm tra"));
        sender.sendMessage(color(" &e/ac reload &7- Nạp lại tệp config.yml"));
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
