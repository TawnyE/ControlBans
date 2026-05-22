package ret.tawny.controlbans.commands.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.AltService;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AltsGuiManager {

    private final ControlBansPlugin plugin;
    private final AltService altService;
    private final PunishmentService punishmentService;
    private static final int ITEMS_PER_PAGE = 45;

    public AltsGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.altService = plugin.getAltService();
        this.punishmentService = plugin.getPunishmentService();
    }

    public static class AltsHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        private final int page;

        public AltsHolder(OfflinePlayer target, int page) {
            this.target = target;
            this.page = page;
        }

        public OfflinePlayer getTarget() {
            return target;
        }

        public int getPage() {
            return page;
        }
    }

    public void openAltsGui(Player viewer, OfflinePlayer target, int page) {
        int startPage = Math.max(1, page);
        String targetName = getDisplayName(target);

        altService.findAltAccounts(target.getUniqueId())
                .thenCombine(altService.findSharedIps(target.getUniqueId()), (alts, ips) -> {
                    int maxPage = Math.max(1, (int) Math.ceil((double) alts.size() / ITEMS_PER_PAGE));
                    int currentPage = startPage > maxPage ? maxPage : startPage;
                    int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                    int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, alts.size());

                    List<CompletableFuture<ItemStack>> itemFutures = new ArrayList<>();
                    for (int i = startIndex; i < endIndex; i++) {
                        itemFutures.add(createAltItem(alts.get(i)));
                    }

                    CompletableFuture.allOf(itemFutures.toArray(new CompletableFuture[0]))
                            .thenRun(() -> {
                                List<ItemStack> loadedItems = itemFutures.stream().map(CompletableFuture::join).toList();

                                plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                                    Inventory inv = Bukkit.createInventory(new AltsHolder(target, currentPage), 54,
                                            plugin.getLocaleManager().getMessage("gui.alts.title",
                                                    Placeholder.unparsed("player", targetName),
                                                    Placeholder.unparsed("page", String.valueOf(currentPage)),
                                                    Placeholder.unparsed("maxpage", String.valueOf(maxPage))));

                                    if (alts.isEmpty()) {
                                        inv.setItem(22, createNoAltsItem());
                                    } else {
                                        for (int i = 0; i < loadedItems.size(); i++) {
                                            inv.setItem(i, loadedItems.get(i));
                                        }
                                    }

                                    inv.setItem(45, currentPage > 1 ? createNavItem("gui.nav-previous") : createFillerGlass());
                                    inv.setItem(49, createPlayerHeadItem(target, alts.size(), ips));
                                    inv.setItem(53, currentPage < maxPage ? createNavItem("gui.nav-next") : createFillerGlass());

                                    viewer.openInventory(inv);
                                });
                            });
                    return null;
                }).exceptionally(ex -> {
                    viewer.sendMessage(plugin.getLocaleManager().getMessage("errors.database-error"));
                    plugin.getLogger().warning("Failed to fetch alts for GUI: " + ex.getMessage());
                    return null;
                });
    }

    private CompletableFuture<ItemStack> createAltItem(UUID altUuid) {
        OfflinePlayer altPlayer = Bukkit.getOfflinePlayer(altUuid);
        String altName = getDisplayName(altPlayer);

        CompletableFuture<PlayerProfile> profileFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return Bukkit.createProfile(altUuid, altName);
            } catch (Exception e) {
                return null;
            }
        });

        CompletableFuture<List<Punishment>> historyFuture = punishmentService.getPunishmentHistory(altUuid, 100);

        return CompletableFuture.allOf(profileFuture, historyFuture).thenApply(v -> {
            PlayerProfile profile = profileFuture.join();
            List<Punishment> history = historyFuture.join();

            PunishmentView punishments = PunishmentView.fromHistory(history);
            long warnings = history.stream().filter(p -> p.getType() == PunishmentType.WARN).count();

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();

            if (profile != null) {
                meta.setPlayerProfile(profile);
            } else {
                meta.setOwningPlayer(altPlayer);
            }

            boolean isBanned = punishments.hasBan();
            boolean isMuted = punishments.hasMute();
            boolean isVoiceMuted = punishments.hasVoiceMute();

            NamedTextColor statusColor = isBanned
                    ? NamedTextColor.RED
                    : (isMuted || isVoiceMuted) ? NamedTextColor.BLUE : NamedTextColor.GREEN;
            meta.displayName(Component.text(altName).color(statusColor).decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(plugin.getLocaleManager().getMessage("gui.alts.status-label").decoration(TextDecoration.ITALIC, false));
            lore.add(isBanned
                    ? plugin.getLocaleManager().getMessage("gui.alts.alt-banned",
                    Placeholder.unparsed("reason", punishments.banReason())).decoration(TextDecoration.ITALIC, false)
                    : plugin.getLocaleManager().getMessage("gui.alts.alt-not-banned").decoration(TextDecoration.ITALIC, false));
            lore.add(isMuted
                    ? plugin.getLocaleManager().getMessage("gui.alts.alt-muted",
                    Placeholder.unparsed("reason", punishments.muteReason())).decoration(TextDecoration.ITALIC, false)
                    : plugin.getLocaleManager().getMessage("gui.alts.alt-not-muted").decoration(TextDecoration.ITALIC, false));
            lore.add(isVoiceMuted
                    ? plugin.getLocaleManager().getMessage("gui.alts.alt-voice-muted",
                    Placeholder.unparsed("reason", punishments.voiceMuteReason())).decoration(TextDecoration.ITALIC, false)
                    : plugin.getLocaleManager().getMessage("gui.alts.alt-not-voice-muted").decoration(TextDecoration.ITALIC, false));
            lore.add(plugin.getLocaleManager().getMessage("gui.alts.warnings-entry",
                    Placeholder.unparsed("warnings", String.valueOf(warnings))).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(plugin.getLocaleManager().getMessage("gui.alts.uuid-entry",
                    Placeholder.unparsed("uuid", altUuid.toString())).decoration(TextDecoration.ITALIC, false));

            if (isBanned) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.lore(lore);
            head.setItemMeta(meta);
            return head;
        });
    }

    private ItemStack createPlayerHeadItem(OfflinePlayer target, int totalAlts, Set<String> ips) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            meta.setOwningPlayer(target);
        } catch (Exception ignored) {
        }

        meta.displayName(plugin.getLocaleManager().getMessage("gui.alts.player-head-name",
                Placeholder.unparsed("player", getDisplayName(target))).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>(plugin.getLocaleManager().getMessageList("gui.alts.player-head-lore",
                Placeholder.unparsed("alts", String.valueOf(totalAlts))));
        lore.replaceAll(component -> component.decoration(TextDecoration.ITALIC, false));

        if (ips.isEmpty()) {
            lore.add(plugin.getLocaleManager().getMessage("gui.alts.no-ips").decoration(TextDecoration.ITALIC, false));
        } else {
            ips.stream()
                    .limit(10)
                    .map(this::maskIp)
                    .forEach(ip -> lore.add(plugin.getLocaleManager().getMessage("gui.alts.ip-entry",
                            Placeholder.unparsed("ip", ip)).decoration(TextDecoration.ITALIC, false)));
        }

        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private String getDisplayName(OfflinePlayer player) {
        return player.getName() != null
                ? player.getName()
                : plugin.getLocaleManager().getRawMessage("gui.alts.unknown-player");
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
        meta.displayName(plugin.getLocaleManager().getMessage("gui.alts.no-alts-item-name").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(String localeKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.getLocaleManager().getMessage(localeKey).decoration(TextDecoration.ITALIC, false));
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

    private record PunishmentView(String banReason, String muteReason, String voiceMuteReason) {
        private static PunishmentView fromHistory(List<Punishment> history) {
            String ban = history.stream()
                    .filter(p -> p.getType().isBan() && p.isActive() && !p.isExpired())
                    .map(Punishment::getReason)
                    .findFirst()
                    .orElse(null);
            String mute = history.stream()
                    .filter(p -> (p.getType() == PunishmentType.MUTE || p.getType() == PunishmentType.TEMPMUTE) && p.isActive() && !p.isExpired())
                    .map(Punishment::getReason)
                    .findFirst()
                    .orElse(null);
            String voiceMute = history.stream()
                    .filter(p -> (p.getType() == PunishmentType.VOICEMUTE || p.getType() == PunishmentType.TEMPVOICEMUTE) && p.isActive() && !p.isExpired())
                    .map(Punishment::getReason)
                    .findFirst()
                    .orElse(null);
            return new PunishmentView(ban, mute, voiceMute);
        }

        private boolean hasBan() {
            return banReason != null;
        }

        private boolean hasMute() {
            return muteReason != null;
        }

        private boolean hasVoiceMute() {
            return voiceMuteReason != null;
        }
    }
}
