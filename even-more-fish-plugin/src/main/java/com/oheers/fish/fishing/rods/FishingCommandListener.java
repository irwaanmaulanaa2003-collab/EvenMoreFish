package com.oheers.fish.fishing.rods;

import com.oheers.fish.config.MainConfig;
import com.oheers.fish.gui.guis.RodShopGui;
import com.oheers.fish.permissions.AdminPerms;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class FishingCommandListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommand(@NotNull PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (!raw.startsWith("/")) {
            return;
        }
        String[] args = raw.substring(1).trim().split("\\s+");
        if (args.length == 0 || args[0].isBlank()) {
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        Player player = event.getPlayer();

        if (command.equals("fishing")) {
            event.setCancelled(true);
            if (args.length >= 2 && args[1].equalsIgnoreCase("stats")) {
                showStats(player, player, Arrays.copyOfRange(args, 2, args.length));
                return;
            }
            new RodShopGui(player).open();
            return;
        }

        if (command.equals("fishstats") || command.equals("fishingstats")) {
            event.setCancelled(true);
            showStats(player, player, Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (command.equals("sellfish")) {
            event.setCancelled(true);
            Bukkit.dispatchCommand(player, MainConfig.getInstance().getMainCommandName() + " " + MainConfig.getInstance().getShopSubCommandName());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerCommand(@NotNull ServerCommandEvent event) {
        String[] args = event.getCommand().trim().split("\\s+");
        if (args.length == 0 || args[0].isBlank()) {
            return;
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!command.equals("fishstats") && !command.equals("fishingstats") && !command.equals("fishing")) {
            return;
        }
        if (command.equals("fishing") && (args.length < 2 || !args[1].equalsIgnoreCase("stats"))) {
            return;
        }
        event.setCancelled(true);
        int start = command.equals("fishing") ? 2 : 1;
        showStats(event.getSender(), null, Arrays.copyOfRange(args, start, args.length));
    }

    private void showStats(@NotNull CommandSender sender, Player self, String[] args) {
        RodUpgradeManager upgrades = RodUpgradeManager.getInstance();
        if (args.length == 0) {
            if (self == null) {
                sender.sendMessage("§cUsage: /fishstats <player>");
                return;
            }
            upgrades.getProgressLines(self).forEach(line -> sender.sendMessage("§b[Fishing] §f" + line));
            return;
        }

        if (!sender.hasPermission(AdminPerms.ADMIN)) {
            sender.sendMessage("§cYou do not have permission to view other players' fishing stats.");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target != null) {
            upgrades.getProgressLines(target).forEach(line -> sender.sendMessage("§b[Fishing] §f" + line));
            return;
        }

        UUID uuid = upgrades.findUuidByName(args[0]);
        if (uuid == null) {
            sender.sendMessage("§cNo fishing progress found for player: " + args[0]);
            return;
        }
        upgrades.getProgressLines(uuid, upgrades.getStoredName(uuid)).forEach(line -> sender.sendMessage("§b[Fishing] §f" + line));
    }
}
