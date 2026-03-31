package org.heroluex.qChambers.command;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.heroluex.qChambers.QChambers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class QChambersCommand implements CommandExecutor, TabCompleter {
    private final QChambers plugin;

    public QChambersCommand(QChambers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!sender.hasPermission("qchambers.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadPlugin();
            sender.sendMessage(ChatColor.GREEN + "qChambers has been reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " reload");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("qchambers.admin")) {
            return List.of("reload");
        }

        return List.of();
    }
}
