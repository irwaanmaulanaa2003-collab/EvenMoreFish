package com.oheers.fish.fishing.rods;

import com.oheers.fish.EvenMoreFish;
import com.oheers.fish.api.Logging;
import com.oheers.fish.api.config.ConfigBase;
import com.oheers.fish.api.fishing.rods.ICustomRod;
import com.oheers.fish.fishing.items.Fish;
import com.oheers.fish.fishing.items.FishManager;
import com.oheers.fish.fishing.items.Rarity;
import com.oheers.fish.items.ItemFactory;
import com.oheers.fish.recipe.EMFRecipe;
import com.oheers.fish.recipe.RecipeUtil;
import com.oheers.fish.utils.nbt.NbtKeys;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

public class CustomRod extends ConfigBase implements ICustomRod {

    private final @NotNull String id;
    private final List<Rarity> allowedRarities;
    private final List<Fish> allowedFish;
    private final EMFRecipe<?> recipe;
    private final ItemFactory factory;
    private final Map<String, Double> rarityWeights;
    private final int pullPower;
    private final double resistanceReduction;
    private final double biteSpeedBonus;

    public CustomRod(@NotNull File file) throws InvalidConfigurationException {
        super(file, EvenMoreFish.getInstance(), false);
        this.id = validateId();
        this.allowedRarities = loadAllowedRarities();
        this.allowedFish = loadAllowedFish();
        this.factory = ItemFactory.itemFactory(getConfig());
        this.rarityWeights = loadRarityWeights();
        this.pullPower = getConfig().getInt("pull-power", 3);
        this.resistanceReduction = getConfig().getDouble("resistance-reduction", 0.0D);
        this.biteSpeedBonus = getConfig().getDouble("bite-speed-bonus", 0.0D);
        this.factory.setFinalChanges(item ->
            NBT.modify(item, nbt -> {
                ReadWriteNBT emfCompound = nbt.getOrCreateCompound(NbtKeys.EMF_COMPOUND);
                emfCompound.setString(NbtKeys.EMF_ROD_ID, getId());
            })
        );
        this.recipe = loadRecipe();
    }

    private String validateId() throws InvalidConfigurationException {
        String id = getConfig().getString("id");
        if (id == null) {
            throw new InvalidConfigurationException("CustomRod " + getFileName() + " has no configured id.");
        }
        return id;
    }

    // Loading things

    /**
     * @return the contents of the "allowed-rarities" section, or an empty list if the section does not exist.
     */
    public List<Rarity> loadAllowedRarities() {
        if ("ALL".equalsIgnoreCase(getConfig().getString("allowed-rarities"))) {
            return FishManager.getInstance().getRarityMap().values().stream().toList();
        }
        return getConfig().getStringList("allowed-rarities").stream()
            .map(id -> FishManager.getInstance().getRarity(id))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * @return the contents of the "allowed-fish" section, or an empty list if the section does not exist.
     */
    public List<Fish> loadAllowedFish() {
        Section section = getConfig().getSection("allowed-fish");
        if (section == null) {
            return List.of();
        }

        return section.getRoutesAsStrings(false).stream()
            .flatMap(rarityId -> {
                Rarity rarity = FishManager.getInstance().getRarity(rarityId);
                if (rarity == null) {
                    Logging.warn("Rarity '" + rarityId + "' not found for custom rod '" + getId() + "'.");
                    return Stream.empty();
                }
                if (!allowedRarities.contains(rarity)) {
                    Logging.warn("Rarity '" + rarityId + "' is not allowed for custom rod '" + getId() + "'.");
                    return Stream.empty();
                }
                return section.getStringList(rarityId)
                    .stream()
                    .map(rarity::getFish)
                    .filter(Objects::nonNull);
            })
            .toList();
    }

    private EMFRecipe<?> loadRecipe() {
        Section section = getConfig().getSection("recipe");
        if (section == null) {
            return null;
        }
        return RecipeUtil.getRecipe(
            section,
            getRecipeKey(),
            create()
        );
    }

    private Map<String, Double> loadRarityWeights() {
        Section section = getConfig().getSection("rarity-weights");
        if (section == null) {
            return Map.of();
        }
        Map<String, Double> weights = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        section.getRoutesAsStrings(false).forEach(key -> weights.put(key, section.getDouble(key, 0.0D)));
        return weights;
    }

    // Config getters

    @Override
    public @NotNull String getId() {
        return this.id;
    }

    private @NotNull NamespacedKey getRecipeKey() {
        return new NamespacedKey(EvenMoreFish.getInstance(), "customrod-" + getId());
    }

    @Override
    public boolean isDisabled() {
        return getConfig().getBoolean("disabled");
    }

    /**
     * Fetches the ItemFactory for this rod.
     * NOTE: Creating an ItemStack from this factory will not add the necessary NBT to identify the rod. Use {@link #create()} instead.
     */
    public ItemFactory getFactory() {
        return this.factory;
    }

    /**
     * Creates an ItemStack of this rod, with the necessary NBT to identify it.
     */
    @Override
    public @NotNull ItemStack create() {
        ItemStack item = factory.createItem();
        NBT.modify(item, nbt -> {
            ReadWriteNBT emfCompound = nbt.getOrCreateCompound(NbtKeys.EMF_COMPOUND);
            emfCompound.setString(NbtKeys.EMF_ROD_ID, getId());
        });
        return item;
    }

    @Override
    public @NotNull List<Rarity> getAllowedRarities() {
        return this.allowedRarities;
    }

    @Override
    public @NotNull List<Fish> getAllowedFish() {
        return this.allowedFish;
    }

    public @Nullable EMFRecipe<?> getRecipe() {
        return this.recipe;
    }

    public double getRarityWeight(@NotNull Rarity rarity) {
        return rarityWeights.getOrDefault(rarity.getId(), rarity.getWeight());
    }

    public int getPullPower() {
        return pullPower;
    }

    public double getResistanceReduction() {
        return resistanceReduction;
    }

    public double getBiteSpeedBonus() {
        return biteSpeedBonus;
    }

}
