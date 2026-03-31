package org.heroluex.qChambers.listener;

import io.papermc.paper.event.block.VaultChangeStateEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TrialSpawner;
import org.bukkit.block.Vault;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.heroluex.qChambers.QChambers;
import org.heroluex.qChambers.config.QChambersPluginConfig;

public final class TrialChambersListener implements Listener {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final QChambers plugin;
    private final NamespacedKey vaultUsageStateKey;
    private final Map<String, TextDisplay> spawnerHolograms = new HashMap<>();
    private final Map<String, PrivateHologram> privateVaultHolograms = new HashMap<>();

    private BukkitTask scannerTask;

    public TrialChambersListener(QChambers plugin) {
        this.plugin = plugin;
        this.vaultUsageStateKey = new NamespacedKey(plugin, "vault_usage_state");
    }

    public void start() {
        restartScannerTask();
    }

    public void reloadState() {
        clearAllHolograms();
        restartScannerTask();
    }

    public void shutdown() {
        if (this.scannerTask != null) {
            this.scannerTask.cancel();
            this.scannerTask = null;
        }

        clearAllHolograms();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        applyTweaks(event.getChunk());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVaultInteract(PlayerInteractEvent event) {
        if (!this.plugin.getPluginConfig().vaultSettings().enabled()) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Vault vault)) {
            return;
        }

        showVaultStatusHologram(event.getPlayer(), vault);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePrivateVaultHolograms(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onVaultChangeState(VaultChangeStateEvent event) {
        if (!this.plugin.getPluginConfig().vaultSettings().enabled() || event.getPlayer() == null) {
            return;
        }

        if (event.getNewState() != org.bukkit.block.data.type.Vault.State.UNLOCKING) {
            return;
        }

        BlockState state = event.getBlock().getState();
        if (!(state instanceof Vault vault)) {
            return;
        }

        Player player = event.getPlayer();
        VaultStatus status = resolveVaultStatus(vault, player.getUniqueId());

        if (status.exhausted()) {
            event.setCancelled(true);
            showVaultStatusHologram(player, vault);
            return;
        }

        if (status.remainingCooldownMillis() > 0L) {
            event.setCancelled(true);
            showVaultStatusHologram(player, vault);
            return;
        }

        long cooldownUntil = this.plugin.getPluginConfig().vaultSettings().usageCooldownMillis() > 0L
                ? System.currentTimeMillis() + this.plugin.getPluginConfig().vaultSettings().usageCooldownMillis()
                : 0L;

        VaultPlayerState updatedState = new VaultPlayerState(status.usedUses() + 1, cooldownUntil);
        Map<UUID, VaultPlayerState> allStates = loadVaultStates(vault);
        allStates.put(player.getUniqueId(), updatedState);
        saveVaultStates(vault, allStates);

        showVaultStatusHologram(player, vault);

        int maxUses = this.plugin.getPluginConfig().vaultSettings().maxUsesPerPlayer();
        if (maxUses <= 0 || updatedState.usedUses() < maxUses) {
            scheduleVaultReuseReset(event.getBlock(), player.getUniqueId());
        }
    }

    public void applyTweaks(Chunk chunk) {
        QChambersPluginConfig.SpawnerSettings spawnerSettings = this.plugin.getPluginConfig().spawnerSettings();
        if (!spawnerSettings.enabled()) {
            return;
        }

        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof TrialSpawner spawner) {
                applySpawnerTweaks(spawner, spawnerSettings);
            }
        }
    }

    private void restartScannerTask() {
        if (this.scannerTask != null) {
            this.scannerTask.cancel();
        }

        long interval = this.plugin.getPluginConfig().scanIntervalTicks();
        this.scannerTask = this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin,
                this::scanSpawnerCooldownHolograms,
                interval,
                interval
        );
    }

    private void scanSpawnerCooldownHolograms() {
        QChambersPluginConfig.SpawnerSettings spawnerSettings = this.plugin.getPluginConfig().spawnerSettings();
        if (!spawnerSettings.enabled() || !spawnerSettings.hologram().enabled()) {
            clearSpawnerHolograms();
            return;
        }

        Set<String> activeKeys = new HashSet<>();

        this.plugin.getServer().getWorlds().forEach(world -> {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof TrialSpawner spawner) {
                        applySpawnerTweaks(spawner, spawnerSettings);
                        updateSpawnerHologram(spawner, activeKeys);
                    }
                }
            }
        });

        cleanupPrivateVaultHolograms();

        this.spawnerHolograms.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }

            entry.getValue().remove();
            return true;
        });
    }

    private void updateSpawnerHologram(TrialSpawner spawner, Set<String> activeKeys) {
        QChambersPluginConfig.SpawnerSettings spawnerSettings = this.plugin.getPluginConfig().spawnerSettings();
        QChambersPluginConfig.CooldownSettings cooldownSettings = spawnerSettings.cooldown();
        QChambersPluginConfig.SpawnerHologramSettings hologramSettings = spawnerSettings.hologram();
        org.bukkit.block.data.type.TrialSpawner spawnerData = (org.bukkit.block.data.type.TrialSpawner) spawner.getBlock().getBlockData();
        org.bukkit.block.data.type.TrialSpawner.State spawnerState = spawnerData.getTrialSpawnerState();

        String key = blockKey(spawner.getBlock());
        long remainingTicks = Math.max(0L, spawner.getCooldownEnd() - spawner.getWorld().getGameTime());
        boolean inUse = isSpawnerInUse(spawnerState);

        if (!inUse && !cooldownSettings.enabled() && !hologramSettings.showReady()) {
            removeSpawnerHologram(key);
            return;
        }

        if (!inUse && remainingTicks <= 0L && !hologramSettings.showReady()) {
            removeSpawnerHologram(key);
            return;
        }

        activeKeys.add(key);

        String rawText;
        if (inUse) {
            rawText = hologramSettings.inUseText();
        } else if (cooldownSettings.enabled() && remainingTicks > 0L) {
            rawText = hologramSettings.cooldownText().replace("{time}", formatDurationTicks(remainingTicks));
        } else {
            rawText = hologramSettings.readyText();
        }

        TextDisplay display = this.spawnerHolograms.computeIfAbsent(key, ignored -> createGlobalTextDisplay(
                spawner.getBlock().getLocation().toCenterLocation().add(0.0D, hologramSettings.heightOffset(), 0.0D)
        ));

        display.teleport(spawner.getBlock().getLocation().toCenterLocation().add(0.0D, hologramSettings.heightOffset(), 0.0D));
        display.text(LEGACY_SERIALIZER.deserialize(rawText));
    }

    private void applySpawnerTweaks(TrialSpawner spawner, QChambersPluginConfig.SpawnerSettings settings) {
        boolean changed = false;
        long currentTime = spawner.getWorld().getGameTime();
        int targetCooldownLength = settings.cooldown().enabled() ? settings.cooldown().lengthTicks() : 0;
        org.bukkit.block.data.type.TrialSpawner spawnerData = (org.bukkit.block.data.type.TrialSpawner) spawner.getBlock().getBlockData();
        boolean inCooldown = spawnerData.getTrialSpawnerState() == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN;

        if (spawner.getCooldownLength() != targetCooldownLength) {
            spawner.setCooldownLength(targetCooldownLength);
            changed = true;

            if (settings.cooldown().enabled() && inCooldown) {
                spawner.setCooldownEnd(currentTime + targetCooldownLength);
            }
        }

        if (!settings.cooldown().enabled() && spawner.getCooldownEnd() > currentTime) {
            spawner.setCooldownEnd(currentTime);
            changed = true;
        }

        if (changed) {
            spawner.update(true, false);
        }
    }

    private boolean isSpawnerInUse(org.bukkit.block.data.type.TrialSpawner.State spawnerState) {
        return spawnerState == org.bukkit.block.data.type.TrialSpawner.State.ACTIVE
                || spawnerState == org.bukkit.block.data.type.TrialSpawner.State.WAITING_FOR_REWARD_EJECTION
                || spawnerState == org.bukkit.block.data.type.TrialSpawner.State.EJECTING_REWARD;
    }

    private void showVaultStatusHologram(Player player, Vault vault) {
        QChambersPluginConfig.VaultSettings vaultSettings = this.plugin.getPluginConfig().vaultSettings();
        QChambersPluginConfig.VaultHologramSettings hologramSettings = vaultSettings.hologram();
        if (!vaultSettings.enabled() || !hologramSettings.enabled()) {
            return;
        }

        VaultStatus status = resolveVaultStatus(vault, player.getUniqueId());
        String maxUsesText = vaultSettings.maxUsesPerPlayer() <= 0 ? "unlimited" : Integer.toString(vaultSettings.maxUsesPerPlayer());
        String remainingUsesText = status.remainingUses() < 0 ? "unlimited" : Integer.toString(status.remainingUses());
        org.bukkit.block.data.type.Vault vaultData = (org.bukkit.block.data.type.Vault) vault.getBlock().getBlockData();

        String template;
        if (isVaultInUse(vaultData)) {
            template = hologramSettings.inUseText();
        } else if (status.exhausted()) {
            template = hologramSettings.exhaustedText();
        } else if (status.remainingCooldownMillis() > 0L) {
            template = hologramSettings.cooldownText();
        } else {
            template = hologramSettings.availableText();
        }

        String text = template
                .replace("{time}", formatDurationMillis(status.remainingCooldownMillis()))
                .replace("{remaining}", remainingUsesText)
                .replace("{max}", maxUsesText);

        Location location = vault.getBlock().getLocation().toCenterLocation().add(0.0D, hologramSettings.heightOffset(), 0.0D);
        String key = privateVaultHologramKey(player.getUniqueId(), vault.getBlock());
        PrivateHologram existing = this.privateVaultHolograms.remove(key);
        if (existing != null) {
            existing.remove();
        }

        TextDisplay display = createPrivateTextDisplay(location, player);
        display.text(LEGACY_SERIALIZER.deserialize(text));

        this.privateVaultHolograms.put(key, new PrivateHologram(display, player.getUniqueId()));
    }

    private boolean isVaultInUse(org.bukkit.block.data.type.Vault vaultData) {
        return vaultData.getVaultState() == org.bukkit.block.data.type.Vault.State.UNLOCKING
                || vaultData.getVaultState() == org.bukkit.block.data.type.Vault.State.EJECTING;
    }

    private VaultStatus resolveVaultStatus(Vault vault, UUID playerId) {
        QChambersPluginConfig.VaultSettings vaultSettings = this.plugin.getPluginConfig().vaultSettings();
        Map<UUID, VaultPlayerState> states = loadVaultStates(vault);
        VaultPlayerState state = states.getOrDefault(playerId, new VaultPlayerState(0, 0L));

        long remainingCooldownMillis = Math.max(0L, state.cooldownUntilEpochMillis() - System.currentTimeMillis());
        int maxUses = vaultSettings.maxUsesPerPlayer();

        if (maxUses <= 0) {
            return new VaultStatus(state.usedUses(), -1, remainingCooldownMillis, false);
        }

        int remainingUses = Math.max(0, maxUses - state.usedUses());
        boolean exhausted = state.usedUses() >= maxUses;
        return new VaultStatus(state.usedUses(), remainingUses, remainingCooldownMillis, exhausted);
    }

    private void scheduleVaultReuseReset(Block block, UUID playerId) {
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            BlockState currentState = block.getState();
            if (!(currentState instanceof Vault vault)) {
                return;
            }

            if (vault.removeRewardedPlayer(playerId)) {
                vault.update(true, false);
                this.plugin.debug("Re-enabled vault usage for " + playerId + ".");
            }
        }, 20L);
    }

    private Map<UUID, VaultPlayerState> loadVaultStates(Vault vault) {
        PersistentDataContainer container = vault.getPersistentDataContainer();
        String serialized = container.get(this.vaultUsageStateKey, PersistentDataType.STRING);
        Map<UUID, VaultPlayerState> states = new HashMap<>();

        if (serialized == null || serialized.isBlank()) {
            return states;
        }

        String[] entries = serialized.split(";");
        for (String entry : entries) {
            if (entry.isBlank()) {
                continue;
            }

            String[] parts = entry.split("\\|");
            if (parts.length != 3) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(parts[0]);
                int usedUses = Integer.parseInt(parts[1]);
                long cooldownUntilEpochMillis = Long.parseLong(parts[2]);
                states.put(playerId, new VaultPlayerState(usedUses, cooldownUntilEpochMillis));
            } catch (IllegalArgumentException ignored) {
                this.plugin.debug("Ignored invalid vault state entry: " + entry);
            }
        }

        return states;
    }

    private void saveVaultStates(Vault vault, Map<UUID, VaultPlayerState> states) {
        StringBuilder serialized = new StringBuilder();

        states.forEach((playerId, state) -> {
            if (serialized.length() > 0) {
                serialized.append(';');
            }

            serialized.append(playerId)
                    .append('|')
                    .append(state.usedUses())
                    .append('|')
                    .append(state.cooldownUntilEpochMillis());
        });

        vault.getPersistentDataContainer().set(this.vaultUsageStateKey, PersistentDataType.STRING, serialized.toString());
        vault.update(true, false);
    }

    private TextDisplay createGlobalTextDisplay(Location location) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class);
        configureDisplay(display);
        return display;
    }

    private TextDisplay createPrivateTextDisplay(Location location, Player player) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class);
        configureDisplay(display);
        display.setVisibleByDefault(false);
        player.showEntity(this.plugin, display);
        return display;
    }

    private void configureDisplay(TextDisplay display) {
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setDefaultBackground(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
    }

    private void removeSpawnerHologram(String key) {
        TextDisplay display = this.spawnerHolograms.remove(key);
        if (display != null) {
            display.remove();
        }
    }

    private void clearSpawnerHolograms() {
        this.spawnerHolograms.values().forEach(TextDisplay::remove);
        this.spawnerHolograms.clear();
    }

    private void clearAllHolograms() {
        clearSpawnerHolograms();

        this.privateVaultHolograms.values().forEach(PrivateHologram::remove);
        this.privateVaultHolograms.clear();
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String privateVaultHologramKey(UUID playerId, Block block) {
        return playerId + ":" + blockKey(block);
    }

    private String formatDurationTicks(long ticks) {
        long totalSeconds = Math.max(0L, (ticks + 19L) / 20L);
        return formatDurationSeconds(totalSeconds);
    }

    private String formatDurationMillis(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        return formatDurationSeconds(totalSeconds);
    }

    private String formatDurationSeconds(long totalSeconds) {
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        }

        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        }

        return String.format(Locale.ROOT, "%ds", seconds);
    }

    private void cleanupPrivateVaultHolograms() {
        double maxDistance = this.plugin.getPluginConfig().vaultSettings().hologram().maxViewDistance();
        double maxDistanceSquared = maxDistance * maxDistance;

        this.privateVaultHolograms.entrySet().removeIf(entry -> {
            PrivateHologram hologram = entry.getValue();
            Player player = this.plugin.getServer().getPlayer(hologram.playerId());

            if (player == null || !player.isOnline()) {
                hologram.remove();
                return true;
            }

            if (!player.getWorld().equals(hologram.display().getWorld())
                    || player.getLocation().distanceSquared(hologram.display().getLocation()) > maxDistanceSquared) {
                hologram.remove();
                return true;
            }

            return false;
        });
    }

    private void removePrivateVaultHolograms(UUID playerId) {
        this.privateVaultHolograms.entrySet().removeIf(entry -> {
            if (!entry.getValue().playerId().equals(playerId)) {
                return false;
            }

            entry.getValue().remove();
            return true;
        });
    }

    private record VaultPlayerState(
            int usedUses,
            long cooldownUntilEpochMillis
    ) {
    }

    private record VaultStatus(
            int usedUses,
            int remainingUses,
            long remainingCooldownMillis,
            boolean exhausted
    ) {
    }

    private record PrivateHologram(
            TextDisplay display,
            UUID playerId
    ) {
        private void remove() {
            this.display.remove();
        }
    }
}
