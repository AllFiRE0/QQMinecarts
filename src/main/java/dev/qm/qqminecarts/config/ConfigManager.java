package dev.qm.qqminecarts.config;

import dev.qm.qqminecarts.QQMinecarts;

import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Хранит и перезагружает конфигурацию и языковые сообщения плагина.
 * Обе ссылки — volatile, поэтому подменяются атомарно и безопасно
 * читаются из любых потоков (поток региона, асинхронный поток и т.д.).
 */
public final class ConfigManager {

    private final QQMinecarts plugin;

    /** Текущая конфигурация. */
    private volatile PluginConfig config;
    /** Текущие языковые сообщения. */
    private volatile Messages messages;

    public ConfigManager(QQMinecarts plugin) {
        this.plugin = plugin;
    }

    /** Первичная загрузка при старте плагина. */
    public void load() {
        config = PluginConfig.load(plugin);
        messages = Messages.load(plugin, config.language());
        plugin.debug("Конфигурация загружена (язык = " + config.language() + ")");
    }

    public File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    /**
     * Асинхронная перезагрузка конфигурации.
     * Файлы с диска читаются в пуле потоков {@link org.bukkit.Bukkit#getAsyncScheduler()}
     * (это НЕ основной поток), результат применяется через GlobalRegionScheduler.
     *
     * @param callback вызывается в global-region потоке с результатом (true = успех)
     */
    public void reloadAsync(Consumer<Boolean> callback) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> {
            boolean ok = false;
            PluginConfig newConfig = null;
            Messages newMessages = null;
            try {
                newConfig = PluginConfig.load(plugin);
                newMessages = Messages.load(plugin, newConfig.language());
                ok = true;
                plugin.debug("Конфигурация прочитана в асинхронном потоке");
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка перезагрузки конфигурации", t);
            }

            final PluginConfig cfg = newConfig;
            final Messages msg = newMessages;
            final boolean success = ok;

            // Применяем результат и уведомляем вызывающего в global-region потоке
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                if (success && cfg != null && msg != null) {
                    config = cfg;
                    messages = msg;
                    plugin.debug("Новая конфигурация применена");
                }
                if (callback != null) {
                    callback.accept(success);
                }
            });
        });
    }
}