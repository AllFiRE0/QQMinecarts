package dev.qm.qqminecarts.config;

/**
 * Один уровень скорости вагонетки из конфигурации.
 *
 * @param key   имя уровня (например "default", "1", "2", ...)
 * @param speed целевая скорость вагонетки (блоков в тик)
 */
public record SpeedLevel(String key, double speed) {

    /**
     * Уровень "default" — обычное (ванильное) управление с клавиши W.
     * Такой уровень всегда стоит на первой позиции списка.
     */
    public boolean isDefault() {
        return key.equalsIgnoreCase("default");
    }
}