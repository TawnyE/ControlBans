package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.AppealService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AppealsGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;
    private static final int ITEMS_PER_PAGE = 45;

    public AppealsGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    // ------------------------------------------------------------------
    // Holders
    // ------------------------------------------------------------------

    public static class AppealsListHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final int page;
        private final Map<Integer, Integer> slotAppealIds = new HashMap<>();

        public AppealsListHolder(UUID viewerUuid, int page) {
            this.viewerUuid = viewerUuid;
            this.page = page;
        }

        public int getPage() { return page; }
        public void setAppealAt(int slot, int appealId) { slotAppealIds.put(slot, appealId); }
        public Integer getAppealIdAt(int slot) { return slotAppealIds.get(slot); }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }
        @Override
        public MenuType getMenuType() { return MenuType.APPEALS_LIST; }
    }

    public static class AppealDetailHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final AppealService.Appeal appeal;
        private final String appealerName;
        private final int returnPage;

        public AppealDetailHolder(UUID viewerUuid, AppealService.Appeal appeal, String appealerName, int returnPage) {
            this.viewerUuid = viewerUuid;
            this.appeal = appeal;
            this.appealerName = appealerName;
            this.returnPage = returnPage;
        }

        public AppealService.Appeal getAppeal() { return appeal; }
        public String getAppealerName() { return appealerName; }
        public int getReturnPage() { return returnPage; }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }
        @Override
        public MenuType getMenuType() { return MenuType.APPEAL_DETAIL; }
    }

    // ------------------------------------------------------------------
    // List view
    // ------------------------------------------------------------------

    public void openAppealsGui(Player viewer, int page) {
        plugin.getAppealService().getAppeals().thenCompose(appeals -> {
            List<AppealService.Appeal> sorted = new ArrayList<>(appeals);
            sorted.sort(Comparator
                    .comparingInt((AppealService.Appeal a) -> AppealService.STATUS_PENDING.equalsIgnoreCase(a.status()) ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(AppealService.Appeal::createdAt).reversed()));

            // Resolve display names for the appellants in one async pass.
            Map<UUID, CompletableFuture<String>> nameFutures = new HashMap<>();
            for (AppealService.Appeal a : sorted) {
                if (a.targetUuid() != null && !nameFutures.containsKey(a.targetUuid())) {
                    nameFutures.put(a.targetUuid(), plugin.getStorage().getLastKnownName(a.targetUuid()));
                }
            }
            return CompletableFuture.allOf(nameFutures.values().toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        Map<UUID, String> names = new HashMap<>();
                        nameFutures.forEach((uuid, future) -> names.put(uuid, future.getNow("Unknown")));
                        return new Object[]{sorted, names};
                    });
        }).thenAccept(data -> {
            @SuppressWarnings("unchecked")
            List<AppealService.Appeal> sorted = (List<AppealService.Appeal>) data[0];
            @SuppressWarnings("unchecked")
            Map<UUID, String> names = (Map<UUID, String>) data[1];

            int maxPage = Math.max(1, (int) Math.ceil((double) sorted.size() / ITEMS_PER_PAGE));
            int currentPage = Math.min(Math.max(1, page), maxPage);

            plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                Component title = locale.getMessage("gui.appeals.title",
                        Placeholder.unparsed("page", String.valueOf(currentPage)),
                        Placeholder.unparsed("maxpage", String.valueOf(maxPage)));

                AppealsListHolder holder = new AppealsListHolder(viewer.getUniqueId(), currentPage);
                Inventory inv = Bukkit.createInventory(holder, 54, title);

                int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, sorted.size());

                for (int i = startIndex; i < endIndex; i++) {
                    AppealService.Appeal appeal = sorted.get(i);
                    String name = appeal.targetUuid() != null ? names.getOrDefault(appeal.targetUuid(), "Unknown") : "Unknown";
                    int slot = i - startIndex;
                    inv.setItem(slot, createAppealItem(appeal, name));
                    holder.setAppealAt(slot, appeal.id());
                }

                if (sorted.isEmpty()) {
                    inv.setItem(22, createInfolessItem(Material.BARRIER, "gui.appeals.empty"));
                }

                inv.setItem(45, currentPage > 1 ? createNavItem("gui.nav-previous") : createFiller());
                inv.setItem(53, currentPage < maxPage ? createNavItem("gui.nav-next") : createFiller());

                viewer.openInventory(inv);
            });
        });
    }

    private ItemStack createAppealItem(AppealService.Appeal appeal, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                if (appeal.targetUuid() != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(appeal.targetUuid()));
                }
            } catch (Exception ignored) {}

            meta.displayName(locale.getMessage("gui.appeals.item-name",
                            Placeholder.unparsed("player", name))
                    .decoration(TextDecoration.ITALIC, false));

            Component statusComponent = statusComponent(appeal.status());

            String message = appeal.message() == null ? "" : appeal.message();
            String shortMessage = message.length() > 40 ? message.substring(0, 40) + "..." : message;

            TagResolver placeholders = TagResolver.builder()
                    .resolver(Placeholder.unparsed("player", name))
                    .resolver(Placeholder.component("status", statusComponent))
                    .resolver(Placeholder.unparsed("message", shortMessage))
                    .resolver(Placeholder.unparsed("date", TimeUtil.formatDate(appeal.createdAt())))
                    .resolver(Placeholder.unparsed("id", String.valueOf(appeal.id())))
                    .build();

            meta.lore(locale.getMessageList("gui.appeals.item-lore", placeholders));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        return head;
    }

    private Component statusComponent(String status) {
        if (AppealService.STATUS_APPROVED.equalsIgnoreCase(status)) {
            return locale.getMessage("gui.appeals.status-approved");
        }
        if (AppealService.STATUS_DENIED.equalsIgnoreCase(status)) {
            return locale.getMessage("gui.appeals.status-denied");
        }
        return locale.getMessage("gui.appeals.status-pending");
    }

    // ------------------------------------------------------------------
    // Detail view
    // ------------------------------------------------------------------

    public void openAppealDetail(Player viewer, AppealService.Appeal appeal, String appealerName, int returnPage) {
        CompletableFuture<Optional<Punishment>> muteFuture = appeal.targetUuid() != null
                ? plugin.getPunishmentService().getActiveMute(appeal.targetUuid())
                : CompletableFuture.completedFuture(Optional.empty());

        muteFuture.thenAccept(muteOpt -> plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
            AppealDetailHolder holder = new AppealDetailHolder(viewer.getUniqueId(), appeal, appealerName, returnPage);
            Component title = locale.getMessage("gui.appeals.detail-title",
                    Placeholder.unparsed("player", appealerName));
            Inventory inv = Bukkit.createInventory(holder, 36, title);

            inv.setItem(11, createPersonHead(appeal.targetUuid(), appealerName));
            inv.setItem(13, createInfoBook(appeal, muteOpt.orElse(null)));
            inv.setItem(20, createActionItem(Material.LIME_DYE, "gui.appeals.action-approve"));
            inv.setItem(24, createActionItem(Material.RED_DYE, "gui.appeals.action-deny"));
            inv.setItem(18, createNavItem("gui.appeals.back"));

            viewer.openInventory(inv);
        }));
    }

    private ItemStack createPersonHead(UUID uuid, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                if (uuid != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                }
            } catch (Exception ignored) {}
            meta.displayName(locale.getMessage("gui.appeals.head-name",
                    Placeholder.unparsed("player", name)).decoration(TextDecoration.ITALIC, false));
        }
        return head;
    }

    private ItemStack createInfoBook(AppealService.Appeal appeal, Punishment mute) {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(locale.getMessage("gui.appeals.info-name").decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(locale.getMessage("gui.appeals.info-id",
                Placeholder.unparsed("id", String.valueOf(appeal.id()))));
        if (mute != null) {
            lore.add(locale.getMessage("gui.appeals.info-mute-reason",
                    Placeholder.unparsed("reason", mute.getReason() != null ? mute.getReason() : "-"),
                    Placeholder.unparsed("id", mute.getPunishmentId() != null ? mute.getPunishmentId() : "-")));
        } else {
            lore.add(locale.getMessage("gui.appeals.info-mute-none"));
        }
        String message = appeal.message() == null ? "" : appeal.message();
        if (message.isEmpty()) {
            lore.add(locale.getMessage("gui.appeals.info-message-line", Placeholder.unparsed("message", "-")));
        } else {
            for (String line : wrap(message, 30)) {
                lore.add(locale.getMessage("gui.appeals.info-message-line", Placeholder.unparsed("message", line)));
            }
        }
        lore.add(locale.getMessage("gui.appeals.info-date",
                Placeholder.unparsed("date", TimeUtil.formatDate(appeal.createdAt()))));
        lore.add(locale.getMessage("gui.appeals.info-status",
                Placeholder.unparsed("status", displayStatus(appeal.status()))));

        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        book.setItemMeta(meta);
        return book;
    }

    private String displayStatus(String status) {
        if (status == null) return AppealService.STATUS_PENDING;
        return status.toUpperCase();
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            String remaining = paragraph;
            while (remaining.length() > width) {
                int cut = remaining.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                lines.add(remaining.substring(0, cut));
                remaining = remaining.substring(cut).trim();
            }
            if (!remaining.isEmpty() || lines.isEmpty()) lines.add(remaining);
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Click handlers (invoked from GuiListener)
    // ------------------------------------------------------------------

    public void handleListClick(Player player, AppealsListHolder holder, int slot, ItemStack item) {
        if (item == null) return;
        if (slot == 45 && item.getType() == Material.ARROW) {
            openAppealsGui(player, holder.getPage() - 1);
            return;
        }
        if (slot == 53 && item.getType() == Material.ARROW) {
            openAppealsGui(player, holder.getPage() + 1);
            return;
        }
        if (slot >= 0 && slot < 45 && item.getType() == Material.PLAYER_HEAD) {
            Integer appealId = holder.getAppealIdAt(slot);
            if (appealId == null) return;
            plugin.getAppealService().getAppeals().thenAccept(appeals ->
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                        AppealService.Appeal found = null;
                        for (AppealService.Appeal a : appeals) {
                            if (a.id() == appealId) {
                                found = a;
                                break;
                            }
                        }
                        if (found == null) {
                            player.sendMessage(locale.getMessage("gui.appeals.appeal-not-found"));
                            openAppealsGui(player, holder.getPage());
                            return;
                        }
                        final AppealService.Appeal appeal = found;
                        CompletableFuture<String> nameFuture = appeal.targetUuid() != null
                                ? plugin.getStorage().getLastKnownName(appeal.targetUuid())
                                : CompletableFuture.completedFuture("Unknown");
                        nameFuture.thenAccept(name ->
                                plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                                        openAppealDetail(player, appeal, name != null ? name : "Unknown", holder.getPage())));
                    }));
        }
    }

    public void handleDetailClick(Player player, AppealDetailHolder holder, int slot, ItemStack item) {
        if (item == null) return;

        switch (slot) {
            case 18 -> openAppealsGui(player, holder.getReturnPage());
            case 20 -> review(player, holder, true);
            case 24 -> review(player, holder, false);
            default -> { }
        }
    }

    private void review(Player staff, AppealDetailHolder holder, boolean approve) {
        AppealService.Appeal appeal = holder.getAppeal();
        String newStatus = approve ? AppealService.STATUS_APPROVED : AppealService.STATUS_DENIED;

        plugin.getAppealService().updateAppealStatus(appeal.id(), newStatus).thenAccept(updated ->
                plugin.getSchedulerAdapter().runTaskForPlayer(staff, () -> {
                    if (!updated) {
                        staff.sendMessage(locale.getMessage("gui.appeals.appeal-not-found"));
                        openAppealsGui(staff, holder.getReturnPage());
                        return;
                    }
                    if (!approve) {
                        staff.sendMessage(locale.getMessage("appeals.denied-staff",
                                Placeholder.unparsed("player", holder.getAppealerName())));
                        notifyPlayer(appeal.targetUuid(), "appeals.denied-player-notify");
                        finishReview(staff, appeal, holder.getAppealerName(), holder.getReturnPage(), newStatus);
                        return;
                    }
                    // Approve: unmute through the exact same service path /unmute uses.
                    String targetName = holder.getAppealerName();
                    plugin.getPunishmentService().unmutePlayer(targetName, staff.getUniqueId(), staff.getName())
                            .whenComplete((unmuted, throwable) ->
                                    plugin.getSchedulerAdapter().runTaskForPlayer(staff, () -> {
                                        if (throwable != null || !Boolean.TRUE.equals(unmuted)) {
                                            staff.sendMessage(locale.getMessage("appeals.approved-no-mute",
                                                    Placeholder.unparsed("player", targetName)));
                                        } else {
                                            staff.sendMessage(locale.getMessage("appeals.approved-staff",
                                                    Placeholder.unparsed("player", targetName)));
                                            notifyPlayer(appeal.targetUuid(), "appeals.approved-player-notify");
                                        }
                                        finishReview(staff, appeal, targetName, holder.getReturnPage(), newStatus);
                                    }));
                }));
    }

    private void finishReview(Player staff, AppealService.Appeal appeal, String appealerName, int returnPage, String newStatus) {
        AppealService.Appeal updated = new AppealService.Appeal(
                appeal.id(), appeal.punishmentId(), appeal.targetUuid(), appeal.message(), appeal.createdAt(), newStatus);
        openAppealDetail(staff, updated, appealerName, returnPage);
    }

    private void notifyPlayer(UUID targetUuid, String localeKey) {
        if (targetUuid == null) return;
        plugin.getSchedulerAdapter().runTask(() -> {
            Player p = Bukkit.getPlayer(targetUuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(locale.getMessage(localeKey));
            }
        });
    }

    // ------------------------------------------------------------------
    // Shared item helpers
    // ------------------------------------------------------------------

    private ItemStack createActionItem(Material material, String nameKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(String localeKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(localeKey).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfolessItem(Material material, String nameKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
