package dev.qm.qqminecarts.config;

/**
 * Один уровень скорости вагонетки из конфигурации.
 *
 * @param key   имя уровня (например "1", "2", ...)
 * @param speed прибавка к скорости вагонетки (блоков в тик)
 */
public record SpeedLevel(String key, double speed) {
}