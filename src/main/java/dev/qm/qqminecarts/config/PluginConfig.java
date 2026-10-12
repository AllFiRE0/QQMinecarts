package dev.qm.qqminecarts.config;

import dev.qm.qqminecarts.QQMinecarts;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

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
import java.util.stream.Stream;

/**
 * Неизменяемая (immutable) конфигурация плагина.
 * Читается из config.yml, при ошибках — используются значения по умолчанию.
 * Замена экземпляра при перезагрузке происходит атомарно (volatile-ссылка
 * в {@link ConfigManager}), поэтому читать конфиг можно из любого потока.
 */
public final class PluginConfig {

    /** Способ отображения текущего уровня/скорости. */
    public enum DisplayType {
        ACTIONBAR, // сообщение над хотбаром
        BOSSBAR    // полоса боссбара
    }

    /** Режим управления скоростью.
     *  MANUAL — клавиша F повышает уровень, пробел сбрасывает.
     *  AUTO   — удержание W/S (вагонетка) или езда прямо (моб) постепенно
     *           разгоняет транспорт; у мобов за повороты снимается часть
     *           скорости (чем резче поворот — тем больше). */
    public enum MechanicMode {
        MANUAL,
        AUTO
    }

    // ---- общие настройки ----
    private final boolean debug;
    private final String language;

    // ---- уровни скорости ----
    private final List<SpeedLevel> speedLevels;
    private final List<SpeedLevel> poweredSpeedLevels;
    private final double maxSpeed;
    private final double curveMaxSpeed;

    // ---- триггеры ----
    private final boolean triggerSwap;
    private final boolean spaceReset;
    private final long clickCooldownMs;

    // ---- плавный разгон / торможение ----
    private final int transitionDelayTicks;
    private final int transitionRampTicks;

    // ---- режим управления (manual / auto) ----
    private final MechanicMode mechanicMode;
    private final int accelTickIntervalTicks;
    /** Тиков на -1 уровень при бездействии/остановке (вагонетка и моб отдельно). */
    private final int decelMinecartTickIntervalTicks;
    private final int decelMobTickIntervalTicks;

    // ---- отображение ----
    private final DisplayType displayType;
    private final int displayUpdateIntervalTicks;
    private final boolean displayShowAlways;
    private final BarColor bossbarColor;
    private final BarStyle bossbarStyle;
    private final double bossbarFullProgressAt;

    // ---- миры-исключения ----
    private final Set<String> disabledWorlds;

    // ---- езда на животных ----
    private final Map<EntityType, RideMobConfig> rideMobs;

    private PluginConfig(boolean debug, String language,
                         List<SpeedLevel> speedLevels, List<SpeedLevel> poweredSpeedLevels,
                         double maxSpeed, double curveMaxSpeed,
                         boolean triggerSwap, boolean spaceReset, long clickCooldownMs,
                         int transitionDelayTicks, int transitionRampTicks,
                         MechanicMode mechanicMode, int accelTickIntervalTicks,
                         int decelMinecartTickIntervalTicks, int decelMobTickIntervalTicks,
                         DisplayType displayType, int displayUpdateIntervalTicks, boolean displayShowAlways,
                         BarColor bossbarColor, BarStyle bossbarStyle, double bossbarFullProgressAt,
                         Set<String> disabledWorlds,
                         Map<EntityType, RideMobConfig> rideMobs) {
        this.debug = debug;
        this.language = language;
        this.speedLevels = speedLevels;
        this.poweredSpeedLevels = poweredSpeedLevels;
        this.maxSpeed = maxSpeed;
        this.curveMaxSpeed = curveMaxSpeed;
        this.triggerSwap = triggerSwap;
        this.spaceReset = spaceReset;
        this.clickCooldownMs = clickCooldownMs;
        this.transitionDelayTicks = transitionDelayTicks;
        this.transitionRampTicks = transitionRampTicks;
        this.mechanicMode = mechanicMode;
        this.accelTickIntervalTicks = accelTickIntervalTicks;
        this.decelMinecartTickIntervalTicks = decelMinecartTickIntervalTicks;
        this.decelMobTickIntervalTicks = decelMobTickIntervalTicks;
        this.displayType = displayType;
        this.displayUpdateIntervalTicks = displayUpdateIntervalTicks;
        this.displayShowAlways = displayShowAlways;
        this.bossbarColor = bossbarColor;
        this.bossbarStyle = bossbarStyle;
        this.bossbarFullProgressAt = bossbarFullProgressAt;
        this.disabledWorlds = disabledWorlds;
        this.rideMobs = rideMobs;
    }

    // ================== GETTERS ==================

    public boolean debug() { return debug; }
    public String language() { return language; }

    public List<SpeedLevel> speedLevels() { return speedLevels; }

    /** Уровни скорости для энергорельс (если секции нет — то же, что speed-levels). */
    public List<SpeedLevel> poweredSpeedLevels() { return poweredSpeedLevels; }

    public int levelCount() { return Math.max(speedLevels.size(), poweredSpeedLevels.size()); }
    public double maxSpeed() { return maxSpeed; }

    /** Максимальная скорость на повороте (0 = не ограничивать). */
    public double curveMaxSpeed() { return curveMaxSpeed; }

    public boolean triggerSwap() { return triggerSwap; }
    public boolean spaceReset() { return spaceReset; }
    public long clickCooldownMs() { return clickCooldownMs; }

    public int transitionDelayTicks() { return transitionDelayTicks; }
    public int transitionRampTicks() { return transitionRampTicks; }

    public MechanicMode mechanicMode() { return mechanicMode; }

    /** true — режим автоматического разгона (AUTO). */
    public boolean autoMode() { return mechanicMode == MechanicMode.AUTO; }

    /** Через сколько тиков удержания вперёд/езды прямо прибавляется уровень. */
    public int accelTickIntervalTicks() { return accelTickIntervalTicks; }

    /** Тиков на -1 уровень, когда вагонетка остановилась/не ведётся (режим AUTO). */
    public int decelMinecartTickIntervalTicks() { return decelMinecartTickIntervalTicks; }

    /** Тиков на -1 уровень, когда моб не скачет/поворачивается (режим AUTO). */
    public int decelMobTickIntervalTicks() { return decelMobTickIntervalTicks; }

    public DisplayType displayType() { return displayType; }
    public int displayUpdateIntervalTicks() { return displayUpdateIntervalTicks; }
    public boolean displayShowAlways() { return displayShowAlways; }
    public BarColor bossbarColor() { return bossbarColor; }
    public BarStyle bossbarStyle() { return bossbarStyle; }
    public double bossbarFullProgressAt() { return bossbarFullProgressAt; }

    public Set<String> disabledWorlds() { return disabledWorlds; }

    /** Настройки езды для указанного типа животного (null — тип не в конфиге). */
    public RideMobConfig rideMob(EntityType type) {
        return rideMobs.get(type);
    }

    /** Разрешена ли механика езды на указанном типе животного. */
    public boolean rideEnabled(EntityType type) {
        RideMobConfig cfg = rideMob(type);
        return cfg != null && cfg.enabled();
    }

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

        List<SpeedLevel> levels = parseLevels(yml, "speed-levels", log);
        if (levels.isEmpty()) levels = defaultLevels();
        List<SpeedLevel> powered = parseLevels(yml, "powered-speed-levels", log);
        if (powered.isEmpty()) powered = levels;

        double maxSpeed = Stream.concat(levels.stream(), powered.stream())
                .mapToDouble(SpeedLevel::speed)
                .filter(v -> v > 0.0)
                .max()
                .orElse(1.0);
        double curveMaxSpeed = Math.max(yml.getDouble("curve-max-speed", 0.7), 0.0);

        List<String> triggers = yml.getStringList("cycle-triggers");
        if (triggers.isEmpty()) {
            triggers = List.of("SWAP");
        }
        boolean triggerSwap = triggers.stream().anyMatch(s -> s.equalsIgnoreCase("SWAP"));

        boolean spaceReset = yml.getBoolean("space-reset", true);
        long clickCooldownMs = Math.max(yml.getLong("click-cooldown-ms", 300L), 0L);

        int delayTicks = clampInt(yml.getInt("transition.delay-ticks", 0), 0, 1200);
        int rampTicks = clampInt(yml.getInt("transition.ramp-ticks", 40), 1, 1200);

        MechanicMode mechanicMode = parseMechanicMode(yml.getString("mechanic.mode", "manual"), log);
        int accelInterval = clampInt(yml.getInt("mechanic.auto.accel-tick-interval", 80), 2, 1200);
        int decelMinecart = clampInt(yml.getInt("mechanic.auto.decel-minecart-tick-interval", 40), 2, 1200);
        int decelMob = clampInt(yml.getInt("mechanic.auto.decel-mob-tick-interval", 20), 2, 1200);

        DisplayType displayType = parseDisplayType(yml.getString("display.type", "ACTIONBAR"), log);
        int updateInterval = clampInt(yml.getInt("display.update-interval-ticks", 10), 1, 600);
        boolean showAlways = yml.getBoolean("display.show-always", true);
        BarColor barColor = parseBarColor(yml.getString("display.bossbar.color", "BLUE"), log);
        BarStyle barStyle = parseBarStyle(yml.getString("display.bossbar.style", "SEGMENTED_10"), log);
        double fullProgressAt = Math.max(yml.getDouble("display.bossbar.full-progress-at", 4.0), 0.01);

        Set<String> disabledWorlds = new LinkedHashSet<>(yml.getStringList("disabled-worlds"));

        Map<EntityType, RideMobConfig> rideMobs = parseRideMobs(yml, log);

        return new PluginConfig(debug, language,
                levels, powered, maxSpeed, curveMaxSpeed,
                triggerSwap, spaceReset, clickCooldownMs,
                delayTicks, rampTicks,
                mechanicMode, accelInterval, decelMinecart, decelMob,
                displayType, updateInterval, showAlways,
                barColor, barStyle, fullProgressAt,
                disabledWorlds,
                rideMobs);
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

    /** Парсинг уровней скорости из секции (например, speed-levels). */
    private static List<SpeedLevel> parseLevels(ConfigurationSection parent, String sectionPath, Logger log) {
        Map<String, Double> raw = new LinkedHashMap<>();
        ConfigurationSection section = parent == null ? null : parent.getConfigurationSection(sectionPath);
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
                        log.warning("Некорректная скорость уровня '" + key + "' в " + sectionPath
                                + ": " + value + ". Уровень пропущен.");
                        continue;
                    }
                }
                raw.put(key, speed);
            }
        }

        List<SpeedLevel> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            boolean exists = result.stream().anyMatch(sl -> sl.key().equalsIgnoreCase(entry.getKey()));
            if (!exists) {
                result.add(new SpeedLevel(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private static List<SpeedLevel> defaultLevels() {
        List<SpeedLevel> result = new ArrayList<>(5);
        result.add(new SpeedLevel("1", 0.4));
        result.add(new SpeedLevel("2", 0.7));
        result.add(new SpeedLevel("3", 1.0));
        result.add(new SpeedLevel("4", 1.3));
        result.add(new SpeedLevel("5", 1.6));
        return result;
    }

    // ================== ЕЗДА НА ЖИВОТНЫХ ==================

    /** Все типы животных, поддерживаемые механикой езды. */
    public static final List<EntityType> RIDE_TYPES = List.of(
            EntityType.HORSE,
            EntityType.DONKEY,
            EntityType.MULE,
            EntityType.SKELETON_HORSE,
            EntityType.ZOMBIE_HORSE,
            EntityType.PIG,
            EntityType.STRIDER,
            EntityType.CAMEL,
            EntityType.CAMEL_HUSK,
            EntityType.LLAMA,
            EntityType.TRADER_LLAMA,
            EntityType.HAPPY_GHAST,
            EntityType.NAUTILUS,
            EntityType.ZOMBIE_NAUTILUS);

    /** Ключ типа в конфиге (horse, skeleton-horse, ...). */
    public static String rideKey(EntityType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Парсинг секции riding. Все типы получают значения по умолчанию,
     *  явные значения из config.yml перекрывают их. */
    private static Map<EntityType, RideMobConfig> parseRideMobs(YamlConfiguration yml, Logger log) {
        Map<EntityType, RideMobConfig> result = new LinkedHashMap<>();
        for (EntityType type : RIDE_TYPES) {
            result.put(type, defaultRide(type));
        }

        ConfigurationSection riding = yml.getConfigurationSection("riding");
        if (riding == null) {
            return result;
        }
        for (String key : riding.getKeys(false)) {
            EntityType type = rideType(key);
            if (type == null) {
                log.warning("riding: неизвестный тип животного '" + key + "' в секции riding. Игнорирую.");
                continue;
            }
            ConfigurationSection section = riding.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            RideMobConfig defaults = defaultRide(type);
            boolean enabled = section.getBoolean("enabled", defaults.enabled());
            List<SpeedLevel> levels = parseLevels(section, "speed-levels", log);
            if (levels.isEmpty()) {
                levels = defaults.levels();
            }
            result.put(type, new RideMobConfig(enabled, levels));
        }
        return result;
    }

    private static EntityType rideType(String key) {
        try {
            return EntityType.valueOf(key.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Дефолтные настройки езды для типа (включено + 5 уровней прибавки). */
    private static RideMobConfig defaultRide(EntityType type) {
        switch (type) {
            case HORSE:
            case SKELETON_HORSE:
            case ZOMBIE_HORSE:
                return new RideMobConfig(true, rideLevels(0.05, 0.10, 0.15, 0.20, 0.25));
            case DONKEY:
            case MULE:
                return new RideMobConfig(true, rideLevels(0.04, 0.08, 0.12, 0.16, 0.20));
            case PIG:
            case STRIDER:
                return new RideMobConfig(true, rideLevels(0.05, 0.10, 0.15, 0.20, 0.25));
            case CAMEL:
            case CAMEL_HUSK:
                return new RideMobConfig(true, rideLevels(0.04, 0.08, 0.12, 0.16, 0.20));
            case LLAMA:
                return new RideMobConfig(true, rideLevels(0.03, 0.06, 0.09, 0.12, 0.15));
            case TRADER_LLAMA:
                return new RideMobConfig(false, rideLevels(0.03, 0.06, 0.09, 0.12, 0.15));
            case HAPPY_GHAST:
            case NAUTILUS:
            case ZOMBIE_NAUTILUS:
                return new RideMobConfig(true, rideLevels(0.05, 0.10, 0.15, 0.20, 0.25));
            default:
                return new RideMobConfig(false, rideLevels(0.05, 0.10, 0.15, 0.20, 0.25));
        }
    }

    private static List<SpeedLevel> rideLevels(double... speeds) {
        List<SpeedLevel> result = new ArrayList<>(speeds.length);
        for (int i = 0; i < speeds.length; i++) {
            result.add(new SpeedLevel(String.valueOf(i + 1), speeds[i]));
        }
        return result;
    }

    private static MechanicMode parseMechanicMode(String value, Logger log) {
        if (value == null) return MechanicMode.MANUAL;
        try {
            return MechanicMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("Неизвестный режим управления '" + value + "' (ожидалось manual/auto). Использую manual.");
            return MechanicMode.MANUAL;
        }
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