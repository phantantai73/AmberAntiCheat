package vn.amber.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SuspectGuiManager implements Listener {

    public enum FilterMode {
        ALL("Tất cả (Online + Offline 30p)"),
        ONLINE_ONLY("Chỉ người đang Online"),
        NEW_ACCOUNTS("Chỉ tài khoản mới (< 30 phút)");

        private final String label;
        FilterMode(String label) { this.label = label; }
        public String getLabel() { return label; }
        public FilterMode next() {
            FilterMode[] vals = values();
            return vals[(this.ordinal() + 1) % vals.length];
        }
    }

    public static class SuspectProfile {
        public final UUID uuid;
        public String name;
        public String clientBrand = "Vanilla";
        public final Map<String, Integer> violations = new ConcurrentHashMap<>();
        public String lastDetail = "";
        public long lastFlagTime = System.currentTimeMillis();
        public long quitTimestamp = 0L;
        public int lastPing = 0;
        public long playMinutes = 0;

        public SuspectProfile(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public int getTotalViolations() {
            int sum = 0;
            for (int v : violations.values()) sum += v;
            return sum;
        }

        public int getRiskScore() {
            return getTotalViolations() + (violations.size() * 3) + (isNewAccount() ? 5 : 0);
        }

        public boolean isNewAccount() {
            return playMinutes < 30;
        }

        public boolean isOnline() {
            Player p = Bukkit.getPlayer(uuid);
            return p != null && p.isOnline();
        }
    }

    private final AmberAntiCheat plugin;
    public static final String MAIN_GUI_TITLE = ChatColor.DARK_GRAY + "⚠ " + ChatColor.GOLD + ChatColor.BOLD + "AMBER-AC " + ChatColor.DARK_GRAY + "| " + ChatColor.RED + "Nghi Phạm (/sus)";
    public static final String ACTION_GUI_PREFIX = ChatColor.DARK_GRAY + "🔍 " + ChatColor.GOLD + ChatColor.BOLD + "Kiểm Tra: " + ChatColor.WHITE;

    private final Map<UUID, SuspectProfile> suspectMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> staffPageMap = new ConcurrentHashMap<>();
    private final Map<UUID, FilterMode> staffFilterMap = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> staffDisplayedList = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> staffSelectedSuspect = new ConcurrentHashMap<>();
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> invseeStaffSet = ConcurrentHashMap.newKeySet();

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public SuspectGuiManager(AmberAntiCheat plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpiredProfiles, 1200L, 1200L);
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public void recordViolation(Player p, String checkType, String detail, int totalPlayerVl) {
        UUID uuid = p.getUniqueId();
        SuspectProfile profile = suspectMap.computeIfAbsent(uuid, k -> new SuspectProfile(uuid, p.getName()));
        profile.name = p.getName();
        profile.clientBrand = plugin.getClientBrand(uuid);
        profile.lastDetail = detail;
        profile.lastFlagTime = System.currentTimeMillis();
        profile.quitTimestamp = 0L;
        profile.lastPing = p.getPing();

        try {
            int ticks = p.getStatistic(Statistic.PLAY_ONE_MINUTE);
            profile.playMinutes = ticks / (20L * 60L);
        } catch (Exception ignored) {
        }

        profile.violations.merge(checkType, 1, Integer::sum);
    }

    private void cleanupExpiredProfiles() {
        long now = System.currentTimeMillis();
        long expireMs = 30L * 60L * 1000L;
        suspectMap.entrySet().removeIf(entry -> {
            SuspectProfile prof = entry.getValue();
            return !prof.isOnline() && prof.quitTimestamp > 0 && (now - prof.quitTimestamp) > expireMs;
        });
    }

    public void openSuspectGui(Player staff, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, MAIN_GUI_TITLE);
        renderSuspectList(staff, inv, page);
        staff.openInventory(inv);
    }

    private void renderSuspectList(Player staff, Inventory inv, int page) {
        cleanupExpiredProfiles();
        inv.clear();

        FilterMode mode = staffFilterMap.computeIfAbsent(staff.getUniqueId(), k -> FilterMode.ALL);

        List<SuspectProfile> filtered = suspectMap.values().stream()
                .filter(prof -> prof.getTotalViolations() >= 3 || prof.violations.size() >= 2)
                .filter(prof -> {
                    if (mode == FilterMode.ONLINE_ONLY) return prof.isOnline();
                    if (mode == FilterMode.NEW_ACCOUNTS) return prof.isNewAccount();
                    return true;
                })
                .sorted(Comparator.comparingInt(SuspectProfile::getRiskScore).reversed())
                .collect(Collectors.toList());

        int maxPage = Math.max(0, (filtered.size() - 1) / 45);
        int safePage = Math.max(0, Math.min(page, maxPage));
        staffPageMap.put(staff.getUniqueId(), safePage);

        List<UUID> displayedIds = new ArrayList<>();
        int startIdx = safePage * 45;
        int endIdx = Math.min(startIdx + 45, filtered.size());

        for (int i = startIdx; i < endIdx; i++) {
            SuspectProfile prof = filtered.get(i);
            int slot = i - startIdx;
            inv.setItem(slot, createSuspectHeadItem(prof));
            displayedIds.add(prof.uuid);
        }
        staffDisplayedList.put(staff.getUniqueId(), displayedIds);

        ItemStack glass = createSimpleItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int s = 45; s < 54; s++) inv.setItem(s, glass);

        if (safePage > 0) {
            inv.setItem(45, createSimpleItem(Material.ARROW, "&e« Trang Trước",
                    Collections.singletonList("&7Chuyển về trang " + safePage)));
        }

        inv.setItem(48, createSimpleItem(Material.HOPPER, "&b&lBộ Lọc Danh Sách", Arrays.asList(
                "&7• Chế độ hiện tại: &a" + mode.getLabel(),
                "&7• Tổng số nghi phạm khớp: &e" + filtered.size(),
                "",
                "&e▶ Chạm để đổi chế độ lọc!"
        )));

        inv.setItem(49, createSimpleItem(Material.CLOCK, "&a&lLàm Mới Danh Sách", Arrays.asList(
                "&7• Trang hiện tại: &f" + (safePage + 1) + "/" + (maxPage + 1),
                "",
                "&e▶ Chạm để cập nhật dữ liệu mới nhất!"
        )));

        inv.setItem(50, createSimpleItem(Material.BARRIER, "&c&lXóa Toàn Bộ Danh Sách", Arrays.asList(
                "&7• Dọn sạch toàn bộ hồ sơ nghi phạm",
                "&7  đang lưu trong bộ nhớ tạm.",
                "",
                "&c▶ Chạm để xóa sạch!"
        )));

        if (safePage < maxPage) {
            inv.setItem(53, createSimpleItem(Material.ARROW, "&eTrang Sau »",
                    Collections.singletonList("&7Chuyển sang trang " + (safePage + 2))));
        }
    }

    private ItemStack createSuspectHeadItem(SuspectProfile prof) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.setAmount(Math.max(1, Math.min(64, prof.getTotalViolations())));
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            boolean online = prof.isOnline();
            boolean frozen = frozenPlayers.contains(prof.uuid);

            String statusPrefix = online ? "&a● " : "&7○ ";
            String altBadge = prof.isNewAccount() ? " &d&l[Alt / Mới]" : "";
            String freezeBadge = frozen ? " &b&l[ĐANG ĐÓNG BĂNG]" : "";

            meta.setDisplayName(color(statusPrefix + "&e&l" + prof.name + altBadge + freezeBadge));

            List<String> lore = new ArrayList<>();
            lore.add(color("&8▪ Trạng thái: " + (online ? "&aĐang Online" : "&cĐã thoát (" + getOfflineMinutes(prof) + " phút trước)")));
            lore.add(color("&8▪ Điểm nguy hiểm: &c&l" + prof.getRiskScore() + " điểm &7(Tổng: &f" + prof.getTotalViolations() + " VL&7)"));
            lore.add(color("&8▪ Thời gian chơi: &f" + prof.playMinutes + " phút" + (prof.isNewAccount() ? " &d(Nick mới tạo)" : "")));
            Player onlinePlayer = Bukkit.getPlayer(prof.uuid);
            int currentPing = (online && onlinePlayer != null) ? onlinePlayer.getPing() : prof.lastPing;
            lore.add(color("&8▪ Client: &b" + prof.clientBrand + " &8| &7Ping: &a" + currentPing + "ms"));
            lore.add("");
            lore.add(color("&6&lCác hành vi bị phát hiện:"));
            prof.violations.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(6)
                    .forEach(e -> lore.add(color(" &8- &f" + e.getKey() + ": &c" + e.getValue() + " lần")));

            lore.add("");
            lore.add(color("&7Chi tiết cuối: &f" + prof.lastDetail));
            lore.add(color("&7Lúc: &7" + timeFormat.format(new Date(prof.lastFlagTime))));
            lore.add("");
            lore.add(color("&e▶ Chạm để mở Menu Kiểm Tra (TP / Freeze / Invsee)"));

            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            head.setItemMeta(meta);
        }
        return head;
    }

    private long getOfflineMinutes(SuspectProfile prof) {
        if (prof.quitTimestamp <= 0) return 0;
        return Math.max(1, (System.currentTimeMillis() - prof.quitTimestamp) / 60000L);
    }

    public void openActionSubMenu(Player staff, SuspectProfile prof) {
        staffSelectedSuspect.put(staff.getUniqueId(), prof.uuid);
        Inventory inv = Bukkit.createInventory(null, 27, ACTION_GUI_PREFIX + prof.name);
        renderActionSubMenu(inv, prof);
        staff.openInventory(inv);
    }

    private void renderActionSubMenu(Inventory inv, SuspectProfile prof) {
        ItemStack glass = createSimpleItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        inv.setItem(4, createSuspectHeadItem(prof));

        inv.setItem(10, createSimpleItem(Material.ENDER_EYE, "&a&lTàng Hình & Dịch Chuyển (Watch)", Arrays.asList(
                "&7• Tự động bật &f/vanish &7(Essentials).",
                "&7• Tự động bật chế độ Bay (&fAllowFlight&7).",
                "&7• Dịch chuyển tới vị trí của &e" + prof.name + "&7.",
                "",
                "&e▶ Chạm để theo dõi ngay!"
        )));

        boolean isFrozenNow = frozenPlayers.contains(prof.uuid);
        inv.setItem(12, createSimpleItem(Material.PACKED_ICE,
                isFrozenNow ? "&b&lMở Đóng Băng (Unfreeze)" : "&3&lĐóng Băng Nghi Phạm (Freeze)",
                Arrays.asList(
                        "&7• Trạng thái hiện tại: " + (isFrozenNow ? "&bĐANG ĐÓNG BĂNG ❄" : "&aBình thường"),
                        "&7• Khóa cứng di chuyển, lệnh, vứt đồ & PvP.",
                        "&7• Tự động cảnh báo đỏ nếu tự ý thoát game!",
                        "",
                        "&e▶ Chạm để " + (isFrozenNow ? "&aMỞ BĂNG" : "&bĐÓNG BĂNG") + "!"
                )));

        inv.setItem(14, createSimpleItem(Material.CHEST, "&6&lKiểm Tra Túi Đồ (Invsee)", Arrays.asList(
                "&7• Xem trực tiếp toàn bộ vật phẩm trong",
                "&7  túi đồ của &e" + prof.name + "&7.",
                "",
                "&e▶ Chạm để mở túi đồ!"
        )));

        inv.setItem(16, createSimpleItem(Material.TNT, "&c&lXóa Hồ Sơ (Bắt Nhầm / Đã Xử Lý)", Arrays.asList(
                "&7• Xóa &e" + prof.name + " &7khỏi danh sách &f/sus&7",
                "&7  và gỡ bỏ đóng băng (nếu có).",
                "",
                "&c▶ Chạm để xóa hồ sơ này!"
        )));

        inv.setItem(22, createSimpleItem(Material.ARROW, "&e« Quay Lại Danh Sách /sus",
                Collections.singletonList("&7Trở về bảng danh sách nghi phạm")));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (!(e.getWhoClicked() instanceof Player staff)) return;

        if (invseeStaffSet.contains(staff.getUniqueId()) && !staff.hasPermission("amberac.admin")) {
            e.setCancelled(true);
            return;
        }

        if (title.equals(MAIN_GUI_TITLE)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= 54) return;

            Inventory topInv = e.getView().getTopInventory();
            int currentPage = staffPageMap.getOrDefault(staff.getUniqueId(), 0);

            if (slot < 45) {
                List<UUID> list = staffDisplayedList.get(staff.getUniqueId());
                if (list != null && slot < list.size()) {
                    UUID suspectId = list.get(slot);
                    SuspectProfile prof = suspectMap.get(suspectId);
                    if (prof != null) {
                        staff.playSound(staff.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
                        openActionSubMenu(staff, prof);
                    }
                }
                return;
            }

            switch (slot) {
                case 45 -> {
                    if (currentPage > 0) renderSuspectList(staff, topInv, currentPage - 1);
                }
                case 48 -> {
                    FilterMode currentMode = staffFilterMap.getOrDefault(staff.getUniqueId(), FilterMode.ALL);
                    staffFilterMap.put(staff.getUniqueId(), currentMode.next());
                    staff.playSound(staff.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f);
                    renderSuspectList(staff, topInv, 0);
                }
                case 49 -> {
                    staff.playSound(staff.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                    renderSuspectList(staff, topInv, currentPage);
                }
                case 50 -> {
                    suspectMap.clear();
                    frozenPlayers.clear();
                    staff.playSound(staff.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                    staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã xóa sạch toàn bộ danh sách nghi phạm trong &e/sus&a!"));
                    renderSuspectList(staff, topInv, 0);
                }
                case 53 -> renderSuspectList(staff, topInv, currentPage + 1);
            }
            return;
        }

        if (title.startsWith(ACTION_GUI_PREFIX)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= 27) return;

            UUID targetId = staffSelectedSuspect.get(staff.getUniqueId());
            SuspectProfile prof = targetId != null ? suspectMap.get(targetId) : null;

            if (slot == 22 || prof == null) {
                openSuspectGui(staff, staffPageMap.getOrDefault(staff.getUniqueId(), 0));
                return;
            }

            Player targetOnline = Bukkit.getPlayer(prof.uuid);

            switch (slot) {
                case 10 -> {
                    staff.closeInventory();
                    if (targetOnline == null || !targetOnline.isOnline()) {
                        staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cNgười chơi &e" + prof.name + " &chiện đã thoát game!"));
                        return;
                    }
                    plugin.executeStaffWatch(staff, targetOnline);
                }
                case 12 -> {
                    if (targetOnline == null || !targetOnline.isOnline()) {
                        staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cNgười chơi &e" + prof.name + " &ckhông online để đóng băng!"));
                        return;
                    }
                    toggleFreeze(staff, targetOnline);
                    renderActionSubMenu(e.getView().getTopInventory(), prof);
                }
                case 14 -> {
                    if (targetOnline == null || !targetOnline.isOnline()) {
                        staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cNgười chơi &e" + prof.name + " &cđã thoát game!"));
                        return;
                    }
                    invseeStaffSet.add(staff.getUniqueId());
                    staff.playSound(staff.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
                    staff.openInventory(targetOnline.getInventory());
                }
                case 16 -> {
                    suspectMap.remove(prof.uuid);
                    frozenPlayers.remove(prof.uuid);
                    plugin.clearPlayerViolations(prof.uuid);
                    staff.playSound(staff.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.2f);
                    staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã xóa hồ sơ của &e" + prof.name + " &akhỏi danh sách nghi phạm!"));
                    openSuspectGui(staff, staffPageMap.getOrDefault(staff.getUniqueId(), 0));
                }
            }
        }
    }

    @EventHandler
    public void onInvseeClose(InventoryCloseEvent e) {
        invseeStaffSet.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(MAIN_GUI_TITLE)
                || title.startsWith(ACTION_GUI_PREFIX)
                || invseeStaffSet.contains(e.getWhoClicked().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    public void toggleFreeze(CommandSender staff, Player target) {
        UUID id = target.getUniqueId();

        if (frozenPlayers.remove(id)) {
            target.sendTitle(color("&a&lĐÃ MỞ ĐÓNG BĂNG"), color("&fBạn có thể tiếp tục chơi bình thường!"), 5, 40, 10);
            target.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aBạn đã được Staff kiểm tra xong và gỡ bỏ đóng băng."));
            staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã mở đóng băng cho &e" + target.getName() + "&a."));
            if (staff instanceof Player pStaff) {
                pStaff.playSound(pStaff.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.2f);
            }
            return;
        }

        if (staff instanceof Player pStaff && pStaff.getUniqueId().equals(id)) {
            staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cBạn không thể tự đóng băng chính mình!"));
            return;
        }
        if (target.hasPermission("amberac.admin") || target.hasPermission("amberac.bypass")) {
            staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cKhông thể đóng băng người chơi có quyền Admin/Bypass!"));
            return;
        }

        frozenPlayers.add(id);
        target.closeInventory();
        target.sendTitle(color("&c&lBẠN ĐANG BỊ KIỂM TRA!"), color("&eTuyệt đối KHÔNG thoát game nếu không muốn bị Ban!"), 5, 100, 10);
        target.sendMessage(color("&c&m-----------------------------------------------------"));
        target.sendMessage(color(" &c&l⚠ BẠN ĐÃ BỊ ĐÓNG BĂNG ĐỂ KIỂM TRA GIAN LẬN!"));
        target.sendMessage(color(" &f• Vui lòng đứng yên và làm theo hướng dẫn của Staff trên chat."));
        target.sendMessage(color(" &e• Cảnh báo: &cTự ý thoát game lúc này sẽ bị xử phạt ngay lập tức!"));
        target.sendMessage(color("&c&m-----------------------------------------------------"));
        staff.sendMessage(color("&8[&6&lAmber AntiCheat&8] &bĐã đóng băng &e" + target.getName() + "&b!"));
        if (staff instanceof Player pStaff) {
            pStaff.playSound(pStaff.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!frozenPlayers.contains(p.getUniqueId())) return;

        p.setFallDistance(0f);
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // Khóa di chuyển ngang (X, Z) và nhảy lên (Y > from.Y), nhưng cho phép rơi chạm đất để không bị Vanilla kick Fly
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location locked = from.clone();
            locked.setY(Math.min(from.getY(), to.getY()));
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            e.setTo(locked);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!frozenPlayers.contains(p.getUniqueId())) return;

        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        // Chỉ cho phép nhắn tin riêng trả lời Staff (/msg, /r, /w, /tell)
        if (!msg.startsWith("/msg ") && !msg.startsWith("/r ") && !msg.startsWith("/w ") && !msg.startsWith("/tell ")) {
            e.setCancelled(true);
            p.sendMessage(color("&8[&6&lAmber AntiCheat&8] &cBạn không thể dùng lệnh khi đang bị đóng băng kiểm tra!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenDropItem(PlayerDropItemEvent e) {
        if (frozenPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenInteract(PlayerInteractEvent e) {
        if (frozenPlayers.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenBreak(BlockBreakEvent e) {
        if (frozenPlayers.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenPlace(BlockPlaceEvent e) {
        if (frozenPlayers.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && frozenPlayers.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && frozenPlayers.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onSuspectQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        SuspectProfile prof = suspectMap.get(uuid);
        if (prof != null) {
            prof.quitTimestamp = System.currentTimeMillis();
        }

        if (frozenPlayers.remove(uuid)) {
            String alert = color("&4&l[BÁO ĐỘNG] &e&l" + p.getName() + " &c&lĐÃ THOÁT GAME KHI ĐANG BỊ ĐÓNG BĂNG KIỂM TRA!");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("amberac.staff") || online.hasPermission("amberac.admin")) {
                    online.sendMessage(alert);
                    online.playSound(online.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.2f);
                }
            }
            plugin.getLogger().warning("[FREEZE-QUIT] " + p.getName() + " da thoat game khi dang bi dong bang!");
        }

        staffPageMap.remove(uuid);
        staffFilterMap.remove(uuid);
        staffDisplayedList.remove(uuid);
        staffSelectedSuspect.remove(uuid);
        invseeStaffSet.remove(uuid);
    }

    private ItemStack createSimpleItem(Material mat, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(title));
            List<String> colored = new ArrayList<>();
            for (String line : loreLines) colored.add(color(line));
            meta.setLore(colored);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
