package dev.qm.qqminecarts;

import dev.qm.qqminecarts.command.QMCCommand;
import dev.qm.qqminecarts.config.ConfigManager;
import dev.qm.qqminecarts.listener.InputListener;
import dev.qm.qqminecarts.listener.VehicleListener;
import dev.qm.qqminecarts.manager.SpeedManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;

/**
 * Главный класс плагина QQMinecarts.
 *
 * Плагин добавляет механики скоростных вагонеток:
 * - клавиша F или ЛКМ в воздухе — переключение скорости по кругу
 *   (default -> 1 -> 2 -> ... -> max -> default);
 * - скорость меняется плавно: задержка (delay-ticks), затем шаги
 *   (step каждые interval-ticks);
 * - на уровне "default" работает ванильное управление с клавиши W;
 * - на уровнях 1+ вагонетка едет сама.
 *
 * Требования к версиям: Paper/Leaf 1.21.11 и новее (проверено на 26.2 локальной
 * сборке Leaf). Вся "тяжёлая" работа выполняется не в основном потоке:
 * тики вагонетки — через EntityScheduler (поток региона на новой модели),
 * перезагрузка конфигурации — через AsyncScheduler + GlobalRegionScheduler.
 */
public final class QQMinecarts extends JavaPlugin {

    private ConfigManager configManager;
    private SpeedManager speedManager;

    @Override
    public void onEnable() {
        long start = System.nanoTime();
        try {
            // Копируем дефолтные файлы, если их ещё нет
            saveDefaultConfig();
            saveLanguages();

            configManager = new ConfigManager(this);
            configManager.load();

            speedManager = new SpeedManager(this, configManager);

            // Слушатели
            getServer().getPluginManager().registerEvents(new VehicleListener(speedManager), this);
            getServer().getPluginManager().registerEvents(new InputListener(this, speedManager), this);

            // Команды
            PluginCommand command = getCommand("qqm");
            if (command != null) {
                QMCCommand executor = new QMCCommand(this, configManager, speedManager);
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }

            debug("Запуск завершён за " + (System.nanoTime() - start) / 1_000_000 + " мс");
            getLogger().info("QQMinecarts включён. Переключение скорости: клавиша F / ЛКМ в вагонетке.");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Критическая ошибка при включении QQMinecarts", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (speedManager != null) {
            speedManager.shutdown();
        }
    }

    // ================== ДОСТУП ==================

    public ConfigManager configManager() {
        return configManager;
    }

    public SpeedManager speedManager() {
        return speedManager;
    }

    /**
     * Отладочный вывод в консоль. Работает только при debug: true в config.yml.
     * Безопасен для вызова из любого потока.
     */
    public void debug(String message) {
        if (configManager != null
                && configManager.config() != null
                && configManager.config().debug()) {
            getLogger().info("[Debug] " + message);
        }
    }

    // ================== РЕСУРСЫ ==================

    /** Сохраняем встроенные языковые файлы (lang/ru.yml, lang/en.yml). */
    private void saveLanguages() {
        File dir = new File(getDataFolder(), "lang");
        for (String name : new String[]{"ru", "en"}) {
            File target = new File(dir, name + ".yml");
            if (target.isFile()) {
                continue;
            }
            try (InputStream in = getResource("lang/" + name + ".yml")) {
                if (in != null) {
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        getLogger().warning("Не удалось создать папку lang/");
                        continue;
                    }
                    Files.copy(in, target.toPath());
                }
            } catch (IOException e) {
                getLogger().warning("Не удалось сохранить lang/" + name + ".yml: " + e.getMessage());
            }
        }
    }
}