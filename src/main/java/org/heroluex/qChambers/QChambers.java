package org.heroluex.qChambers;

import org.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.heroluex.qChambers.command.QChambersCommand;
import org.heroluex.qChambers.config.QChambersPluginConfig;
import org.heroluex.qChambers.listener.TrialChambersListener;

public final class QChambers extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 30469;

    private QChambersPluginConfig pluginConfig;
    private TrialChambersListener trialChambersListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfiguration();

        this.trialChambersListener = new TrialChambersListener(this);
        getServer().getPluginManager().registerEvents(this.trialChambersListener, this);
        this.trialChambersListener.start();

        PluginCommand pluginCommand = getCommand("qchambers");
        if (pluginCommand != null) {
            QChambersCommand command = new QChambersCommand(this);
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().warning("Could not register the qchambers command.");
        }

        applyTweaksToLoadedChunks();
        new Metrics(this, BSTATS_PLUGIN_ID);
        getLogger().info("qChambers has been enabled.");
    }

    @Override
    public void onDisable() {
        if (this.trialChambersListener != null) {
            this.trialChambersListener.shutdown();
        }
        getLogger().info("qChambers has been disabled.");
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        this.pluginConfig = QChambersPluginConfig.load(this);
    }

    public void reloadPlugin() {
        reloadPluginConfiguration();

        if (this.trialChambersListener != null) {
            this.trialChambersListener.reloadState();
        }

        applyTweaksToLoadedChunks();
    }

    public QChambersPluginConfig getPluginConfig() {
        return this.pluginConfig;
    }

    public void applyTweaksToLoadedChunks() {
        if (this.trialChambersListener == null) {
            return;
        }

        getServer().getWorlds().forEach(world -> {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                this.trialChambersListener.applyTweaks(chunk);
            }
        });
    }

    public void debug(String message) {
        if (this.pluginConfig != null && this.pluginConfig.debug()) {
            getLogger().info("[Debug] " + message);
        }
    }
}
