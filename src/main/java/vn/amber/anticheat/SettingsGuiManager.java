package vn.amber.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsGuiManager implements Listener {

    private final AmberAntiCheat plugin;
    public static final String GUI_TITLE = ChatColor.DARK_GRAY + "⚙ " + ChatColor.GOLD + ChatColor.BOLD + "Amber AntiCheat " + ChatColor.DARK_GRAY + "| " + ChatColor.WHITE + "Cài Đặt";

    private final Map<Integer, String> slotToConfigPath = new HashMap<>();

    public SettingsGuiManager(AmberAntiCheat plugin) {
        this.plugin = plugin;

        slotToConfigPath.put(10, "he-thong.anticheat");
        slotToConfigPath.put(11, "anti-xray.bat");
        slotToConfigPath.put(12, "anti-player-esp.bat");
        slotToConfigPath.put(13, "anti-block-esp.bat");
        slotToConfigPath.put(14, "hien-tren-an-duoi.bat");
        slotToConfigPath.put(15, "kiem-tra-client.bat");
        slotToConfigPath.put(16, "chong-ban-nham.che-do-an-toan-khong-ban");
        slotToConfigPath.put(21, "he-thong.thong-bao-staff");
    }

    public void openMainGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
        renderItems(inv);
        player.openInventory(inv);
    }

    private void renderItems(Inventory inv) {
        ItemStack filler = createDecorationGlass();
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(10, createSwitchItem(
                Material.DIAMOND_SWORD,
                "AntiCheat (PvP, Movement, Freecam)",
                "he-thong.anticheat",
                Arrays.asList(
                        "&7• Bắt Reach, KillAura, Aim-GCD, Velocity.",
                        "&7• Bắt Timer, Fly, Speed, Scaffold.",
                        "&7• Chặn Freecam mở rương/đập đặt xuyên tường.",
                        "&8Mức tải: &aSiêu nhẹ (< 0.2ms MSPT)"
                )
        ));

        inv.setItem(11, createSwitchItem(
                Material.NETHERITE_PICKAXE,
                "Anti-Xray (1 Mode Tối Ưu)",
                "anti-xray.bat",
                Arrays.asList(
                        "&7• Biến quặng chôn kín 6 mặt (Y <= 64) thành Đá.",
                        "&7• Tự mở khóa đúng 6 mặt sát vách khi đào.",
                        "&8Mức tải: &aNhẹ (Chạy trên luồng Netty)"
                )
        ));

        inv.setItem(12, createSwitchItem(
                Material.ENDER_EYE,
                "Anti-PlayerESP (Xuyên Tường)",
                "anti-player-esp.bat",
                Arrays.asList(
                        "&7• Ẩn mô hình 3D người chơi sau tường (> 8m)",
                        "&7  hoặc đứng quá xa (> 64m).",
                        "&7• Giữ nguyên tên trên bảng Tab + Trễ ẩn 1.5s.",
                        "&8Mức tải: &aNhẹ (Dò tia Async trên BitSet)"
                )
        ));

        inv.setItem(13, createSwitchItem(
                Material.CHEST,
                "Anti-BlockESP & Anti-Base",
                "anti-block-esp.bat",
                Arrays.asList(
                        "&7• Mặc định biến Rương, Shulker, Spawner, Phễu,",
                        "&7  Lò nung, Giường, Đe thành Đá khi tải chunk.",
                        "&7• Chỉ mở khóa khi <= 24m + không bị tường che.",
                        "&8Mức tải: &eTrung bình - Nhẹ (Giới hạn 64 khối)"
                )
        ));

        inv.setItem(14, createSwitchItem(
                Material.RECOVERY_COMPASS,
                "Phân Tầng Y=35 (2 Chiều) & Khóa Chunk",
                "hien-tren-an-duoi.bat",
                Arrays.asList(
                        "&7• Ở trên Y=35: Ẩn sạch người & base dưới hầm.",
                        "&7• Ở dưới Y=35: Ẩn sạch người & base mặt đất.",
                        "&7• Khóa tầm nhìn: Mặt đất 6 chunks, Hầm 2 chunks.",
                        "&7• Chặn Shift+F3 Pie-Chart, Âm thanh & Hạt.",
                        "&8Mức tải: &a0.0ms RayTrace (Chạy độc lập)"
                )
        ));

        inv.setItem(15, createSwitchItem(
                Material.SPYGLASS,
                "Kiểm Tra Client (Brand Checker)",
                "kiem-tra-client.bat",
                Arrays.asList(
                        "&7• Đọc nhãn Client ngay khi người chơi kết nối.",
                        "&7• Tự động cảnh báo các bản mod/hack cấm.",
                        "&8Mức tải: &a0.0ms"
                )
        ));

        inv.setItem(16, createSwitchItem(
                Material.TOTEM_OF_UNDYING,
                "Chế Độ An Toàn (Chống Ban Nhầm)",
                "chong-ban-nham.che-do-an-toan-khong-ban",
                Arrays.asList(
                        "&7• Khi BẬT: Không tự động Ban/Kick,",
                        "&7  chỉ hủy thao tác hack và lưu vào &e/sus&7.",
                        "&8Khuyên dùng: &aNên BẬT cho SMP đông người"
                )
        ));

        inv.setItem(21, createSwitchItem(
                Material.BELL,
                "Thông Báo Cảnh Báo Staff",
                "he-thong.thong-bao-staff",
                Arrays.asList(
                        "&7• Bật/tắt dòng cảnh báo vi phạm trên chat",
                        "&7  kèm nút chạm &b[Theo dõi]&7 nhanh."
                )
        ));

        inv.setItem(22, createActionItem(
                Material.NETHER_STAR,
                "&b&lTải Lại Cấu Hình (Reload Config)",
                Arrays.asList(
                        "&7• Đọc lại tệp &fconfig.yml&7 từ ổ cứng.",
                        "&7• Cập nhật lại bảng mã Block & Chunk.",
                        "",
                        "&e▶ Chạm để nạp lại ngay lập tức!"
                )
        ));

        inv.setItem(23, createActionItem(
                Material.FEATHER,
                "&a&lChế Độ Nhẹ Server (Ưu Tiên 20 TPS)",
                Arrays.asList(
                        "&7• Tự động TẮT: &fAnti-Xray, PlayerESP, BlockESP&7.",
                        "&7• Giữ nguyên: &aAntiCheat PvP + Phân Tầng Y=35&7.",
                        "",
                        "&e▶ Chạm để kích hoạt Chế Độ Nhẹ!"
                )
        ));
    }

    private ItemStack createDecorationGlass() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSwitchItem(Material icon, String title, String configPath, List<String> description) {
        boolean enabled = plugin.getConfig().getBoolean(configPath, true);
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String statusBadge = enabled ? "&a&l[ĐANG BẬT]" : "&c&l[ĐANG TẮT]";
            meta.setDisplayName(color(statusBadge + " &f" + title));

            List<String> lore = new ArrayList<>();
            lore.add(color("&8▪ Trạng thái: " + (enabled ? "&aHoạt động ✔" : "&cĐã vô hiệu hóa ✘")));
            lore.add("");
            for (String line : description) {
                lore.add(color(line));
            }
            lore.add("");
            lore.add(color(enabled ? "&e▶ Chạm để &cTẮT &echức năng này" : "&e▶ Chạm để &aBẬT &echức năng này"));

            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createActionItem(Material icon, String title, List<String> description) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(title));
            List<String> lore = new ArrayList<>();
            for (String line : description) {
                lore.add(color(line));
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals(GUI_TITLE)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("amberac.admin")) {
            player.closeInventory();
            return;
        }

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        Inventory topInv = e.getView().getTopInventory();

        if (slotToConfigPath.containsKey(slot)) {
            String path = slotToConfigPath.get(slot);
            boolean current = plugin.getConfig().getBoolean(path, true);
            boolean nextState = !current;

            plugin.getConfig().set(path, nextState);
            plugin.saveConfig();
            syncModuleStateImmediately(path, nextState);

            player.playSound(player.getLocation(),
                    nextState ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS,
                    1.0f, nextState ? 1.5f : 0.8f);
            renderItems(topInv);
            return;
        }

        if (slot == 22) {
            plugin.reloadPluginConfig();
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            player.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã tải lại cấu hình và đồng bộ tầm nhìn thành công!"));
            renderItems(topInv);
            return;
        }

        if (slot == 23) {
            plugin.getConfig().set("anti-xray.bat", false);
            plugin.getConfig().set("anti-player-esp.bat", false);
            plugin.getConfig().set("anti-block-esp.bat", false);
            plugin.getConfig().set("he-thong.anticheat", true);
            plugin.getConfig().set("hien-tren-an-duoi.bat", true);
            plugin.saveConfig();

            plugin.reloadPluginConfig();
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            player.sendMessage(color("&8[&6&lAmber AntiCheat&8] &aĐã kích hoạt &e&lChế Độ Nhẹ Server&a!"));
            renderItems(topInv);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(GUI_TITLE)) {
            e.setCancelled(true);
        }
    }

    private void syncModuleStateImmediately(String path, boolean enabled) {
        switch (path) {
            case "anti-xray.bat" -> {
                if (plugin.getAntiXrayManager() != null) {
                    plugin.getAntiXrayManager().reloadStateMappings();
                }
            }
            case "anti-player-esp.bat" -> {
                if (!enabled && !plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)) {
                    plugin.revealAllHiddenPlayers();
                }
            }
            case "anti-block-esp.bat" -> {
                if (plugin.getAntiXrayManager() != null) {
                    plugin.getAntiXrayManager().reloadStateMappings();
                }
                if (!enabled && !plugin.getConfig().getBoolean("hien-tren-an-duoi.bat", true)
                        && plugin.getBlockEspListener() != null) {
                    plugin.getBlockEspListener().restoreAllHiddenBlocks();
                }
            }
            case "hien-tren-an-duoi.bat" -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.applyAllowedChunkRadius(p);
                }
                if (!enabled) {
                    if (!plugin.getConfig().getBoolean("anti-player-esp.bat", true)) {
                        plugin.revealAllHiddenPlayers();
                    }
                    if (!plugin.getConfig().getBoolean("anti-block-esp.bat", true)
                            && plugin.getBlockEspListener() != null) {
                        plugin.getBlockEspListener().restoreAllHiddenBlocks();
                    }
                }
            }
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
