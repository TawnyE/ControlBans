package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.AltService;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AltsGuiManager {

    private final ControlBansPlugin plugin;
    private final AltService altService;
    private final PunishmentService punishmentService;
    private final Map<UUID, Integer> openInventories = new ConcurrentHashMap<>();
    private static final int ITEMS_PER_PAGE = 45;

    public AltsGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.altService = plugin.getAltService();
        this.punishmentService = plugin.getPunishmentService();
    }

    public void openAltsGui(Player viewer, OfflinePlayer target, int page) {
        altService.findAltAccounts(target.getUniqueId())
                .thenCombine(altService.findSharedIps(target.getUniqueId()), (alts, ips) -> {
                    // Run GUI logic on the main server thread
                    plugin.getSchedulerAdapter().runTask(() -> {
                        int maxPage = (int) Math.ceil((double) alts.size() / ITEMS_PER_PAGE);
                        if (maxPage == 0) maxPage = 1;

                        String titleStr = String.format("Alts: %s (%d/%d)", target.getName(), page, maxPage);
                        // Use Component for inventory title
                        Inventory inv = Bukkit.createInventory(null, 54, Component.text(titleStr));

                        if (alts.isEmpty()) {
                            inv.setItem(22, createNoAltsItem());
                        } else {
                            List<CompletableFuture<ItemStack>> itemFutures = new ArrayList<>();
                            int startIndex = (page - 1) * ITEMS_PER_PAGE;
                            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, alts.size());

                            for (int i = startIndex; i < endIndex; i++) {
                                UUID altUuid = alts.get(i);
                                itemFutures.add(createAltItem(altUuid));
                            }

                            CompletableFuture.allOf(itemFutures.toArray(new CompletableFuture[0]))
                                    .thenRun(() -> plugin.getSchedulerAdapter().runTask(() -> {
                                        for (int i = 0; i < itemFutures.size(); i++) {
                                            inv.setItem(i, itemFutures.get(i).join());
                                        }
                                    }));
                        }

                        inv.setItem(45, (page > 1) ? createNavItem("« Previous Page") : createFillerGlass());
                        inv.setItem(49, createPlayerHeadItem(target, alts.size(), ips));
                        inv.setItem(53, (page < maxPage) ? createNavItem("Next Page »") : createFillerGlass());

                        viewer.openInventory(inv);
                        openInventories.put(viewer.getUniqueId(), page);
                    });
                    return null;
                }).exceptionally(ex -> {
                    viewer.sendMessage(Component.text("An error occurred while fetching alt accounts.", NamedTextColor.RED));
                    plugin.getLogger().warning("Failed to fetch alts for GUI: " + ex.getMessage());
                    return null;
                });
    }

    private CompletableFuture<ItemStack> createAltItem(UUID altUuid) {
        OfflinePlayer altPlayer = Bukkit.getOfflinePlayer(altUuid);
        String altName = altPlayer.getName() != null ? altPlayer.getName() : "Unknown";

        CompletableFuture<Optional<Punishment>> banFuture = punishmentService.getActiveBan(altUuid);
        CompletableFuture<Optional<Punishment>> muteFuture = punishmentService.getActiveMute(altUuid);
        CompletableFuture<List<Punishment>> historyFuture = punishmentService.getPunishmentHistory(altUuid, 200);

        return CompletableFuture.allOf(banFuture, muteFuture, historyFuture).thenApply(v -> {
            Optional<Punishment> ban = banFuture.join();
            Optional<Punishment> mute = muteFuture.join();
            long warnings = historyFuture.join().stream().filter(p -> p.getType() == PunishmentType.WARN).count();

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(altPlayer);

            boolean isBanned = ban.isPresent();
            boolean isMuted = mute.isPresent();

            NamedTextColor statusColor = (isBanned) ? NamedTextColor.RED : (isMuted) ? NamedTextColor.BLUE : NamedTextColor.GREEN;
            // Use displayName(Component) instead of setDisplayName(String)
            meta.displayName(Component.text(altName).color(statusColor).decoration(TextDecoration.ITALIC, false));

            // Use List<Component> for lore instead of List<String>
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Status:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

            if (isBanned) {
                lore.add(Component.text(" - Banned: Yes (", NamedTextColor.RED)
                        .append(Component.text(ban.get().getReason(), NamedTextColor.WHITE))
                        .append(Component.text(")", NamedTextColor.RED))
                        .decoration(TextDecoration.ITALIC, false));
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                lore.add(Component.text(" - Banned: No", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            }

            if (isMuted) {
                lore.add(Component.text(" - Muted: Yes (", NamedTextColor.BLUE)
                        .append(Component.text(mute.get().getReason(), NamedTextColor.WHITE))
                        .append(Component.text(")", NamedTextColor.BLUE))
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text(" - Muted: No", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            }

            lore.add(Component.text(" - Warnings: " + warnings, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("UUID: ", NamedTextColor.GRAY)
                    .append(Component.text(altUuid.toString(), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            head.setItemMeta(meta);
            return head;
        });
    }

    private ItemStack createPlayerHeadItem(OfflinePlayer target, int totalAlts, Set<String> ips) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);

        meta.displayName(Component.text("Alt Check For: ", NamedTextColor.GREEN)
                .append(Component.text(Objects.requireNonNull(target.getName()), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Found ", NamedTextColor.GRAY)
                .append(Component.text(totalAlts, NamedTextColor.YELLOW))
                .append(Component.text(" alt(s).", NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Shared IPs:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        if (ips.isEmpty()) {
            lore.add(Component.text("- None found", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        } else {
            ips.stream().map(this::maskIp).forEach(ip ->
                    lore.add(Component.text("- " + ip, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
        }

        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "?.?.?.?";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".X.X";
        }
        return "Invalid IP";
    }

    private ItemStack createNoAltsItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No Alternate Accounts Found", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(String name) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFillerGlass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    public Map<UUID, Integer> getOpenInventories() {
        return openInventories;
    }
}