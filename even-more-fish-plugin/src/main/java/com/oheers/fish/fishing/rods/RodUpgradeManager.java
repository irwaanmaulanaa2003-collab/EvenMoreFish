package com.oheers.fish.fishing.rods;

import com.oheers.fish.EvenMoreFish;
import com.oheers.fish.config.MainConfig;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RodUpgradeManager {

    private static final RodUpgradeManager instance = new RodUpgradeManager();

    private final EvenMoreFish plugin = EvenMoreFish.getInstance();
    private final File progressFile = new File(plugin.getDataFolder(), "rod-progress.yml");
    private YamlConfiguration progressConfig;

    private RodUpgradeManager() {
        load();
    }

    public static @NotNull RodUpgradeManager getInstance() {
        return instance;
    }

    public void load() {
        if (!progressFile.exists()) {
            try {
                File parent = progressFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                progressFile.createNewFile();
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not create rod-progress.yml: " + exception.getMessage());
            }
        }
        this.progressConfig = YamlConfiguration.loadConfiguration(progressFile);
    }

    public void recordCatch(@NotNull Player player, @NotNull String rarityId) {
        UUID uuid = player.getUniqueId();
        String rarity = normalize(rarityId);
        String base = uuid.toString();
        progressConfig.set(base + ".name", player.getName());
        progressConfig.set(base + ".total-catches", getTotalCatches(uuid) + 1);
        progressConfig.set(base + ".rarities." + rarity, getRarityCatches(uuid, rarity) + 1);
        save();
    }

    public int getTotalCatches(@NotNull Player player) {
        return getTotalCatches(player.getUniqueId());
    }

    public int getTotalCatches(@NotNull UUID uuid) {
        return progressConfig.getInt(uuid + ".total-catches", 0);
    }

    public int getRarityCatches(@NotNull Player player, @NotNull String rarityId) {
        return getRarityCatches(player.getUniqueId(), rarityId);
    }

    public int getRarityCatches(@NotNull UUID uuid, @NotNull String rarityId) {
        return progressConfig.getInt(uuid + ".rarities." + normalize(rarityId), 0);
    }

    public boolean meetsRequirements(@NotNull Player player, @NotNull String rodId) {
        return getMissingRequirements(player, rodId).isEmpty();
    }

    public @NotNull List<String> getMissingRequirements(@NotNull Player player, @NotNull String rodId) {
        List<String> missing = new ArrayList<>();
        if (!MainConfig.getInstance().isRodUpgradeRequirementsEnabled()) {
            return missing;
        }

        Section requirement = MainConfig.getInstance().getRodUpgradeRequirement(rodId);
        if (requirement == null) {
            return missing;
        }

        String previousRod = requirement.getString("previous-rod", "");
        if (previousRod != null && !previousRod.isBlank() && !hasRod(player, previousRod)) {
            missing.add("Previous Rod: " + displayRod(previousRod));
        }

        int requiredTotal = requirement.getInt("total-catches", 0);
        int total = getTotalCatches(player);
        if (requiredTotal > 0 && total < requiredTotal) {
            missing.add("Total Catches: " + total + " / " + requiredTotal);
        }

        Section rarities = requirement.getSection("rarities");
        if (rarities != null) {
            for (String rarityId : rarities.getRoutesAsStrings(false)) {
                int required = rarities.getInt(rarityId, 0);
                if (required <= 0) {
                    continue;
                }
                int caught = getRarityCatches(player, rarityId);
                if (caught < required) {
                    missing.add(displayRarity(rarityId) + " Fish: " + caught + " / " + required);
                }
            }
        }

        return missing;
    }

    public @NotNull List<String> getProgressLines(@NotNull Player player) {
        return getProgressLines(player.getUniqueId(), player.getName());
    }

    public @NotNull List<String> getProgressLines(@NotNull UUID uuid, @NotNull String name) {
        List<String> lines = new ArrayList<>();
        lines.add("Fishing progress for " + name + ":");
        lines.add("Total Catches: " + getTotalCatches(uuid));
        lines.add("Junk: " + getRarityCatches(uuid, "junk"));
        lines.add("Common: " + getRarityCatches(uuid, "common"));
        lines.add("Rare: " + getRarityCatches(uuid, "rare"));
        lines.add("Epic: " + getRarityCatches(uuid, "epic"));
        lines.add("Legendary: " + getRarityCatches(uuid, "legendary"));
        lines.add("Mythic: " + getRarityCatches(uuid, "mythic"));
        lines.add("Divine: " + getRarityCatches(uuid, "divine"));
        return lines;
    }

    public @Nullable UUID findUuidByName(@NotNull String name) {
        for (String key : progressConfig.getKeys(false)) {
            String stored = progressConfig.getString(key + ".name", "");
            if (stored != null && stored.equalsIgnoreCase(name)) {
                try {
                    return UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public @NotNull String getStoredName(@NotNull UUID uuid) {
        return progressConfig.getString(uuid + ".name", uuid.toString());
    }

    public boolean consumePreviousRod(@NotNull Player player, @NotNull String rodId) {
        if (!MainConfig.getInstance().isRodUpgradeRequirementsEnabled() || !MainConfig.getInstance().shouldConsumePreviousRod()) {
            return true;
        }

        Section requirement = MainConfig.getInstance().getRodUpgradeRequirement(rodId);
        if (requirement == null) {
            return true;
        }

        String previousRod = requirement.getString("previous-rod", "");
        if (previousRod == null || previousRod.isBlank()) {
            return true;
        }

        return removeOneRod(player, previousRod);
    }

    public boolean hasRod(@NotNull Player player, @NotNull String rodId) {
        for (ItemStack item : player.getInventory().getContents()) {
            CustomRod rod = getRod(item);
            if (rod != null && rod.getId().equalsIgnoreCase(rodId)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeOneRod(@NotNull Player player, @NotNull String rodId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            CustomRod rod = getRod(item);
            if (rod == null || !rod.getId().equalsIgnoreCase(rodId)) {
                continue;
            }
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItem(slot, null);
            }
            return true;
        }
        return false;
    }

    private @Nullable CustomRod getRod(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return RodManager.getInstance().getRod(item);
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private @NotNull String displayRarity(@NotNull String rarityId) {
        if (rarityId.isBlank()) {
            return rarityId;
        }
        return rarityId.substring(0, 1).toUpperCase(Locale.ROOT) + rarityId.substring(1).toLowerCase(Locale.ROOT);
    }

    private @NotNull String displayRod(@NotNull String rodId) {
        return switch (rodId.toLowerCase(Locale.ROOT)) {
            case "wooden_fisher_rod" -> "Wooden Fisher Rod";
            case "stone_fisher_rod" -> "Stone Fisher Rod";
            case "iron_fisher_rod" -> "Iron Fisher Rod";
            case "golden_fisher_rod" -> "Golden Fisher Rod";
            case "diamond_fisher_rod" -> "Diamond Fisher Rod";
            case "emerald_fisher_rod" -> "Emerald Fisher Rod";
            case "netherite_fisher_rod" -> "Netherite Fisher Rod";
            case "mythic_fisher_rod" -> "Mythic Fisher Rod";
            case "celestial_fisher_rod" -> "Celestial Fisher Rod";
            case "divine_fisher_rod" -> "Divine Fisher Rod";
            default -> rodId;
        };
    }

    private void save() {
        try {
            progressConfig.save(progressFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save rod-progress.yml: " + exception.getMessage());
        }
    }
}
