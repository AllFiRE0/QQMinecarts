package dev.qm.qqminecarts.command;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.ConfigManager;
import dev.qm.qqminecarts.manager.SpeedManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Команды плагина: /qqm reload — перезагрузка конфигурации в фоне.
 */
public final class QMCCommand implements CommandExecutor, TabCompleter {

    private final QQMinecarts plugin;
    private final ConfigManager configManager;
    private final SpeedManager speedManager;

    public QMCCommand(QQMinecarts plugin, ConfigManager configManager, SpeedManager speedManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.speedManager = speedManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        try {
            if (args.length == 0) {
                sender.sendMessage(speedManager.messages().get("messages.wrong-usage"));
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload":
                case "rl": {
                    if (!canReload(sender)) {
                        sender.sendMessage(speedManager.messages().get("messages.no-permission"));
                        return true;
                    }
                    sender.sendMessage(speedManager.messages().get("messages.reloading"));
                    // Перезагрузка выполняется в фоновом потоке; колбэк приходит
                    // из global-region потока (не блокирует основной тик сервера).
                    configManager.reloadAsync(ok ->
                            sender.sendMessage(speedManager.messages().get(
                                    ok ? "messages.reloaded" : "messages.reload-failed")));
                    return true;
                }
                default:
                    sender.sendMessage(speedManager.messages().get("messages.wrong-usage"));
                    return true;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Ошибка в команде /" + label + ": " + t.getMessage());
            return true;
        }
    }

    private boolean canReload(CommandSender sender) {
        return sender.isOp()
                || sender.hasPermission("qqminecarts.admin")
                || sender.hasPermission("qqminecarts.reload");
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }
}