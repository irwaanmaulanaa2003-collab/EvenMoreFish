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
import org.bukkit.Material;
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

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            sessions.remove(uuid);
            if (!canStartCustomFishing(player, rod, true)) {
                if (MainConfig.getInstance().blockVanillaRodWhenCustomRequired()) {
                    event.setCancelled(true);
                }
                return;
            }
            return;
        }

        MinigameSession session = sessions.get(uuid);
        if (event.getState() == PlayerFishEvent.State.BITE) {
            if (!canStartCustomFishing(player, rod, false)) {
                return;
            }
            CustomRod customRod = RodManager.getInstance().getRod(rod);
            Fish fish = chooseFish(player, event.getHook().getLocation(), null, customRod);
            if (fish == null) {
                return;
            }
            MinigameSession newSession = new MinigameSession(event.getHook(), fish, customRod);
            sessions.put(uuid, newSession);
            sendMinigameMessage(player, "bite", "<yellow>Fish is biting! <white>Right click within <aqua>{time}s</aqua> to hook it.", Map.of(
                "{time}", String.valueOf(MainConfig.getInstance().getMinigameHookTimeSeconds())
            ));
            int hookTimeoutTicks = Math.max(1, MainConfig.getInstance().getMinigameHookTimeSeconds()) * 20;
            EvenMoreFish.getScheduler().runTaskLater(() -> expireWaitingHook(player.getUniqueId(), newSession), hookTimeoutTicks);
            return;
        }

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH || event.getState() == PlayerFishEvent.State.REEL_IN) {
            if (session != null || MainConfig.getInstance().blockVanillaRodWhenCustomRequired()) {
                event.setCancelled(true);
                if (event.getCaught() instanceof Item item) {
                    item.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        ItemStack item = event.getItem();
        if (!Checks.canUseRod(item)) {
            return;
        }
        event.setCancelled(true);
        session.state = MinigameState.PULLING;
        session.lastPullMillis = System.currentTimeMillis();
        sendMinigameMessage(player, "hooked", "<aqua>Hooked!</aqua> <white>Spam <yellow>Shift</yellow> to pull the fish.", Map.of());
        scheduleEscapeCheck(player.getUniqueId(), session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneakPull(@NotNull PlayerToggleSneakEvent event) {
        if (!MainConfig.getInstance().isCustomMinigameEnabled() || !event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        MinigameSession session = sessions.get(player.getUniqueId());
        if (session == null || session.state != MinigameState.PULLING) {
            return;
        }
        if (session.hook == null || !session.hook.isValid()) {
            failSession(player, session);
            return;
        }

        session.lastPullMillis = System.currentTimeMillis();
        int pullPower = Math.max(1, session.rod == null ? 3 : session.rod.getPullPower());
        session.progress = Math.min(MainConfig.getInstance().getMinigameProgressNeeded(), session.progress + pullPower);
        applyResistance(player, session);
        pullBobberTowardPlayer(player, session);
        sendMinigameMessage(player, "progress", "<aqua>Pulling:</aqua> <white>{progress}%</white> <gray>| Fish: {fish}</gray>", Map.of(
            "{progress}", String.valueOf(Math.max(0, session.progress)),
            "{fish}", session.fish.getDisplayName().getPlainTextMessage(player)
        ));

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
                sendMinigameMessage(player, "vanilla-rod-blocked", "<red>You need a custom fishing rod to fish here.</red>", Map.of());
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
            sendMinigameMessage(player, "escaped", "<red>The fish escaped.</red>", Map.of());
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
        pushBobberAway(player, session);
        sendMinigameMessage(player, "resistance", "<red>The fish fought back!</red> <gray>-{loss}% progress.</gray>", Map.of(
            "{loss}", String.valueOf(loss)
        ));
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
        sendMinigameMessage(player, "caught", "<green>You successfully pulled the fish in!</green>", Map.of());
    }

    private void failSession(@NotNull Player player, @NotNull MinigameSession session) {
        sessions.remove(player.getUniqueId());
        if (session.hook != null && session.hook.isValid()) {
            session.hook.remove();
        }
        sendMinigameMessage(player, "escaped", "<red>The fish escaped.</red>", Map.of());
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
        PULLING
    }

    private static final class MinigameSession {
        private final FishHook hook;
        private final Fish fish;
        private final CustomRod rod;
        private MinigameState state = MinigameState.WAITING_HOOK;
        private int progress = 0;
        private long lastPullMillis = System.currentTimeMillis();

        private MinigameSession(@NotNull FishHook hook, @NotNull Fish fish, @Nullable CustomRod rod) {
            this.hook = hook;
            this.fish = fish;
            this.rod = rod;
        }
    }

}
