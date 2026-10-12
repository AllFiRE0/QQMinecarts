package dev.qm.qqminecarts.config;

import java.util.List;

/**
 * Настройки механики езды на одном виде животных (из секции riding конфига).
 *
 * @param enabled включена ли механика для этого животного
 * @param levels  уровни прибавки скорости (блоков в тик).
 *                Итоговая скорость = базовая скорость животного + уровень.
 */
public record RideMobConfig(boolean enabled, List<SpeedLevel> levels) {

    /** Самый высокий доступный уровень (1..max). 0 — если уровней нет. */
    public int levelCount() {
        return Math.max(1, levels.size());
    }
}