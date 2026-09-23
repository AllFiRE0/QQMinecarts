package dev.qm.qqminecarts.config;

import dev.qm.qqminecarts.QQMinecarts;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Неизменяемая (immutable) конфигурация плагина.
 * Читается из config.yml, при ошибках — используются значения по умолчанию.
 * Замена экземпляра при перезагрузке происходит атомарно (volatile-ссылка
 * в {@link ConfigManager}), поэтому читать конфиг можно из любого потока.
 */
public final class PluginConfig {

    /** Способ отображения текущей скорости. */
    public enum DisplayType {
        ACTIONBAR, // сообщение над хотбаром
        BOSSBAR    // полоса боссбара
    }

    // ---- общие настройки ----
    private final boolean debug;
    private final String language;
    private final boolean permissionPerLevel;

    // ---- уровни скорости (индекс 0 всегда "default") ----
    private final List<SpeedLevel> speedLevels;
    private final double maxSpeed;

    // ---- триггеры переключения ----
    private final boolean triggerSwap;
    private final boolean triggerLeftClick;

    // ---- плавный разгон / торможение ----
    private final int transitionDelayTicks;
    private final double transitionStep;
    private final int transitionIntervalTicks;
    private final double manualForwardSpeed;

    // ---- отображение ----
    private final DisplayType displayType;
    private final int displayUpdateIntervalTicks;
    private final boolean displayShowAlways;
    private final BarColor bossbarColor;
    private final BarStyle bossbarStyle;
    private final double bossbarFullProgressAt;

    // ---- миры-исключения ----
    private final Set<String> disabledWorlds;

    private PluginConfig(boolean debug, String language, boolean permissionPerLevel,
                         List<SpeedLevel> speedLevels, double maxSpeed,
                         boolean triggerSwap, boolean triggerLeftClick,
                         int transitionDelayTicks, double transitionStep, int transitionIntervalTicks,
                         double manualForwardSpeed,
                         DisplayType displayType, int displayUpdateIntervalTicks, boolean displayShowAlways,
                         BarColor bossbarColor, BarStyle bossbarStyle, double bossbarFullProgressAt,
                         Set<String> disabledWorlds) {
        this.debug = debug;
        this.language = language;
        this.permissionPerLevel = permissionPerLevel;
        this.speedLevels = speedLevels;
        this.maxSpeed = maxSpeed;
        this.triggerSwap = triggerSwap;
        this.triggerLeftClick = triggerLeftClick;
        this.transitionDelayTicks = transitionDelayTicks;
        this.transitionStep = transitionStep;
        this.transitionIntervalTicks = transitionIntervalTicks;
        this.manualForwardSpeed = manualForwardSpeed;
        this.displayType = displayType;
        this.displayUpdateIntervalTicks = displayUpdateIntervalTicks;
        this.displayShowAlways = displayShowAlways;
        this.bossbarColor = bossbarColor;
        this.bossbarStyle = bossbarStyle;
        this.bossbarFullProgressAt = bossbarFullProgressAt;
        this.disabledWorlds = disabledWorlds;
    }

    // ================== GETTERS ==================

    public boolean debug() { return debug; }
    public String language() { return language; }
    public boolean permissionPerLevel() { return permissionPerLevel; }

    public List<SpeedLevel> speedLevels() { return speedLevels; }
    public int levelCount() { return speedLevels.size(); }

    /** Уровень по индексу (0 = default). */
    public SpeedLevel level(int index) {
        return speedLevels.get(index);
    }

    /** Максимальная настроенная скорость (для прогресс-бара). */
    public double maxSpeed() { return maxSpeed; }

    public boolean triggerSwap() { return triggerSwap; }
    public boolean triggerLeftClick() { return triggerLeftClick; }

    public int transitionDelayTicks() { return transitionDelayTicks; }
    public double transitionStep() { return transitionStep; }
    public int transitionIntervalTicks() { return transitionIntervalTicks; }
    public double manualForwardSpeed() { return manualForwardSpeed; }

    public DisplayType displayType() { return displayType; }
    public int displayUpdateIntervalTicks() { return displayUpdateIntervalTicks; }
    public boolean displayShowAlways() { return displayShowAlways; }
    public BarColor bossbarColor() { return bossbarColor; }
    public BarStyle bossbarStyle() { return bossbarStyle; }
    public double bossbarFullProgressAt() { return bossbarFullProgressAt; }

    public Set<String> disabledWorlds() { return disabledWorlds; }

    // ================== ЗАГРУЗКА ==================

    /**
     * Загрузка конфигурации из config.yml плагина.
     * Ошибки не бросаются: при проблемах используются встроенные значения.
     */
    public static PluginConfig load(QQMinecarts plugin) {
        Logger log = plugin.getLogger();
        YamlConfiguration yml;

        try {
            File file = new File(plugin.getDataFolder(), "config.yml");
            yml = YamlConfiguration.loadConfiguration(file);
        } catch (Throwable t) {
            log.warning("Ошибка чтения config.yml: " + t.getMessage() + ". Использую встроенные значения.");
            yml = null;
        }
        // Если файл пустой/отсутствует — берём встроенный config.yml из jar
        if (yml == null || yml.getKeys(false).isEmpty()) {
            yml = embedded(plugin);
        }

        boolean debug = yml.getBoolean("debug", false);
        String language = yml.getString("language", "ru");
        if (language == null || language.trim().isEmpty()) language = "ru";
        boolean permissionPerLevel = yml.getBoolean("permission-per-level", false);

        List<SpeedLevel> levels = parseLevels(yml, log);
        double maxSpeed = levels.stream()
                .filter(sl -> !sl.isDefault())
                .mapToDouble(SpeedLevel::speed)
                .filter(v -> v > 0.0)
                .max()
                .orElse(1.0);

        List<String> triggers = yml.getStringList("cycle-triggers");
        if (triggers.isEmpty()) {
            triggers = List.of("SWAP", "LEFT_CLICK");
        }
        boolean triggerSwap = triggers.stream().anyMatch(s -> s.equalsIgnoreCase("SWAP"));
        boolean triggerLeftClick = triggers.stream()
                .anyMatch(s -> s.equalsIgnoreCase("LEFT_CLICK") || s.equalsIgnoreCase("LMB"));

        int delayTicks = clampInt(yml.getInt("transition.delay-ticks", 60), 0, 1200);
        double step = Math.max(yml.getDouble("transition.step", 0.10), 0.0001);
        int intervalTicks = clampInt(yml.getInt("transition.interval-ticks", 5), 1, 200);
        double forwardSpeed = Math.max(yml.getDouble("manual.forward-speed", 0.4), 0.0);

        DisplayType displayType = parseDisplayType(yml.getString("display.type", "ACTIONBAR"), log);
        int updateInterval = clampInt(yml.getInt("display.update-interval-ticks", 10), 1, 600);
        boolean showAlways = yml.getBoolean("display.show-always", true);
        BarColor barColor = parseBarColor(yml.getString("display.bossbar.color", "BLUE"), log);
        BarStyle barStyle = parseBarStyle(yml.getString("display.bossbar.style", "SEGMENTED_10"), log);
        double fullProgressAt = Math.max(yml.getDouble("display.bossbar.full-progress-at", 2.0), 0.01);

        Set<String> disabledWorlds = new LinkedHashSet<>(yml.getStringList("disabled-worlds"));

        return new PluginConfig(debug, language, permissionPerLevel,
                levels, maxSpeed,
                triggerSwap, triggerLeftClick,
                delayTicks, step, intervalTicks, forwardSpeed,
                displayType, updateInterval, showAlways,
                barColor, barStyle, fullProgressAt,
                disabledWorlds);
    }

    /** Встроенная копия config.yml из jar (фолбэк при отсутствии файла). */
    private static YamlConfiguration embedded(QQMinecarts plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // ничего страшного — вернём пустую конфигурацию
        }
        return new YamlConfiguration();
    }

    /** Парсинг уровней скорости; "default" всегда ставится первым. */
    private static List<SpeedLevel> parseLevels(YamlConfiguration yml, Logger log) {
        Map<String, Double> raw = new LinkedHashMap<>();
        ConfigurationSection section = yml.getConfigurationSection("speed-levels");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Object value = section.get(key);
                double speed;
                if (value instanceof Number number) {
                    speed = number.doubleValue();
                } else {
                    try {
                        speed = Double.parseDouble(String.valueOf(value));
                    } catch (NumberFormatException e) {
                        log.warning("Некорректная скорость уровня '" + key + "': " + value + ". Уровень пропущен.");
                        continue;
                    }
                }
                raw.put(key, speed);
            }
        }

        List<SpeedLevel> result = new ArrayList<>();
        Double def = raw.remove("default");
        result.add(new SpeedLevel("default", def == null ? 0.0 : def));
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            boolean exists = result.stream().anyMatch(sl -> sl.key().equalsIgnoreCase(entry.getKey()));
            if (!exists) {
                result.add(new SpeedLevel(entry.getKey(), entry.getValue()));
            }
        }
        // Секция полностью пустая — добавляем запасные уровни 1..5
        if (result.size() <= 1) {
            result.add(new SpeedLevel("1", 0.4));
            result.add(new SpeedLevel("2", 0.7));
            result.add(new SpeedLevel("3", 1.0));
            result.add(new SpeedLevel("4", 1.3));
            result.add(new SpeedLevel("5", 1.6));
        }
        return result;
    }

    private static DisplayType parseDisplayType(String value, Logger log) {
        if (value == null) return DisplayType.ACTIONBAR;
        try {
            return DisplayType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("Неизвестный тип отображения '" + value + "'. Использую ACTIONBAR.");
            return DisplayType.ACTIONBAR;
        }
    }

    private static BarColor parseBarColor(String value, Logger log) {
        if (value == null) return BarColor.BLUE;
        try {
            return BarColor.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            log.warning("Неизвестный цвет боссбара '" + value + "'. Использую BLUE.");
            return BarColor.BLUE;
        }
    }

    private static BarStyle parseBarStyle(String value, Logger log) {
        if (value == null) return BarStyle.SEGMENTED_10;
        try {
            return BarStyle.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            log.warning("Неизвестный стиль боссбара '" + value + "'. Использую SEGMENTED_10.");
            return BarStyle.SEGMENTED_10;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}