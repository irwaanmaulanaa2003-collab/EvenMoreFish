package com.oheers.fish.gui;

import com.oheers.fish.EvenMoreFish;
import com.oheers.fish.FishUtils;
import com.oheers.fish.config.GuiConfig;
import com.oheers.fish.config.MainConfig;
import com.oheers.fish.api.economy.Economy;
import com.oheers.fish.fishing.rods.CustomRod;
import com.oheers.fish.fishing.rods.RodManager;
import com.oheers.fish.fishing.rods.RodUpgradeManager;
import com.oheers.fish.database.DatabaseUtil;
import com.oheers.fish.gui.guis.BaitsGui;
import com.oheers.fish.gui.guis.FishJournalGui;
import com.oheers.fish.gui.guis.MainMenuGui;
import com.oheers.fish.gui.guis.RodShopGui;
import com.oheers.fish.gui.guis.SellGui;
import com.oheers.fish.items.ItemFactory;
import com.oheers.fish.messages.ConfigMessage;
import com.oheers.fish.selling.SellHelper;
import de.themoep.inventorygui.GuiElement;
import de.themoep.inventorygui.GuiPageElement;
import de.themoep.inventorygui.InventoryGui;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class GuiUtils {

    public static GuiPageElement getFirstPageButton() {
        YamlDocument config = GuiConfig.getInstance().getConfig();
        return new GuiPageElement('f',
            createItemStack(config.getSection("general.first-page")),
            GuiPageElement.PageAction.FIRST
        );
    }

    public static GuiPageElement getNextPageButton() {
        YamlDocument config = GuiConfig.getInstance().getConfig();
        return new GuiPageElement('n',
            createItemStack(config.getSection("general.next-page")),
            GuiPageElement.PageAction.NEXT
        );
    }

    public static GuiPageElement getPreviousPageButton() {
        YamlDocument config = GuiConfig.getInstance().getConfig();
        return new GuiPageElement('p',
            createItemStack(config.getSection("general.previous-page")),
            GuiPageElement.PageAction.PREVIOUS
        );
    }

    public static GuiPageElement getLastPageButton() {
        YamlDocument config = GuiConfig.getInstance().getConfig();
        return new GuiPageElement('l',
            createItemStack(config.getSection("general.last-page")),
            GuiPageElement.PageAction.LAST
        );
    }

    public static ItemStack createItemStack(@Nullable Section section) {
        if (section == null) {
            ItemStack fallback = new ItemStack(Material.BARRIER);
            fallback.editMeta(meta -> meta.displayName(Component.text("Invalid Item")));
            return fallback;
        }
        // Fix for me messing up the item config layout - FireML
        if (section.contains("displayname")) {
            section.set("item.displayname", section.get("displayname"));
            section.remove("displayname");
        }
        ItemFactory factory = ItemFactory.itemFactory(section);
        return factory.createItem();
    }

    public static Map<String, BiConsumer<ConfigGui, GuiElement.Click>> getActionMap() {
        Map<String, BiConsumer<ConfigGui, GuiElement.Click>> newActionMap = new HashMap<>();
        // Exiting the main menu should close the Gui
        newActionMap.put("full-exit", (gui, click) -> {
            if (gui != null) {
                gui.doRescue();
            }
            closeGui(click.getWhoClicked());
        });
        // Exiting a sub-menu should open the main menu
        newActionMap.put("open-main-menu", (gui, click) -> {
            if (gui != null) {
                gui.doRescue();
            }
            new MainMenuGui(click.getWhoClicked()).open();
            clearHistory(click.getWhoClicked());
        });
        // Toggling custom fish should redraw the Gui and leave it at that
        newActionMap.put("fish-toggle", (gui, click) -> {
            if (click.getWhoClicked() instanceof Player player) {
                EvenMoreFish.getInstance().getToggle().performFishToggle(player);
            }
            click.getGui().draw();
        });
        // The shop action should just open the shop menu
        newActionMap.put("open-shop", (gui, click) -> {
            if (gui != null) {
                gui.doRescue();
            }

            HumanEntity humanEntity = click.getWhoClicked();

            if (!(humanEntity instanceof Player player)) {
                return;
            }
            new SellGui(player, SellGui.SellState.NORMAL, null).open();
            clearHistory(click.getWhoClicked());
        });
        newActionMap.put("open-rod-shop", (gui, click) -> {
            if (!MainConfig.getInstance().isRodShopEnabled()) {
                click.getWhoClicked().sendMessage(Component.text("Rod shop is disabled."));
                return;
            }
            if (gui != null) {
                gui.doRescue();
            }
            new RodShopGui(click.getWhoClicked()).open();
            clearHistory(click.getWhoClicked());
        });
        MainConfig.getInstance().getRodShopPrices().forEach((rodId, price) ->
            newActionMap.put("buy-rod-" + rodId, (gui, click) -> buyRod(click.getWhoClicked(), rodId, price))
        );
        newActionMap.put("show-command-help", (gui, click) -> {
            click.getWhoClicked().closeInventory();
            if (click.getWhoClicked() instanceof Player player) {
                //todo test
                player.performCommand("%s %s".formatted(
                                MainConfig.getInstance().getMainCommandName(),
                                MainConfig.getInstance().getHelpSubCommandName()
                ));
            }

            //MainCommand.HELP_MESSAGE.sendMessage(click.getWhoClicked());
        });
        newActionMap.put("sell-inventory", (gui, click) -> {
            HumanEntity humanEntity = click.getWhoClicked();
            if (!(humanEntity instanceof Player player)) {
                return;
            }
            if (gui instanceof SellGui sellGui) {
                new SellGui(player, SellGui.SellState.CONFIRM, sellGui.getFishInventory()).open();
                return;
            }
            new SellHelper(click.getWhoClicked().getInventory(), player).sell();
            closeGui(humanEntity);
        });
        newActionMap.put("sell-shop", (gui, click) -> {
            HumanEntity humanEntity = click.getWhoClicked();
            if (gui instanceof SellGui sellGui && humanEntity instanceof Player player) {
                new SellGui(player, SellGui.SellState.CONFIRM, sellGui.getFishInventory()).open();
                return;
            }
            SellHelper.sellInventoryGui(click.getGui(), click.getWhoClicked());
            closeGui(click.getWhoClicked());
        });
        newActionMap.put("sell-inventory-confirm", (gui, click) -> {
            HumanEntity humanEntity = click.getWhoClicked();
            if (!(humanEntity instanceof Player player)) {
                return;
            }
            new SellHelper(click.getWhoClicked().getInventory(), player).sell();
            if (gui != null) {
                gui.doRescue();
            }
            closeGui(click.getWhoClicked());
        });
        newActionMap.put("sell-shop-confirm", (gui, click) -> {
            SellHelper.sellInventoryGui(click.getGui(), click.getWhoClicked());
            if (gui != null) {
                gui.doRescue();
            }
            closeGui(click.getWhoClicked());
        });
        newActionMap.put("open-baits-menu", (gui, click) -> {
            if (gui != null) {
                gui.doRescue();
            }
            new BaitsGui(click.getWhoClicked()).open();
            clearHistory(click.getWhoClicked());
        });
        newActionMap.put("open-journal-menu", (gui, click) -> {
            if (!DatabaseUtil.isDatabaseOnline()) {
                ConfigMessage.JOURNAL_DISABLED.getMessage().send(click.getWhoClicked());
                return;
            }
            if (gui != null) {
                gui.doRescue();
            }
            new FishJournalGui(click.getWhoClicked(), null).open();
            clearHistory(click.getWhoClicked());
        });
        // Add page actions so third party plugins cannot register their own.
        newActionMap.put("first-page", (gui, click) -> {});
        newActionMap.put("previous-page", (gui, click) -> {});
        newActionMap.put("next-page", (gui, click) -> {});
        newActionMap.put("last-page", (gui, click) -> {});

        return newActionMap;
    }

    private static void buyRod(@NotNull HumanEntity human, @NotNull String rodId, double price) {
        if (!(human instanceof Player player)) {
            return;
        }
        CustomRod rod = RodManager.getInstance().getRod(rodId);
        if (rod == null) {
            player.sendMessage(Component.text("Rod not found: " + rodId));
            return;
        }
        RodUpgradeManager upgrades = RodUpgradeManager.getInstance();
        if (!upgrades.meetsRequirements(player, rodId)) {
            player.sendMessage(Component.text("Requirement not met for " + rodId + ":"));
            for (String missing : upgrades.getMissingRequirements(player, rodId)) {
                player.sendMessage(Component.text("- " + missing));
            }
            return;
        }

        if (price > 0 && !Economy.getInstance().isEnabled()) {
            player.sendMessage(Component.text("Economy is not available."));
            return;
        }
        if (price > 0 && !Economy.getInstance().has(player, price)) {
            player.sendMessage(Component.text("You do not have enough money."));
            return;
        }
        if (price > 0) {
            Economy.getInstance().withdraw(player, price, false);
        }

        if (!upgrades.consumePreviousRod(player, rodId)) {
            if (price > 0) {
                Economy.getInstance().deposit(player, price, false);
            }
            player.sendMessage(Component.text("Previous rod is required for this upgrade."));
            return;
        }

        FishUtils.giveItem(rod.create(), player);
        player.sendMessage(Component.text("Purchased " + rodId + " for " + price + "."));
        closeGui(player);
    }

    private static void closeGui(HumanEntity human) {
        clearHistory(human);
        human.closeInventory();
    }

    private static void clearHistory(HumanEntity human) {
        InventoryGui.clearHistory(human);
    }

}
