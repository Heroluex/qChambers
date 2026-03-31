package org.heroluex.qChambers.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record QChambersPluginConfig(
        boolean debug,
        int scanIntervalTicks,
        SpawnerSettings spawnerSettings,
        VaultSettings vaultSettings
) {
    public static QChambersPluginConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return new QChambersPluginConfig(
                config.getBoolean("settings.debug", false),
                Math.max(1, config.getInt("settings.scan-interval-ticks", 20)),
                loadSpawnerSettings(config),
                loadVaultSettings(config)
        );
    }

    private static SpawnerSettings loadSpawnerSettings(FileConfiguration config) {
        return new SpawnerSettings(
                config.getBoolean("spawner.enabled", true),
                new CooldownSettings(
                        config.getBoolean("spawner.cooldown.enabled", true),
                        Math.max(0, config.getInt("spawner.cooldown.length-seconds", 90)) * 20
                ),
                new SpawnerHologramSettings(
                        config.getBoolean("spawner.hologram.enabled", true),
                        Math.max(0.0D, config.getDouble("spawner.hologram.height-offset", 1.35D)),
                        config.getBoolean("spawner.hologram.show-ready", false),
                        config.getString("spawner.hologram.ready-text", "&aSpawner ready"),
                        config.getString("spawner.hologram.cooldown-text", "&6Spawner cooldown\\n&f{time}"),
                        config.getString("spawner.hologram.in-use-text", "&6Spawner in use")
                )
        );
    }

    private static VaultSettings loadVaultSettings(FileConfiguration config) {
        return new VaultSettings(
                config.getBoolean("vault.enabled", true),
                config.getInt("vault.max-uses-per-player", 3),
                Math.max(0L, config.getLong("vault.usage-cooldown-seconds", 300)) * 1000L,
                new VaultHologramSettings(
                        config.getBoolean("vault.hologram.enabled", true),
                        Math.max(0.0D, config.getDouble("vault.hologram.height-offset", 1.35D)),
                        Math.max(1.0D, config.getDouble("vault.hologram.max-view-distance", 10.0D)),
                        config.getString("vault.hologram.available-text", "&bVault\\n&fUses left: &e{remaining}/{max}"),
                        config.getString("vault.hologram.cooldown-text", "&bVault\\n&fCooldown: &e{time}\\n&fUses left: &e{remaining}/{max}"),
                        config.getString("vault.hologram.exhausted-text", "&bVault\\n&cNo uses left"),
                        config.getString("vault.hologram.in-use-text", "&bVault\\n&6Currently in use")
                )
        );
    }

    public record SpawnerSettings(
            boolean enabled,
            CooldownSettings cooldown,
            SpawnerHologramSettings hologram
    ) {
    }

    public record CooldownSettings(
            boolean enabled,
            int lengthTicks
    ) {
    }

    public record SpawnerHologramSettings(
            boolean enabled,
            double heightOffset,
            boolean showReady,
            String readyText,
            String cooldownText,
            String inUseText
    ) {
    }

    public record VaultSettings(
            boolean enabled,
            int maxUsesPerPlayer,
            long usageCooldownMillis,
            VaultHologramSettings hologram
    ) {
    }

    public record VaultHologramSettings(
            boolean enabled,
            double heightOffset,
            double maxViewDistance,
            String availableText,
            String cooldownText,
            String exhaustedText,
            String inUseText
    ) {
    }
}
