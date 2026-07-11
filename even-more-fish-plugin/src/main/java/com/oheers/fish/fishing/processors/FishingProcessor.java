package com.oheers.fish.fishing.processors;

import com.oheers.fish.Checks;
import com.oheers.fish.EvenMoreFish;
import com.oheers.fish.FishUtils;
import com.oheers.fish.api.events.EMFFishCaughtEvent;
import com.oheers.fish.api.fishing.CatchType;
import com.oheers.fish.competition.Competition;
import com.oheers.fish.config.MainConfig;
import com.oheers.fish.fishing.Processor;
import com.oheers.fish.fishing.items.Fish;
import com.oheers.fish.fishing.rods.CustomRod;
import com.oheers.fish.fishing.rods.RodManager;
import com.oheers.fish.messages.ConfigMessage;
import com.oheers.fish.messages.EMFSingleMessage;
import com.oheers.fish.permissions.UserPerms;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FishingProcessor extends Processor<PlayerFishEvent> implements Listener {
    private final EvenMoreFish plugin = EvenMoreFish.getInstance();
    private final Map<UUID, MinigameSession> sessions = new HashMap<>();

    @Override
    @EventHandler(priority = EventPriority.HIGHEST)
    public void process(@NotNull PlayerFishEvent event) {
        if (event.isCancelled()) {
            plugin.debug("Fishing event was cancelled. Skipping handling.");
            return;
        }

        if (MainConfig.getInstance().isCustomMinigameEnabled()) {
            processMinigame(event);
            return;
        }

        processDefault(event);
    }

    private void processDefault(@NotNull PlayerFishEvent event) {
        ItemStack rod = getRod(event);

        if (rod == null) {
            plugin.debug("Fishing blocked: could not find rod.");
            return;
        }

        if (!isCustomFishAllowed(event.getPlayer())) {
            plugin.debug("Fishing blocked: custom fish not allowed for player %s.".formatted(event.getPlayer().getName()));
            return;
        }

        if (!Checks.canUseRod(rod)) {
            plugin.debug("Fishing blocked: rod unusable (%s).".formatted(rod));
            return;
        }

        if (MainConfig.getInstance().requireFishingPermission() && !event.getPlayer().hasPermission(UserPerms.USE_ROD)) {
            plugin.debug("Fishing blocked: permission required and player lacks it.");
            if (event.getState() == PlayerFishEvent.State.FISHING) {
                //send msg only when throw the lure
                ConfigMessage.NO_PERMISSION_FISHING.getMessage().send(event.getPlayer());
            }
            return;
        }

        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            plugin.debug("Ignoring event state: %s".formatted(event.getState()));
            return;
        }

        if (!(event.getCaught() instanceof Item nonCustom)) {
            plugin.debug("Caught entity is not an Item.");
            return;
        }

        if (MainConfig.getInstance().isFishCatchOverrideOnlyFish() && !Tag.ITEMS_FISHES.isTagged(nonCustom.getItemStack().getType())) {
            plugin.debug("Caught item is not a vanilla fish, and we have been told to skip non-fish. Skipping.");
            return;
        }

        ItemStack fish = getCaughtItem(event.getPlayer(), event.getHook().getLocation(), rod);

        if (fish == null) {
            plugin.debug("Could not obtain fish.");
            return;
        }

        if (MainConfig.getInstance().isGiveStraightToInventory() && FishUtils.inventoryHasSpace(event.getPlayer().getInventory())) {
            FishUtils.giveItem(fish, event.getPlayer());
            nonCustom.remove();
            return;
        }
        // replaces the fishing item with a custom evenmorefish fish.
        if (fish.isEmpty()) {
            nonCustom.remove();
            return;
        }

        nonCustom.setItemStack(fish);
    }

    private void processMinigame(@NotNull PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ItemStack rod = getRod(event);
        CustomRod customRod = getHeldCustomRod(rod);

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            sessions.remove(uuid);
            if (customRod == null) {
                // Vanilla rods are allowed to fish normally, but they will not receive custom EMF fish.
                return;
            }
            if (!canStartCustomFishing(player, rod, true)) {
                event.setCancelled(true);
                return;
            }
            applyBiteSpeedBonus(event.getHook(), customRod);
            return;
        }

        MinigameSession session = sessions.get(uuid);
        if (event.getState() == PlayerFishEvent.State.BITE) {
            if (customRod == null || !canStartCustomFishing(player, rod, false)) {
                return;
            }
            Fish fish = chooseFish(player, event.getHook().getLocation(), null, customRod);
            if (fish == null) {
                return;
            }
            MinigameSession newSession = new MinigameSession(event.getHook(), fish, customRod);
            // Put the player into WAITING_HOOK before any UI is sent, so fast right-clicks are accepted.
            sessions.put(uuid, newSession);
            playMinigameSound(player, "bite", "BLOCK_NOTE_BLOCK_PLING", 1.25F);
            sendMinigameMessage(player, "bite", "<gold>Strike detected.</gold> <white>Right-click within <aqua>{time}s</aqua> to set the hook.</white>", Map.of(
                "{time}", String.valueOf(MainConfig.getInstance().getMinigameHookTimeSeconds())
            ));
            sendMinigameTitle(player, "bite-title", "<gold>STRIKE!</gold>", "bite-subtitle", "<white>Right-click to set the hook</white>", Map.of(
                "{time}", String.valueOf(MainConfig.getInstance().getMinigameHookTimeSeconds())
            ));
            int hookTimeoutTicks = Math.max(1, MainConfig.getInstance().getMinigameHookTimeSeconds()) * 20;
            EvenMoreFish.getScheduler().runTaskLater(() -> expireWaitingHook(player.getUniqueId(), newSession), hookTimeoutTicks);
            return;
        }

        if (event.getState() == PlayerFishEvent.State.REEL_IN && session != null && session.state == MinigameState.WAITING_HOOK) {
            event.setCancelled(true);
            startReadyCountdown(player, session);
            return;
        }

        if (session != null && (event.getState() == PlayerFishEvent.State.CAUGHT_FISH || event.getState() == PlayerFishEvent.State.REEL_IN)) {
            event.setCancelled(true);
            if (event.getCaught() instanceof Item item) {
                item.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHookInteract(@NotNull PlayerInteractEvent event) {
        if (!MainConfig.getInstance().isCustomMinigameEnabled()) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        MinigameSession session = sessions.get(player.getUniqueId());
        if (session == null || session.state != MinigameState.WAITING_HOOK) {
            return;
        }
        if (!canUseHeldCustomRod(player)) {
            return;
        }
        event.setCancelled(true);
        startReadyCountdown(player, session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneakPull(@NotNull PlayerToggleSneakEvent event) {
        if (!MainConfig.getInstance().isCustomMinigameEnabled() || !event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        MinigameSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.state == MinigameState.READY_COUNTDOWN) {
            sendMinigameMessage(player, "wait-ready", "<yellow>Wait for the <green>GO</green> signal before pulling.</yellow>", Map.of());
            return;
        }
        if (session.state != MinigameState.PULLING) {
            return;
        }
        if (session.hook == null || !session.hook.isValid()) {
            failSession(player, session);
            return;
        }

        session.lastPullMillis = System.currentTimeMillis();
        int pullPower = Math.max(1, session.rod == null ? 3 : session.rod.getPullPower());
        session.progress = Math.min(MainConfig.getInstance().getMinigameProgressNeeded(), session.progress + pullPower);
        reduceFishPressureOnPull(session);
        playMinigameSound(player, "pull", "ENTITY_EXPERIENCE_ORB_PICKUP", 1.1F);
        applyResistance(player, session);
        pullBobberTowardPlayer(player, session);
        sendProgressMessage(player, session);

        if (session.progress >= MainConfig.getInstance().getMinigameProgressNeeded()) {
            completeSession(player, session);
            return;
        }
        scheduleEscapeCheck(player.getUniqueId(), session);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private boolean canStartCustomFishing(@NotNull Player player, @Nullable ItemStack rod, boolean notify) {
        if (rod == null || !Checks.canUseRod(rod)) {
            if (notify && MainConfig.getInstance().requireCustomRod() && MainConfig.getInstance().blockVanillaRodWhenCustomRequired()) {
                sendMinigameMessage(player, "vanilla-rod-blocked", "<red>You need a custom fishing rod to catch custom fish.</red>", Map.of());
            }
            return false;
        }
        if (!isCustomFishAllowed(player)) {
            return false;
        }
        if (MainConfig.getInstance().requireFishingPermission() && !player.hasPermission(UserPerms.USE_ROD)) {
            if (notify) {
                ConfigMessage.NO_PERMISSION_FISHING.getMessage().send(player);
            }
            return false;
        }
        return true;
    }

    private @Nullable CustomRod getHeldCustomRod(@Nullable ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return null;
        }
        return RodManager.getInstance().getRod(rod);
    }

    private void applyBiteSpeedBonus(@NotNull FishHook hook, @NotNull CustomRod customRod) {
        double bonus = Math.max(0.0D, Math.min(80.0D, customRod.getBiteSpeedBonus()));
        if (bonus <= 0.0D) {
            return;
        }
        double multiplier = Math.max(0.2D, 1.0D - (bonus / 100.0D));
        int currentMin = Math.max(1, hook.getMinWaitTime());
        int currentMax = Math.max(currentMin + 1, hook.getMaxWaitTime());
        int newMin = Math.max(20, (int) Math.round(currentMin * multiplier));
        int newMax = Math.max(newMin + 20, (int) Math.round(currentMax * multiplier));
        hook.setMinWaitTime(newMin);
        hook.setMaxWaitTime(newMax);
    }

    private void startReadyCountdown(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.state != MinigameState.WAITING_HOOK) {
            return;
        }
        session.state = MinigameState.READY_COUNTDOWN;
        session.lastPullMillis = System.currentTimeMillis();
        playMinigameSound(player, "hook", "ENTITY_FISHING_BOBBER_RETRIEVE", 1.0F);
        sendMinigameTitle(player, "ready-title", "<green>HOOK SET</green>", "ready-subtitle", "<white>Prepare to pull. Wait for the signal.</white>");
        runReadyCountdown(player.getUniqueId(), session, Math.max(1, MainConfig.getInstance().getMinigameReadyDelaySeconds()));
    }

    private void runReadyCountdown(@NotNull UUID uuid, @NotNull MinigameSession expected, int secondsLeft) {
        Player player = plugin.getServer().getPlayer(uuid);
        MinigameSession session = sessions.get(uuid);
        if (player == null || session != expected || session.state != MinigameState.READY_COUNTDOWN) {
            return;
        }

        if (secondsLeft > 0) {
            playMinigameSound(player, "countdown", "BLOCK_NOTE_BLOCK_HAT", 1.0F + (0.1F * secondsLeft));
            sendMinigameMessage(player, "ready-countdown", "<green>Ready.</green> <white>Pulling starts in <aqua>{time}</aqua>...</white>", Map.of(
                "{time}", String.valueOf(secondsLeft)
            ));
            sendMinigameTitle(player, "ready-countdown-title", "<aqua>{time}</aqua>", "ready-countdown-subtitle", "<white>Hold steady. Do not pull yet.</white>", Map.of(
                "{time}", String.valueOf(secondsLeft)
            ));
            EvenMoreFish.getScheduler().runTaskLater(() -> runReadyCountdown(uuid, expected, secondsLeft - 1), 20L);
            return;
        }

        session.state = MinigameState.PULLING;
        session.progress = 0;
        session.fishProgress = 0.0D;
        session.lastPullMillis = System.currentTimeMillis();
        playMinigameSound(player, "go", "ENTITY_PLAYER_LEVELUP", 1.0F);
        sendMinigameMessage(player, "go", "<green>GO!</green> <white>Spam <yellow>Sneak</yellow> to reel the fish in.</white>", Map.of());
        sendMinigameTitle(player, "go-title", "<green>GO!</green>", "go-subtitle", "<white>Spam <yellow>Sneak</yellow> now</white>");
        sendProgressMessage(player, session);
        scheduleEscapeCheck(uuid, session);
        scheduleProgressDisplay(uuid, session);
    }

    private void scheduleProgressDisplay(@NotNull UUID uuid, @NotNull MinigameSession expected) {
        int interval = Math.max(1, MainConfig.getInstance().getMinigameProgressDecayIntervalTicks());
        EvenMoreFish.getScheduler().runTaskLater(() -> {
            MinigameSession session = sessions.get(uuid);
            if (session != expected || session.state != MinigameState.PULLING) {
                return;
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                sessions.remove(uuid);
                return;
            }
            applyIdleProgressDecay(player, session);
            if (applyFishEscapePressure(player, session)) {
                return;
            }
            sendProgressMessage(player, session);
            scheduleProgressDisplay(uuid, expected);
        }, interval);
    }

    private boolean canUseHeldCustomRod(@NotNull Player player) {
        ItemStack mainHand = player.getInventory().getItem(EquipmentSlot.HAND);
        if (mainHand != null && mainHand.getType() == Material.FISHING_ROD && Checks.canUseRod(mainHand)) {
            return true;
        }
        ItemStack offHand = player.getInventory().getItem(EquipmentSlot.OFF_HAND);
        return offHand != null && offHand.getType() == Material.FISHING_ROD && Checks.canUseRod(offHand);
    }

    private void expireWaitingHook(@NotNull UUID uuid, @NotNull MinigameSession expected) {
        MinigameSession session = sessions.get(uuid);
        if (session != expected || session.state != MinigameState.WAITING_HOOK) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        sessions.remove(uuid);
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        if (player != null) {
            playMinigameSound(player, "escaped", "ENTITY_ITEM_BREAK", 0.8F);
            sendMinigameMessage(player, "escaped", "<red>The fish broke free.</red>", Map.of());
        }
    }

    private void scheduleEscapeCheck(@NotNull UUID uuid, @NotNull MinigameSession expected) {
        int delay = Math.max(1, MainConfig.getInstance().getMinigameEscapeTimeSeconds()) * 20;
        long lastPull = expected.lastPullMillis;
        EvenMoreFish.getScheduler().runTaskLater(() -> {
            MinigameSession session = sessions.get(uuid);
            if (session != expected || session.state != MinigameState.PULLING) {
                return;
            }
            if (session.lastPullMillis != lastPull) {
                return;
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                failSession(player, session);
            } else {
                sessions.remove(uuid);
            }
        }, delay);
    }

    private void applyResistance(@NotNull Player player, @NotNull MinigameSession session) {
        String rarityId = session.fish.getRarity().getId();
        double chance = MainConfig.getInstance().getMinigameResistanceChance(rarityId);
        if (session.rod != null) {
            chance = Math.max(0.0D, chance - session.rod.getResistanceReduction());
        }
        if (plugin.getRandom().nextDouble() * 100.0D > chance) {
            return;
        }
        int loss = MainConfig.getInstance().getMinigameResistanceLoss(rarityId);
        session.progress = Math.max(0, session.progress - loss);
        session.fishProgress = Math.min(MainConfig.getInstance().getMinigameProgressNeeded(), session.fishProgress + MainConfig.getInstance().getMinigameFishEscapeResistanceGain(rarityId));
        playMinigameSound(player, "resistance", "ENTITY_FISHING_BOBBER_SPLASH", 0.75F);
        pushBobberAway(player, session);
        sendMinigameMessage(player, "resistance", "<red>The fish is fighting back!</red> <gray>Pull progress -{loss}%.</gray>", Map.of(
            "{loss}", String.valueOf(loss)
        ));
    }

    private void applyIdleProgressDecay(@NotNull Player player, @NotNull MinigameSession session) {
        int delayMillis = Math.max(0, MainConfig.getInstance().getMinigameProgressDecayDelayMillis());
        if (System.currentTimeMillis() - session.lastPullMillis < delayMillis) {
            return;
        }
        int decay = Math.max(0, MainConfig.getInstance().getMinigameProgressDecayAmount());
        if (decay <= 0 || session.progress <= 0) {
            return;
        }
        session.progress = Math.max(0, session.progress - decay);
        pushBobberAway(player, session);
    }

    private boolean applyFishEscapePressure(@NotNull Player player, @NotNull MinigameSession session) {
        double gain = Math.max(0.0D, MainConfig.getInstance().getMinigameFishEscapeGain(session.fish.getRarity().getId()));
        if (gain <= 0.0D) {
            return false;
        }
        session.fishProgress = Math.min(MainConfig.getInstance().getMinigameProgressNeeded(), session.fishProgress + gain);
        if (session.fishProgress >= MainConfig.getInstance().getMinigameProgressNeeded()) {
            failFishRace(player, session);
            return true;
        }
        return false;
    }

    private void reduceFishPressureOnPull(@NotNull MinigameSession session) {
        double reduction = Math.max(0.0D, MainConfig.getInstance().getMinigameFishEscapePullReduction());
        if (reduction <= 0.0D || session.fishProgress <= 0.0D) {
            return;
        }
        session.fishProgress = Math.max(0.0D, session.fishProgress - reduction);
    }

    private void pullBobberTowardPlayer(@NotNull Player player, @NotNull MinigameSession session) {
        Vector direction = player.getLocation().toVector().subtract(session.hook.getLocation().toVector());
        if (direction.lengthSquared() <= 0.01D) {
            return;
        }
        session.hook.setVelocity(direction.normalize().multiply(MainConfig.getInstance().getMinigameBobberPullStrength()));
    }

    private void pushBobberAway(@NotNull Player player, @NotNull MinigameSession session) {
        Vector direction = session.hook.getLocation().toVector().subtract(player.getLocation().toVector());
        if (direction.lengthSquared() <= 0.01D) {
            return;
        }
        session.hook.setVelocity(direction.normalize().multiply(0.12D));
    }

    private void completeSession(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        ItemStack fish = finalizeCaughtFish(player, session.hook.getLocation(), session.fish);
        if (fish == null || fish.isEmpty()) {
            return;
        }
        if (MainConfig.getInstance().isGiveStraightToInventory() && FishUtils.inventoryHasSpace(player.getInventory())) {
            FishUtils.giveItem(fish, player);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), fish);
        }
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        playMinigameSound(player, "caught", "ENTITY_PLAYER_LEVELUP", 1.2F);
        sendMinigameMessage(player, "caught", "<green>Catch secured.</green> <white>The fish has been reeled in.</white>", Map.of());
    }

    private void failSession(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        playMinigameSound(player, "escaped", "ENTITY_ITEM_BREAK", 0.8F);
        sendMinigameMessage(player, "escaped", "<red>The fish broke free.</red>", Map.of());
    }

    private void failFishRace(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        playMinigameSound(player, "fish-win", "ENTITY_FISHING_BOBBER_SPLASH", 0.55F);
        sendMinigameMessage(player, "fish-win", "<red>The fish overpowered your line.</red>", Map.of());
        sendMinigameTitle(player, "fish-win-title", "<red>LINE LOST</red>", "fish-win-subtitle", "<gray>The fish escaped first.</gray>");
    }

    private void sendProgressMessage(@NotNull Player player, @NotNull MinigameSession session) {
        int needed = Math.max(1, MainConfig.getInstance().getMinigameProgressNeeded());
        int progress = Math.max(0, Math.min(needed, session.progress));
        int pullPercent = (int) Math.round((progress * 100.0D) / needed);
        int fishPercent = (int) Math.round((Math.max(0.0D, Math.min(needed, session.fishProgress)) * 100.0D) / needed);
        sendMinigameMessage(player, "progress", "<gray>[</gray> {rarity} <gray>]</gray> <green>CATCH</green> {bar} <red>TENSION</red> {fish_bar}", Map.of(
            "{rarity}", getRarityLabel(session),
            "{bar}", getProgressBar(pullPercent, "<green>"),
            "{fish_bar}", getProgressBar(fishPercent, "<red>")
        ));
    }

    private @NotNull String getRarityLabel(@NotNull MinigameSession session) {
        String rarityId = session.fish.getRarity().getId();
        String color = switch (rarityId.toLowerCase(java.util.Locale.ROOT)) {
            case "junk" -> "<gray>";
            case "common" -> "<green>";
            case "rare" -> "<aqua>";
            case "epic" -> "<light_purple>";
            case "legendary" -> "<gold>";
            case "mythic" -> "<dark_purple>";
            case "divine" -> "<yellow>";
            default -> "<white>";
        };
        return color + rarityId.toUpperCase(java.util.Locale.ROOT);
    }

    private @NotNull String getProgressBar(int percent, @NotNull String fillColor) {
        int totalBars = 7;
        int filled = Math.max(0, Math.min(totalBars, (int) Math.round(percent / (100.0D / totalBars))));
        StringBuilder builder = new StringBuilder("<dark_gray>[");
        builder.append(fillColor);
        for (int i = 0; i < filled; i++) {
            builder.append('|');
        }
        builder.append("<gray>");
        for (int i = filled; i < totalBars; i++) {
            builder.append('|');
        }
        builder.append("<dark_gray>]");
        return builder.toString();
    }

    private void playMinigameSound(@NotNull Player player, @NotNull String key, @NotNull String fallback, float pitch) {
        if (!MainConfig.getInstance().isMinigameSoundEnabled()) {
            return;
        }
        String soundName = MainConfig.getInstance().getMinigameSound(key, fallback);
        try {
            player.playSound(
                player.getLocation(),
                Sound.valueOf(soundName),
                SoundCategory.PLAYERS,
                MainConfig.getInstance().getMinigameSoundVolume(),
                pitch
            );
        } catch (IllegalArgumentException ignored) {
            plugin.debug("Invalid custom fishing minigame sound: %s".formatted(soundName));
        }
    }

    private void sendMinigameTitle(@NotNull Player player, @NotNull String titleKey, @NotNull String titleFallback, @NotNull String subtitleKey, @NotNull String subtitleFallback) {
        sendMinigameTitle(player, titleKey, titleFallback, subtitleKey, subtitleFallback, Map.of());
    }

    private void sendMinigameTitle(@NotNull Player player, @NotNull String titleKey, @NotNull String titleFallback, @NotNull String subtitleKey, @NotNull String subtitleFallback, @NotNull Map<String, String> variables) {
        String configuredTitle = MainConfig.getInstance().getMinigameMessage(titleKey, titleFallback);
        String configuredSubtitle = MainConfig.getInstance().getMinigameMessage(subtitleKey, subtitleFallback);
        EMFSingleMessage title = EMFSingleMessage.fromString(configuredTitle);
        EMFSingleMessage subtitle = EMFSingleMessage.fromString(configuredSubtitle);
        variables.forEach(title::setVariable);
        variables.forEach(subtitle::setVariable);
        player.showTitle(Title.title(
            title.getComponentMessage(player),
            subtitle.getComponentMessage(player),
            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(200))
        ));
    }

    private void sendMinigameMessage(@NotNull Player player, @NotNull String key, @NotNull String fallback, @NotNull Map<String, String> variables) {
        String configured = MainConfig.getInstance().getMinigameMessage(key, fallback);
        EMFSingleMessage message = EMFSingleMessage.fromString(configured);
        variables.forEach(message::setVariable);
        player.sendActionBar(message.getComponentMessage(player));
    }

    @Override
    protected boolean isEnabled() {
        return MainConfig.getInstance().isCatchEnabled();
    }

    @Override
    protected boolean competitionOnlyCheck() {
        Competition active = Competition.getCurrentlyActive();

        if (active != null) {
            return active.getCompetitionFile().isAllowFishing();
        }

        return !MainConfig.getInstance().isFishCatchOnlyInCompetition();
    }


    @Override
    protected boolean fireEvent(@NotNull Fish fish, @NotNull Player player) {
        return new EMFFishCaughtEvent(fish, player, LocalDateTime.now()).callEvent();
    }

    @Override
    protected ConfigMessage getCaughtMessage() {
        return ConfigMessage.FISH_CAUGHT;
    }

    @Override
    protected ConfigMessage getLengthlessCaughtMessage() {
        return ConfigMessage.FISH_LENGTHLESS_CAUGHT;
    }

    @Override
    protected boolean shouldCatchBait() {
        return true;
    }

    @Override
    public boolean canUseFish(@NotNull Fish fish) {
        return fish.getCatchType().equals(CatchType.CATCH)
                || fish.getCatchType().equals(CatchType.BOTH);
    }

    private @Nullable ItemStack getRod(@NotNull PlayerFishEvent event) {
        Player player = event.getPlayer();

        // Use getHand() only if the state is FISHING
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            EquipmentSlot hand = event.getHand();
            if (hand != null) {
                return player.getInventory().getItem(hand);
            }
            return null; // Defensive fallback, though shouldn't happen if state is FISHING
        }

        // Fallback: check both hands for a rod
        ItemStack mainHand = player.getInventory().getItem(EquipmentSlot.HAND);
        if (mainHand.getType() == Material.FISHING_ROD) {
            return mainHand;
        }

        ItemStack offHand = player.getInventory().getItem(EquipmentSlot.OFF_HAND);
        if (offHand.getType() == Material.FISHING_ROD) {
            return offHand;
        }

        // No rod found
        return null;
    }

    private enum MinigameState {
        WAITING_HOOK,
        READY_COUNTDOWN,
        PULLING
    }

    private static final class MinigameSession {
        private final FishHook hook;
        private final Fish fish;
        private final CustomRod rod;
        private MinigameState state = MinigameState.WAITING_HOOK;
        private int progress = 0;
        private double fishProgress = 0.0D;
        private long lastPullMillis = System.currentTimeMillis();

        private MinigameSession(@NotNull FishHook hook, @NotNull Fish fish, @Nullable CustomRod rod) {
            this.hook = hook;
            this.fish = fish;
            this.rod = rod;
        }
    }

}
