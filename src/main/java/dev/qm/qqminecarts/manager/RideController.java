package dev.qm.qqminecarts.manager;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.PluginConfig;
import dev.qm.qqminecarts.config.RideMobConfig;
import dev.qm.qqminecarts.config.SpeedLevel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Механика езды верхом на животных (лошади, свиньи, ламы, верблюды, страйдеры...).
 *
 * Клавиша F повышает уровень скорости (1 -> ... -> max), пробел — сброс.
 * Плагин НЕ переопределяет движение животного: пока игрок ведёт его
 * клавишей (W), животное само разгоняется до своей максимальной скорости.
 * «Уровень» поднимает эту максимальную скорость: к базовому атрибуту
 * {@link Attribute#MOVEMENT_SPEED} животного добавляется плоский модификатор
 * ADD_NUMBER на значение уровня, поэтому животное плавно ускоряется до
 * нового предела (итог = базовая скорость животного + уровень) и так же
 * плавно тормозится после сброса.
 *
 * Модификатор снимается при выходе из седла, выгрузке чанка и т.п.
 */
public final class RideController {

    /** UUID модификатора скорости (одинаковый у всех животных).
     *  Кодируем имя в UUID, чтобы всегда снимать именно «наш» модификатор. */
    static final UUID BOOST_UUID = UUID.nameUUIDFromBytes("qqminecarts:ride-boost".getBytes(StandardCharsets.UTF_8));

    private final SpeedManager manager;
    private final Map<UUID, RideState> rides = new ConcurrentHashMap<>();

    RideController(SpeedManager manager) {
        this.manager = manager;
    }

    // ================== ПРОВЕРКИ ==================

    /** Можно ли применить механику езды к этому транспортному сущности. */
    boolean isRideable(Entity vehicle) {
        if (vehicle == null) return false;
        return manager.config().rideEnabled(vehicle.getType());
    }

    boolean has(UUID playerId) {
        return rides.containsKey(playerId);
    }

    // ================== ПРИВЯЗКА / ОТВЯЗКА ==================

    /** Игрок сел на животное — запускаем управление (если тип разрешён). */
    void attach(Player player, Entity vehicle) {
        if (!isRideable(vehicle) || !(vehicle instanceof LivingEntity)) return;
        UUID playerId = player.getUniqueId();
        detach(playerId);

        // Несколько седоков на одном животном — управляет последний
        for (Map.Entry<UUID, RideState> entry : rides.entrySet()) {
            if (entry.getValue().sameVehicle(vehicle.getUniqueId())) {
                detach(entry.getKey());
            }
        }

        if (manager.isWorldAllowed(vehicle.getWorld().getName(), player)) {
            rides.put(playerId, new RideState(manager, player, (LivingEntity) vehicle));
            manager.plugin().debug("Привязка езды: " + player.getName() + " -> " + vehicle.getType());
        } else {
            manager.messages().sendMessage(player, "messages.disabled-world");
        }
    }

    /** Игрок покинул седло или вышел — снимаем модификатор и останавливаем тик. */
    void detach(UUID playerId) {
        RideState state = rides.remove(playerId);
        if (state != null) {
            state.cancel();
        }
    }

    void shutdown() {
        rides.values().forEach(RideState::cancel);
        rides.clear();
    }

    // ================== ВВОД ==================

    /** Клавиша F. true — уровень изменён. */
    boolean cycle(Player player) {
        RideState state = rides.get(player.getUniqueId());
        return state != null && state.cycle();
    }

    /** Пробел: сброс скорости к обычной. */
    boolean reset(Player player) {
        RideState state = rides.get(player.getUniqueId());
        return state != null && state.reset();
    }

    /** Игрок держит W/S (ввод управления). В режиме auto "скачет" = держит W
     *  и реально движется; отпустил клавишу — скорость начинает сливаться,
     *  даже если моб ещё катится по инерции. */
    void setForwardHeld(UUID playerId, boolean held) {
        RideState state = rides.get(playerId);
        if (state != null) {
            state.forwardHeld = held;
        }
    }

    // ================== ДАННЫЕ ДЛЯ ПЛЕЙСХОЛДЕРОВ ==================

    int level(UUID playerId) {
        RideState state = rides.get(playerId);
        return state == null ? 0 : state.currentLevelIndex();
    }

    double selectedLevelSpeed(UUID playerId) {
        RideState state = rides.get(playerId);
        return state == null ? 0.0 : state.selectedLevelSpeed();
    }

    /** Базовая максимальная скорость животного (атрибут без нашего бонуса).
     *  -1 — игрок не на настроенном животном. */
    double baseMaxSpeed(UUID playerId) {
        RideState state = rides.get(playerId);
        return state == null ? -1.0 : state.baseMaxSpeed();
    }

    double speedKmh(UUID playerId) {
        RideState state = rides.get(playerId);
        return state == null ? 0.0 : state.speedKmh();
    }

    // ================== СОСТОЯНИЕ ОДНОЙ ПОЕЗДКИ ==================

    /** Тикается через EntityScheduler животного (на Leaf — поток региона,
     *  т.е. НЕ основной поток), поэтому обращаться к животному безопасно. */
    private static final class RideState implements Runnable {

        private static final double EPS = 1e-9;

        private final SpeedManager manager;
        private final UUID playerId;
        private final UUID vehicleId;
        private final LivingEntity vehicle;

        private ScheduledTask task;

        // ---- изменяемое состояние (только из задачи планировщика) ----
        private int levelIndex = 0;        // 0 = обычная скорость, 1..N = уровни
        private double boost = 0.0D;       // текущая прибавка (модификатор)
        private double baseSpeed = 0.0D;   // база атрибута без нашего бонуса
        /** Текущая скорость животного, км/ч (по смещению позиции между тиками). */
        private double speedKmh = 0.0D;
        private double lastX = Double.NaN;
        private double lastZ = Double.NaN;
        /** Смещение за последний измеренный тик (для авто-режима). */
        private double lastSampleDist = 0.0D;
        /** true — игрок держит клавишу вперёд (управляет мобом). Обновляется
         *  из события ввода, поэтому доступно из потока региона. */
        volatile boolean forwardHeld = false;
        private int displayTicks = 0;
        private long lastBoostNanos = 0L;

        // ---- авто-режим (разгон временем / слив при бездействии) ----
        private double autoBoost = 0.0D;      // накопленная прибавка
        private int autoTicks = 0;            // тиков активной езды (разгон)
        private int decelTicks = 0;           // тиков "не скачет" (слив)
        private double turnSumDeg = 0.0D;     // накопленный угол поворота
        private boolean hasHeading = false;
        private double lastHeading = 0.0D;

        /** Прямое движение считается, пока накопленный поворот < 5°.
         *  Резче — моб "не скачет", скорость начинает сливаться. */
        private static final double STRAIGHT_DEG = 5.0;

        /** Усреднение км/ч скользящим окном. Реальную скорость езды нельзя
         *  взять из getVelocity() (ванильная езда двигает моба сменой позиции,
         *  а не velocity — там всегда ~0), поэтому меряем смещение за тик.
         *  Окно-сумма неуязвимо к пропущенному планировщиком тику: если один
         *  отсчёт покрыл 2 тика (завышен), следующий даст ~0 — в сумме окна
         *  они компенсируются, и среднее остаётся точным без всяких флагов. */
        private static final int KMH_WINDOW = 16;
        private final ArrayDeque<Double> kmhSamples = new ArrayDeque<>(KMH_WINDOW);

        RideState(SpeedManager manager, Player player, LivingEntity vehicle) {
            this.manager = manager;
            this.playerId = player.getUniqueId();
            this.vehicleId = vehicle.getUniqueId();
            this.vehicle = vehicle;
            this.task = vehicle.getScheduler().runAtFixedRate(
                    manager.plugin(),
                    scheduledTask -> runTask(),
                    this::finish,
                    1L, 1L);
        }

        @Override
        public void run() {
            runTask();
        }

        private void runTask() {
            try {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    finish();
                    return;
                }
                if (vehicle == null || !vehicle.isValid() || !vehicle.getPassengers().contains(player)) {
                    finish();
                    return;
                }

                PluginConfig cfg = manager.config();
                RideMobConfig ride = cfg.rideMob(vehicle.getType());
                if (ride == null || !ride.enabled()) {
                    finish();
                    return;
                }
                if (!manager.isWorldAllowed(vehicle.getWorld().getName(), player)) {
                    finish();
                    return;
                }

                applyTick(ride);

                // Отображение обновляем, пока есть уровень (manual) или
                // накопленная прибавка/движение (auto)
                if (levelIndex > 0 || cfg.autoMode()) {
                    if (++displayTicks >= cfg.displayUpdateIntervalTicks()) {
                        displayTicks = 0;
                        updateDisplay(player);
                    }
                }
            } catch (Throwable t) {
                // Никогда не роняем тик сервера
                QQMinecarts plugin = manager.plugin();
                plugin.getLogger().severe("Ошибка в задаче езды на животном: " + t.getMessage());
                if (manager.config().debug()) {
                    t.printStackTrace();
                }
                finish();
            }
        }

        /** Применяем модификатор скорости к текущему уровню (или снимаем).
         *  Тик км/ч выполняется всегда (нужен и для авто-режима). */
        private void applyTick(RideMobConfig ride) {
            measureSpeedKmh();

            double targetBoost;
            if (manager.config().autoMode()) {
                targetBoost = autoTick(ride);
            } else {
                targetBoost = 0.0D;
                if (levelIndex > 0) {
                    SpeedLevel level = currentLevel(ride);
                    if (level != null) {
                        targetBoost = level.speed();
                    }
                }
            }
            boost = targetBoost;
            applyModifier(targetBoost);
        }

        /** Один шаг авто-режима: "скачет" — разгон, иначе слив на 1 уровень
         *  каждые N тиков. Возвращает целевую прибавку (0..max). */
        private double autoTick(RideMobConfig ride) {
            List<SpeedLevel> levels = ride.levels();
            if (levels.isEmpty()) {
                return 0.0D;
            }
            double maxBoost = levels.get(levels.size() - 1).speed();
            double step = Math.max(0.01D, levels.get(0).speed());
            PluginConfig cfg = manager.config();

            boolean moving = lastSampleDist > 0.002;
            if (!moving) {
                // Стоим/уперлись/падаем вертикально — поворота не меряем
                turnSumDeg = 0.0D;
                hasHeading = false;
            } else {
                // направление движения из смещения позиции за этот тик
                double x = vehicle.getLocation().getX();
                double z = vehicle.getLocation().getZ();
                double heading = Math.atan2(z - lastZ, x - lastX);
                if (hasHeading) {
                    double deltaDeg = Math.toDegrees(Math.atan2(
                            Math.sin(heading - lastHeading),
                            Math.cos(heading - lastHeading)));
                    turnSumDeg += deltaDeg;
                    // выровняли курс — накопленный поворот плавно затухает,
                    // чтобы после дуги вернуться в состояние "скачет"
                    if (Math.abs(deltaDeg) < 1.0 && Math.abs(turnSumDeg) > 0.0) {
                        turnSumDeg *= 0.85;
                        if (Math.abs(turnSumDeg) < 0.5) {
                            turnSumDeg = 0.0D;
                        }
                    }
                }
                lastHeading = heading;
                hasHeading = true;
            }

            // "скачет": держит вперёд, движется по опоре и не поворачивается.
            // Остановился, упёрся, плывёт, падает, поворачивается или
            // просто отпустил W (моб катится по инерции) — это НЕ скачет,
            // и скорость сливается на 1 уровень каждые decel-mob-tick-interval.
            boolean galloping = moving
                    && forwardHeld
                    && Math.abs(turnSumDeg) < STRAIGHT_DEG
                    && onRiddenSurface();

            if (galloping) {
                decelTicks = 0;
                if (++autoTicks >= cfg.accelTickIntervalTicks()) {
                    autoTicks = 0;
                    autoBoost = Math.min(maxBoost, autoBoost + step);
                }
            } else {
                autoTicks = 0;
                if (++decelTicks >= cfg.decelMobTickIntervalTicks()) {
                    decelTicks = 0;
                    autoBoost = Math.max(0.0D, autoBoost - step);
                }
            }
            return autoBoost;
        }

        /** Моб "на опоре": стоит/бежит по земле (или лаве для страйдера).
         *  В воде/при полёте с обрыва isOnGround() = false -> идёт слив.
         *  Happy Ghast — летающий верховой, для него "скакать" = лететь
         *  вперёд по горизонтали (вертикальное падение ловит moving=false). */
        private boolean onRiddenSurface() {
            return vehicle.isOnGround()
                    || vehicle.getType() == EntityType.HAPPY_GHAST;
        }

        /** Добавляем/снимаем модификатор скорости. Прибавка ВСЕГДА >= 0,
         *  поэтому эффективная скорость моба никогда не опускается ниже его
         *  врождённой (база атрибута не меняется, только сумма модификатора). */
        private void applyModifier(double targetBoost) {
            AttributeInstance attr = vehicle.getAttribute(Attribute.MOVEMENT_SPEED);
            if (attr == null) {
                baseSpeed = 0.0D;
                return;
            }

            AttributeModifier current = attr.getModifier(BOOST_UUID);
            baseSpeed = attr.getValue() - (current != null ? current.getAmount() : 0.0D);

            if (targetBoost <= EPS) {
                if (current != null) {
                    attr.removeModifier(BOOST_UUID);
                }
                return;
            }
            if (current == null || Math.abs(current.getAmount() - targetBoost) > EPS) {
                if (current != null) {
                    attr.removeModifier(BOOST_UUID);
                }
                attr.addModifier(new AttributeModifier(BOOST_UUID, "qqminecarts_ride_boost",
                        targetBoost, AttributeModifier.Operation.ADD_NUMBER));
            }
        }

        // ================== УРОВНИ ==================

        boolean cycle() {
            PluginConfig cfg = manager.config();
            if (cfg.autoMode()) return false;
            RideMobConfig ride = cfg.rideMob(vehicle.getType());
            if (ride == null || !ride.enabled()) return false;

            // Анти-спам (защита от двойного срабатывания клика)
            long now = System.nanoTime();
            if (now - lastBoostNanos < cfg.clickCooldownMs() * 1_000_000L) {
                return false;
            }
            lastBoostNanos = now;

            if (levelIndex >= ride.levelCount()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    manager.messages().sendMessage(player, "messages.riding-boost-max");
                }
                return false;
            }

            levelIndex++;
            Player player = Bukkit.getPlayer(playerId);
            SpeedLevel level = currentLevel(ride);
            if (player != null && level != null) {
                manager.messages().sendMessage(player, "messages.riding-boost",
                        "animal", rideName(),
                        "level", level.key(),
                        "speed", format(level.speed()));
            }
            manager.plugin().debug("Игрок " + playerId + " выбрал уровень " + levelIndex + " на " + rideName());
            return true;
        }

        boolean reset() {
            PluginConfig cfg = manager.config();
            if (cfg.autoMode()) {
                if (autoBoost <= EPS && autoTicks == 0) {
                    return false;
                }
                autoBoost = 0.0D;
                autoTicks = 0;
                decelTicks = 0;
                turnSumDeg = 0.0D;
                hasHeading = false;
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    manager.messages().sendMessage(player, "messages.riding-boost-reset");
                }
                manager.plugin().debug("Игрок " + playerId + " сбросил авто-скорость езды к обычной");
                return true;
            }
            if (levelIndex == 0 && boost <= EPS) {
                return false;
            }
            levelIndex = 0;
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                manager.messages().sendMessage(player, "messages.riding-boost-reset");
            }
            manager.plugin().debug("Игрок " + playerId + " сбросил скорость езды к обычной");
            return true;
        }

        private SpeedLevel currentLevel(RideMobConfig ride) {
            if (levelIndex < 1) {
                return null;
            }
            List<SpeedLevel> levels = ride.levels();
            if (levels.isEmpty()) {
                return null;
            }
            return levels.get(Math.min(levelIndex - 1, levels.size() - 1));
        }

        /** Км/ч: смещение по плоскости за один тик * 72 (20 тиков/с * 3.6),
         *  усреднённое скользящим окном (см. KMH_WINDOW).
         *  Параллельно запоминается само смещение (lastSampleDist). */
        private void measureSpeedKmh() {
            double x = vehicle.getLocation().getX();
            double z = vehicle.getLocation().getZ();
            double dist = Double.isNaN(lastX) ? 0.0 : Math.hypot(x - lastX, z - lastZ);
            lastSampleDist = dist;
            kmhSamples.addLast(dist * 72.0D);
            if (kmhSamples.size() > KMH_WINDOW) {
                kmhSamples.removeFirst();
            }
            double sum = 0.0;
            for (Double s : kmhSamples) {
                sum += s;
            }
            speedKmh = sum / kmhSamples.size();
            lastX = x;
            lastZ = z;
        }

        // ================== ОТОБРАЖЕНИЕ ==================

        private void updateDisplay(Player player) {
            PluginConfig cfg = manager.config();
            String levelText = displayLevelText(cfg, cfg.rideMob(vehicle.getType()));
            if (!cfg.displayShowAlways() && boost <= EPS) {
                return;
            }
            manager.messages().sendActionBar(player, "messages.riding-actionbar",
                    "level", levelText,
                    "animal", rideName(),
                    "boost", format(boost),
                    "kmh", String.valueOf(Math.round(speedKmh)));
        }

        /** Текст уровня в экшнбаре: в manual — ключ уровня; в auto — номер
         *  ступени (0 = обычная скорость). */
        private String displayLevelText(PluginConfig cfg, RideMobConfig ride) {
            String defaultText = manager.messages().getPlain("messages.level-default");
            if (cfg.autoMode()) {
                if (boost <= EPS) {
                    return defaultText;
                }
                return String.valueOf(stage());
            }
            SpeedLevel level = ride == null ? null : currentLevel(ride);
            return level == null ? defaultText : level.key();
        }

        // ================== ОБСЛУЖИВАНИЕ ЖИЗНЕННОГО ЦИКЛА ==================

        boolean sameVehicle(UUID otherId) {
            return vehicleId.equals(otherId);
        }

        /** Отмена задачи + снятие модификатора (без удаления из менеджера). */
        void cancel() {
            if (task != null && !task.isCancelled()) {
                try {
                    task.cancel();
                } catch (IllegalStateException ignored) {
                    // задача уже завершена/отменена
                }
            }
            if (vehicle != null && vehicle.isValid()) {
                try {
                    AttributeInstance attr = vehicle.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (attr != null) {
                        attr.removeModifier(BOOST_UUID);
                    }
                } catch (Throwable ignored) {
                    // сущность уже недоступна
                }
            }
        }

        /** Полная очистка: отмена + удаление из менеджера. */
        private void finish() {
            cancel();
            manager.detachRide(playerId);
        }

        // ================== ДОСТУП (для плейсхолдеров) ==================

        int currentLevelIndex() {
            if (manager.config().autoMode()) {
                if (autoBoost <= EPS) {
                    return 0;
                }
                return stage();
            }
            return levelIndex;
        }

        double selectedLevelSpeed() {
            if (manager.config().autoMode()) {
                return autoBoost;
            }
            RideMobConfig ride = manager.config().rideMob(vehicle.getType());
            SpeedLevel level = ride == null ? null : currentLevel(ride);
            return level == null ? 0.0 : level.speed();
        }

        double baseMaxSpeed() {
            return baseSpeed;
        }

        double speedKmh() {
            return speedKmh;
        }

        /** Ступень накопленной прибавки (1..N) для отображения/плейсхолдеров.
         *  Шаг = первый уровень speed-levels животного, максимум — последний. */
        private int stage() {
            RideMobConfig ride = manager.config().rideMob(vehicle.getType());
            List<SpeedLevel> levels = ride == null ? List.of() : ride.levels();
            double step = levels.isEmpty() ? 1.0 : Math.max(0.01D, levels.get(0).speed());
            return Math.max(1, (int) Math.ceil(autoBoost / step));
        }

        private String rideName() {
            return manager.messages().mobName(PluginConfig.rideKey(vehicle.getType()));
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }
}