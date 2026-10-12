package dev.qm.qqminecarts.placeholder;

import dev.qm.qqminecarts.manager.SpeedManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Расширение PlaceholderAPI для QQMinecarts, префикс {@code qqminecarts}.
 *
 * Доступные плейсхолдеры:
 * <ul>
 *   <li>%qqminecarts_riding%         — верхом на животном? (yes/no)</li>
 *   <li>%qqminecarts_minecart%       — в вагонетке? (yes/no)</li>
 *   <li>%qqminecarts_player_speed%   — текущая скорость игрока, км/ч (целое)</li>
 *   <li>%qqminecarts_selected_level% — выбранный уровень скорости (0 — обычная)</li>
 *   <li>%qqminecarts_level_boost%    — прибавка выбранного уровня (блок/тик)</li>
 *   <li>%qqminecarts_base_max_speed% — базовая максимальная скорость транспорта
 *       (вагонетка — как в purpur.yml; животное — его атрибут скорости)</li>
 * </ul>
 */
public final class QQExpansion extends PlaceholderExpansion {

    private static final double BLOCKS_PER_TICK_TO_KMH = 20.0D * 3.6D; // *72 = км/ч из блоков/тик

    private final SpeedManager manager;

    public QQExpansion(SpeedManager manager) {
        this.manager = manager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "qqminecarts";
    }

    @Override
    public @NotNull String getAuthor() {
        return "AllF1RE";
    }

    @Override
    public @NotNull String getVersion() {
        return manager.plugin().getPluginMeta().getVersion();
    }

    @Override
    public String onRequest(OfflinePlayer offline, @NotNull String params) {
        try {
            if (!(offline instanceof Player player) || !player.isOnline()) {
                return fallback(params);
            }

            switch (params.toLowerCase(Locale.ROOT)) {
                case "riding":
                    return manager.hasRide(player.getUniqueId()) ? "yes" : "no";
                case "minecart":
                    return manager.hasMinecart(player.getUniqueId()) ? "yes" : "no";
                case "player_speed": {
                    // Приоритет — точный кэш из контроллера (смещение позиции за тик);
                    // фолбэк для неследимого транспорта (лодки и т.п.) — velocity.
                    double kmh = manager.speedKmh(player);
                    if (kmh <= 0.0) {
                        kmh = kmhLive(player);
                    }
                    return String.valueOf(Math.round(kmh));
                }
                case "selected_level":
                    return String.valueOf(manager.level(player));
                case "level_boost":
                    return format(manager.levelBoost(player));
                case "base_max_speed":
                    return format(manager.baseMaxSpeed(player));
                default:
                    return null;
            }
        } catch (Throwable t) {
            manager.plugin().debug("Ошибка плейсхолдера qqminecarts_" + params + ": " + t.getMessage());
            return null;
        }
    }

    /** Значения для offline/неактивного игрока (чтобы скорборды не падали). */
    private String fallback(String params) {
        switch (params.toLowerCase(Locale.ROOT)) {
            case "riding":
            case "minecart":
                return "no";
            case "player_speed":
            case "selected_level":
                return "0";
            case "level_boost":
            case "base_max_speed":
                return "0.00";
            default:
                return null;
        }
    }

    /** Текущая скорость движения по velocity: транспорта, если игрок в нём,
     *  иначе игрока. Используется только как фолбэк. */
    private static long kmhLive(Player player) {
        Entity entity = player.getVehicle();
        Entity measure = entity != null ? entity : player;
        Vector v = measure.getVelocity();
        double horizontal = Math.hypot(v.getX(), v.getZ());
        return Math.round(horizontal * BLOCKS_PER_TICK_TO_KMH);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}