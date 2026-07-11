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
import com.oheers.fish.fishing.rods.RodUpgradeManager;
import com.oheers.fish.messages.ConfigMessage;
import com.oheers.fish.messages.EMFSingleMessage;
import com.oheers.fish.permissions.UserPerms;
import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.event.player.PlayerAnimationEvent;
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
            // If the vanilla hook fires another bite while our custom minigame is already running,
            // ignore it. This prevents the fight from being reset and asking the player to hook again.
            if (session != null) {
                event.setCancelled(true);
                freezeVanillaBites(session.hook);
                return;
            }
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
            freezeVanillaBites(newSession.hook);
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
        Player player = event.getPlayer();
        MinigameSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (session.state != MinigameState.WAITING_HOOK) {
                return;
            }
            if (!canUseHeldCustomRod(player)) {
                return;
            }
            event.setCancelled(true);
            startReadyCountdown(player, session);
            return;
        }

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            handleTimingClick(player, session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTimingSwing(@NotNull PlayerAnimationEvent event) {
        if (!MainConfig.getInstance().isCustomMinigameEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        MinigameSession session = sessions.get(player.getUniqueId());
        if (session == null || session.state != MinigameState.PULLING) {
            return;
        }
        handleTimingClick(player, session);
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
            sendMinigameMessage(player, "wait-ready", "<yellow>Wait for the <green>GO</green> signal before clicking.</yellow>", Map.of());
            return;
        }
        if (session.state == MinigameState.PULLING) {
            sendMinigameMessage(player, "timing-use-click", "<yellow>Use <white>Left Click</white> when the marker enters the zone.</yellow>", Map.of());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        MinigameSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            clearTimingBossBar(event.getPlayer(), session);
        }
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
        CustomRod customRod = getHeldCustomRod(rod);
        if (customRod != null && !RodUpgradeManager.getInstance().meetsRequirements(player, customRod.getId())) {
            if (notify) {
                sendMinigameMessage(player, "rod-locked", "<red>You do not meet the requirements to use this rod yet.</red>", Map.of());
                for (String missing : RodUpgradeManager.getInstance().getMissingRequirements(player, customRod.getId())) {
                    player.sendMessage("§c- " + missing);
                }
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
        session.captureStartDistance(player);
        freezeVanillaBites(session.hook);
        session.lastPullMillis = System.currentTimeMillis();
        scheduleNextStruggle(session);
        setupTimingGame(player, session);
        playMinigameSound(player, "go", "ENTITY_PLAYER_LEVELUP", 1.0F);
        sendMinigameMessage(player, "go", "<green>GO!</green> <white>Left Click when the marker enters the target zone.</white>", Map.of());
        sendMinigameTitle(player, "go-title", "<green>GO!</green>", "go-subtitle", "<white>Left Click inside the timing zone</white>");
        sendProgressMessage(player, session);
        scheduleEscapeCheck(uuid, session);
        scheduleProgressDisplay(uuid, session);
    }

    private void scheduleProgressDisplay(@NotNull UUID uuid, @NotNull MinigameSession expected) {
        int interval = Math.max(1, MainConfig.getInstance().getMinigameTimingUpdateIntervalTicks());
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
            updateTimingMarker(player, session);
            applyIdleProgressDecay(player, session);
            if (applyStruggleBurst(player, session)) {
                return;
            }
            if (applyFishEscapePressure(player, session)) {
                return;
            }
            syncBobberToProgress(player, session);
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
        if (player != null) {
            clearTimingBossBar(player, session);
        }
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
        session.strugglingUntilMillis = Math.max(session.strugglingUntilMillis, System.currentTimeMillis() + 900L);
        playMinigameSound(player, "resistance", "ENTITY_FISHING_BOBBER_SPLASH", 0.75F);
        pushBobberAway(player, session);
        sendMinigameMessage(player, "resistance", "<red>The fish is fighting back!</red> <gray>Pull progress -{loss}%.</gray>", Map.of(
            "{loss}", String.valueOf(loss)
        ));
    }

    private void applyIdleProgressDecay(@NotNull Player player, @NotNull MinigameSession session) {
        int delayMillis = Math.max(0, MainConfig.getInstance().getMinigameProgressDecayDelayMillis());
        long now = System.currentTimeMillis();
        if (now - session.lastPullMillis < delayMillis) {
            return;
        }
        int decayIntervalMillis = Math.max(1, MainConfig.getInstance().getMinigameProgressDecayIntervalTicks()) * 50;
        if (now - session.lastProgressDecayMillis < decayIntervalMillis) {
            return;
        }
        session.lastProgressDecayMillis = now;
        int decay = Math.max(0, MainConfig.getInstance().getMinigameProgressDecayAmount());
        if (decay <= 0 || session.progress <= 0) {
            return;
        }
        session.progress = Math.max(0, session.progress - decay);
        pushBobberAway(player, session);
    }

    private boolean applyFishEscapePressure(@NotNull Player player, @NotNull MinigameSession session) {
        double gain = Math.max(0.0D, MainConfig.getInstance().getMinigameFishEscapeGain(session.fish.getRarity().getId()));
        // Fish escape gain was balanced around the old 10-tick loop. Scale it when the
        // timing bar is updated more frequently so tension does not become unfair.
        gain *= Math.max(1, MainConfig.getInstance().getMinigameTimingUpdateIntervalTicks()) / 10.0D;
        if (isStruggling(session)) {
            gain *= Math.max(1.0D, MainConfig.getInstance().getMinigameStruggleTensionMultiplier());
        }
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

    private boolean applyStruggleBurst(@NotNull Player player, @NotNull MinigameSession session) {
        if (!MainConfig.getInstance().isMinigameStruggleEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (session.nextStruggleAtMillis <= 0L) {
            scheduleNextStruggle(session);
            return false;
        }
        if (now < session.nextStruggleAtMillis) {
            return false;
        }

        String rarityId = session.fish.getRarity().getId();
        double chance = Math.max(0.0D, MainConfig.getInstance().getMinigameStruggleChance(rarityId));
        if (session.rod != null) {
            chance = Math.max(0.0D, chance - (session.rod.getResistanceReduction() * 0.5D));
        }

        scheduleNextStruggle(session);
        if (plugin.getRandom().nextDouble() * 100.0D > chance) {
            return false;
        }

        int loss = Math.max(0, MainConfig.getInstance().getMinigameStruggleProgressLoss(rarityId));
        double tensionGain = Math.max(0.0D, MainConfig.getInstance().getMinigameStruggleTensionGain(rarityId));
        int needed = Math.max(1, MainConfig.getInstance().getMinigameProgressNeeded());
        session.progress = Math.max(0, session.progress - loss);
        session.fishProgress = Math.min(needed, session.fishProgress + tensionGain);
        session.strugglingUntilMillis = now + Math.max(500, MainConfig.getInstance().getMinigameStruggleDurationMillis());
        pushBobberAway(player, session);
        playMinigameSound(player, "struggle", "ENTITY_FISHING_BOBBER_SPLASH", 0.65F);
        sendMinigameMessage(player, "struggle", "<red>STRUGGLE!</red> <gray>The fish surges against the line.</gray>", Map.of());
        if (session.fishProgress >= needed) {
            failFishRace(player, session);
            return true;
        }
        return false;
    }

    private void setupTimingGame(@NotNull Player player, @NotNull MinigameSession session) {
        randomizeTimingTarget(session);
        session.timingPosition = plugin.getRandom().nextDouble();
        session.timingDirection = plugin.getRandom().nextBoolean() ? 1.0D : -1.0D;
        if (MainConfig.getInstance().isMinigameTimingBossBarEnabled()) {
            BossBar.Color color = getTimingBossBarColor(session);
            session.timingBossBar = BossBar.bossBar(
                EMFSingleMessage.fromString(buildTimingBossBarText(session)).getComponentMessage(player),
                (float) Math.max(0.0D, Math.min(1.0D, session.timingPosition)),
                color,
                BossBar.Overlay.NOTCHED_20
            );
            player.showBossBar(session.timingBossBar);
        }
    }

    private void updateTimingMarker(@NotNull Player player, @NotNull MinigameSession session) {
        double speed = Math.max(0.002D, MainConfig.getInstance().getMinigameTimingSpeed(session.fish.getRarity().getId()));
        if (session.rod != null) {
            // High tier rods make the timing marker a little easier to control.
            speed *= Math.max(0.60D, 1.0D - (session.rod.getResistanceReduction() / 160.0D));
        }
        if (isStruggling(session)) {
            speed *= 1.25D;
        }
        session.timingPosition += speed * session.timingDirection;
        if (session.timingPosition >= 1.0D) {
            session.timingPosition = 1.0D;
            session.timingDirection = -1.0D;
        } else if (session.timingPosition <= 0.0D) {
            session.timingPosition = 0.0D;
            session.timingDirection = 1.0D;
        }
        updateTimingBossBar(player, session);
    }

    private void handleTimingClick(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.state == MinigameState.READY_COUNTDOWN) {
            sendMinigameMessage(player, "wait-ready", "<yellow>Wait for the <green>GO</green> signal before clicking.</yellow>", Map.of());
            return;
        }
        if (session.state != MinigameState.PULLING) {
            return;
        }
        if (session.hook == null || !session.hook.isValid()) {
            failSession(player, session);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastTimingHitMillis < MainConfig.getInstance().getMinigameTimingHitCooldownMillis()) {
            return;
        }
        session.lastTimingHitMillis = now;
        session.lastPullMillis = now;

        double marker = session.timingPosition;
        double distance = Math.abs(marker - session.timingTargetCenter);
        double targetRadius = getTimingTargetSize(session) / 2.0D;
        double perfectRadius = getTimingPerfectSize(session) / 2.0D;
        boolean perfect = distance <= perfectRadius;
        boolean good = distance <= targetRadius;

        if (perfect) {
            applyTimingSuccess(player, session, true);
        } else if (good) {
            applyTimingSuccess(player, session, false);
        } else {
            applyTimingMiss(player, session);
        }

        randomizeTimingTarget(session);
        syncBobberToProgress(player, session);
        updateTimingBossBar(player, session);
        sendProgressMessage(player, session);

        if (session.progress >= MainConfig.getInstance().getMinigameProgressNeeded()) {
            completeSession(player, session);
            return;
        }
        if (session.fishProgress >= MainConfig.getInstance().getMinigameProgressNeeded()) {
            failFishRace(player, session);
            return;
        }
        scheduleEscapeCheck(player.getUniqueId(), session);
    }

    private void applyTimingSuccess(@NotNull Player player, @NotNull MinigameSession session, boolean perfect) {
        int gain = perfect ? MainConfig.getInstance().getMinigameTimingPerfectCatchGain() : MainConfig.getInstance().getMinigameTimingGoodCatchGain();
        if (session.rod != null) {
            gain += Math.max(0, session.rod.getPullPower() / 2);
        }
        double tensionReduction = perfect ? MainConfig.getInstance().getMinigameTimingPerfectTensionReduction() : MainConfig.getInstance().getMinigameTimingGoodTensionReduction();
        int needed = Math.max(1, MainConfig.getInstance().getMinigameProgressNeeded());
        session.progress = Math.min(needed, session.progress + Math.max(1, gain));
        session.fishProgress = Math.max(0.0D, session.fishProgress - Math.max(0.0D, tensionReduction));
        playMinigameSound(player, perfect ? "perfect" : "pull", perfect ? "BLOCK_NOTE_BLOCK_BELL" : "ENTITY_EXPERIENCE_ORB_PICKUP", perfect ? 1.45F : 1.15F);
        sendMinigameMessage(player, perfect ? "perfect-hit" : "good-hit", perfect ? "<green>PERFECT HIT!</green> <white>The line holds strong.</white>" : "<aqua>GOOD HIT!</aqua> <white>Keep the rhythm.</white>", Map.of());
        applyResistance(player, session);
    }

    private void applyTimingMiss(@NotNull Player player, @NotNull MinigameSession session) {
        int loss = Math.max(0, MainConfig.getInstance().getMinigameTimingMissCatchLoss());
        double tensionGain = Math.max(0.0D, MainConfig.getInstance().getMinigameTimingMissTensionGain());
        session.progress = Math.max(0, session.progress - loss);
        session.fishProgress = Math.min(MainConfig.getInstance().getMinigameProgressNeeded(), session.fishProgress + tensionGain);
        session.strugglingUntilMillis = Math.max(session.strugglingUntilMillis, System.currentTimeMillis() + 700L);
        pushBobberAway(player, session);
        playMinigameSound(player, "miss", "BLOCK_NOTE_BLOCK_BASS", 0.7F);
        sendMinigameMessage(player, "miss-hit", "<red>MISSED!</red> <gray>The fish pulls harder.</gray>", Map.of());
    }

    private void randomizeTimingTarget(@NotNull MinigameSession session) {
        double size = getTimingTargetSize(session);
        double min = size / 2.0D;
        double max = 1.0D - min;
        if (max <= min) {
            session.timingTargetCenter = 0.5D;
            return;
        }
        session.timingTargetCenter = min + (plugin.getRandom().nextDouble() * (max - min));
    }

    private double getTimingTargetSize(@NotNull MinigameSession session) {
        double size = MainConfig.getInstance().getMinigameTimingTargetSize(session.fish.getRarity().getId());
        if (session.rod != null) {
            size += session.rod.getPullPower() * 0.0025D;
        }
        return Math.max(0.06D, Math.min(0.45D, size));
    }

    private double getTimingPerfectSize(@NotNull MinigameSession session) {
        return Math.max(0.02D, Math.min(getTimingTargetSize(session), MainConfig.getInstance().getMinigameTimingPerfectSize(session.fish.getRarity().getId())));
    }

    private void updateTimingBossBar(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.timingBossBar == null) {
            return;
        }
        session.timingBossBar.progress((float) Math.max(0.0D, Math.min(1.0D, session.timingPosition)));
        session.timingBossBar.name(EMFSingleMessage.fromString(buildTimingBossBarText(session)).getComponentMessage(player));
        session.timingBossBar.color(getTimingBossBarColor(session));
    }

    private @NotNull BossBar.Color getTimingBossBarColor(@NotNull MinigameSession session) {
        String rarityId = session.fish.getRarity().getId().toLowerCase(java.util.Locale.ROOT);
        return switch (rarityId) {
            case "rare" -> BossBar.Color.BLUE;
            case "epic", "mythic" -> BossBar.Color.PURPLE;
            case "legendary", "divine" -> BossBar.Color.YELLOW;
            default -> BossBar.Color.GREEN;
        };
    }

    private @NotNull String buildTimingBossBarText(@NotNull MinigameSession session) {
        int slots = 15;
        int markerSlot = Math.max(0, Math.min(slots - 1, (int) Math.round(session.timingPosition * (slots - 1))));
        double targetSize = getTimingTargetSize(session);
        int targetStart = Math.max(0, (int) Math.floor((session.timingTargetCenter - (targetSize / 2.0D)) * slots));
        int targetEnd = Math.min(slots - 1, (int) Math.ceil((session.timingTargetCenter + (targetSize / 2.0D)) * slots) - 1);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            if (i == markerSlot) {
                bar.append("<white>|</white>");
            } else if (i >= targetStart && i <= targetEnd) {
                bar.append("<green>█</green>");
            } else {
                bar.append("<dark_gray>█</dark_gray>");
            }
        }
        return "<gray>[</gray> " + getRarityLabel(session) + " <gray>]</gray> <white>LEFT CLICK</white> <gray>" + bar + "</gray>";
    }

    private void clearTimingBossBar(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.timingBossBar != null) {
            player.hideBossBar(session.timingBossBar);
            session.timingBossBar = null;
        }
    }

    private void scheduleNextStruggle(@NotNull MinigameSession session) {
        int minTicks = Math.max(1, MainConfig.getInstance().getMinigameStruggleIntervalMinTicks());
        int maxTicks = Math.max(minTicks, MainConfig.getInstance().getMinigameStruggleIntervalMaxTicks());
        int extraTicks = maxTicks == minTicks ? 0 : plugin.getRandom().nextInt((maxTicks - minTicks) + 1);
        session.nextStruggleAtMillis = System.currentTimeMillis() + ((long) (minTicks + extraTicks) * 50L);
    }

    private boolean isStruggling(@NotNull MinigameSession session) {
        return session.strugglingUntilMillis > System.currentTimeMillis();
    }

    private void reduceFishPressureOnPull(@NotNull MinigameSession session) {
        double reduction = Math.max(0.0D, MainConfig.getInstance().getMinigameFishEscapePullReduction());
        if (reduction <= 0.0D || session.fishProgress <= 0.0D) {
            return;
        }
        session.fishProgress = Math.max(0.0D, session.fishProgress - reduction);
    }

    private void pullBobberTowardPlayer(@NotNull Player player, @NotNull MinigameSession session) {
        syncBobberToProgress(player, session);
    }

    private void syncBobberToProgress(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.hook == null || !session.hook.isValid()) {
            return;
        }
        if (session.startDistance <= 0.0D) {
            session.captureStartDistance(player);
        }

        int needed = Math.max(1, MainConfig.getInstance().getMinigameProgressNeeded());
        double catchRatio = Math.max(0.0D, Math.min(1.0D, session.progress / (double) needed));
        double minDistance = Math.max(0.8D, MainConfig.getInstance().getMinigameBobberMinDistance());
        double startDistance = Math.max(minDistance + 0.25D, session.startDistance);
        double targetDistance = minDistance + ((startDistance - minDistance) * (1.0D - catchRatio));

        Vector hookVector = session.hook.getLocation().toVector();
        Vector playerVector = player.getLocation().toVector();
        double currentDistance = hookVector.distance(playerVector);
        double difference = currentDistance - targetDistance;

        // Do not snap the bobber to the player. Only apply a small correction toward the target
        // distance so the visual movement follows the CATCH bar smoothly.
        if (difference <= 0.05D) {
            return;
        }

        Vector direction = playerVector.subtract(hookVector);
        if (direction.lengthSquared() <= 0.01D) {
            return;
        }
        double speed = Math.min(
            MainConfig.getInstance().getMinigameBobberMaxSpeed(),
            Math.max(0.015D, difference * MainConfig.getInstance().getMinigameBobberSyncStrength())
        );
        session.hook.setVelocity(direction.normalize().multiply(speed));
    }

    private void pushBobberAway(@NotNull Player player, @NotNull MinigameSession session) {
        if (session.hook == null || !session.hook.isValid()) {
            return;
        }
        Vector direction = session.hook.getLocation().toVector().subtract(player.getLocation().toVector());
        if (direction.lengthSquared() <= 0.01D) {
            return;
        }
        session.hook.setVelocity(direction.normalize().multiply(MainConfig.getInstance().getMinigameBobberPushStrength()));
    }

    private void freezeVanillaBites(@Nullable FishHook hook) {
        if (hook == null || !hook.isValid()) {
            return;
        }
        try {
            // Keep the same bobber, but stop vanilla from creating another BITE event during the minigame.
            hook.setMinWaitTime(20 * 60 * 60);
            hook.setMaxWaitTime((20 * 60 * 60) + 20);
        } catch (IllegalArgumentException ignored) {
            plugin.debug("Could not freeze vanilla bite timing for custom fishing minigame.");
        }
    }

    private void completeSession(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        clearTimingBossBar(player, session);
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
        RodUpgradeManager.getInstance().recordCatch(player, session.fish.getRarity().getId());
        playMinigameSound(player, "caught", "ENTITY_PLAYER_LEVELUP", 1.2F);
        sendMinigameMessage(player, "caught", "<green>Catch secured.</green> <white>The fish has been reeled in.</white>", Map.of());
    }

    private void failSession(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        clearTimingBossBar(player, session);
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        playMinigameSound(player, "escaped", "ENTITY_ITEM_BREAK", 0.8F);
        sendMinigameMessage(player, "escaped", "<red>The fish broke free.</red>", Map.of());
    }

    private void failFishRace(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        clearTimingBossBar(player, session);
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
        sendMinigameMessage(player, "progress", "<gray>[</gray> {rarity} <gray>]</gray> <green>CATCH</green> {bar} <red>TENSION</red> {fish_bar} {state}", Map.of(
            "{rarity}", getRarityLabel(session),
            "{bar}", getProgressBar(pullPercent, "<green>"),
            "{fish_bar}", getProgressBar(fishPercent, "<red>"),
            "{state}", isStruggling(session) ? "<red>STRUGGLE!</red>" : ""
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
        private long nextStruggleAtMillis = 0L;
        private long strugglingUntilMillis = 0L;
        private long lastProgressDecayMillis = 0L;
        private long lastTimingHitMillis = 0L;
        private double startDistance = -1.0D;
        private double timingPosition = 0.0D;
        private double timingDirection = 1.0D;
        private double timingTargetCenter = 0.5D;
        private @Nullable BossBar timingBossBar = null;

        private MinigameSession(@NotNull FishHook hook, @NotNull Fish fish, @Nullable CustomRod rod) {
            this.hook = hook;
            this.fish = fish;
            this.rod = rod;
        }

        private void captureStartDistance(@NotNull Player player) {
            if (hook == null || !hook.isValid()) {
                startDistance = -1.0D;
                return;
            }
            startDistance = Math.max(1.0D, hook.getLocation().distance(player.getLocation()));
        }
    }

}
