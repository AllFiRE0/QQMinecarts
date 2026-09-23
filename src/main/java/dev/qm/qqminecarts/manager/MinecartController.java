package dev.qm.qqminecarts.manager;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.Messages;
import dev.qm.qqminecarts.config.PluginConfig;
import dev.qm.qqminecarts.config.SpeedLevel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

/**
 * Контроллер — состояние управления одной вагонетки с игроком.
 *
 * Логика тикается через {@code EntityScheduler} вагонетки:
 * на Leaf/Folia/новых версиях Paper это поток региона (т.е. НЕ основной
 * поток). Вся математика разгона/торможения и применение скорости
 * выполняются именно там.
 *
 * Общая схема:
 * - уровень 0 ("default") — ручное управление: W -> разгон до
 *   manual.forward-speed, отпустили W -> плавная остановка;
 * - уровень 1..N — автономный режим: после выбора ждём delay-ticks,
 *   затем каждый interval-ticks прибавляем/убираем step, пока не достигнем
 *   целевой скорости уровня.
 */
final class MinecartController implements Runnable {

    /** Фазы состояния контроллера. */
    private enum Phase {
        WAIT,  // ждём delay-ticks после выбора уровня (скорость не меняется)
        ACCEL, // плавное движение текущей скорости к целевой
        STABLE // достигли целевой скорости (только для автономного режима)
    }

    /** Порог, ниже которого скорость считается нулевой (вагонетку не трогаем). */
    private static final double EPS_DELTA = 0.0001D;

    private final SpeedManager manager;
    private final UUID playerId;
    private final UUID cartId;
    private final Minecart cart;

    private ScheduledTask task;

    // ---- изменяемое состояние (доступно только из задачи планировщика) ----
    private int levelIndex = 0;         // индекс текущего уровня (0 = default)
    private double currentSpeed = 0.0D; // фактически применяемая скорость
    private Phase phase = Phase.ACCEL;
    private int waitTicks = 0;          // счётчик фазы WAIT
    private int stepTicks = 0;          // счётчик шагов фазы ACCEL
    private int displayTicks = 0;       // счётчик обновления экрана

    private BossBar bossBar;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;

    MinecartController(SpeedManager manager, Player player, Minecart cart) {
        this.manager = manager;
        this.playerId = player.getUniqueId();
        this.cartId = cart.getUniqueId();
        this.cart = cart;

        // Тикаем каждый игровой тик, начиная со следующего.
        // Третий аргумент — колбэк, если движок "убрал" сущность
        // (выгрузка чанка и т.п.) — тогда корректно обнуляем состояние.
        this.task = cart.getScheduler().runAtFixedRate(
                manager.plugin(),
                scheduledTask -> runTask(),
                this::finish,
                1L, 1L);
    }

    @Override
    public void run() {
        runTask();
    }

    // ================== ОСНОВНОЙ ТИК ==================

    private void runTask() {
        try {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                finish();
                return;
            }
            if (cart == null || !cart.isValid() || !cart.getPassengers().contains(player)) {
                finish();
                return;
            }

            PluginConfig cfg = manager.config();
            if (!manager.isWorldAllowed(cart.getWorld().getName(), player)) {
                finish();
                return;
            }

            // Целевая скорость на этом тике
            boolean manual = levelIndex == 0;
            double desired;
            if (manual) {
                desired = manager.isForwardHeld(playerId)
                        ? cfg.manualForwardSpeed()
                        : 0.0D;
            } else {
                desired = cfg.level(levelIndex).speed();
            }

            // Плавный разгон / торможение / ожидание
            switch (phase) {
                case WAIT:
                    // Считаем задержку: когда она истечёт — начинаем разгон
                    if (--waitTicks <= 0) {
                        phase = Phase.ACCEL;
                    }
                    break;
                case ACCEL:
                    if (--stepTicks <= 0) {
                        stepTicks = cfg.transitionIntervalTicks();
                        stepToward(desired, cfg, manual);
                    }
                    break;
                case STABLE:
                    // Уже на целевой скорости — ничего не делаем
                    break;
                default:
                    break;
            }

            applyVelocity();

            // Периодическое обновление экрана (экшнбар / боссбар)
            if (++displayTicks >= cfg.displayUpdateIntervalTicks()) {
                displayTicks = 0;
                updateDisplay(player, cfg, desired);
            }
        } catch (Throwable t) {
            // Никогда не роняем тик сервера
            QQMinecarts plugin = manager.plugin();
            plugin.getLogger().severe("Ошибка в задаче вагонетки: " + t.getMessage());
            if (manager.config().debug()) {
                t.printStackTrace();
            }
            finish();
        }
    }

    // ================== ПЕРЕКЛЮЧЕНИЕ УРОВНЕЙ ==================

    /** Следующий (ещё не выбранный) уровень по кругу — для проверки прав. */
    SpeedLevel nextLevel() {
        PluginConfig cfg = manager.config();
        int n = levelIndex + 1;
        if (n >= cfg.levelCount()) {
            n = 0;
        }
        return cfg.level(n);
    }

    /** Переключить скорость на следующий уровень (по кругу). */
    void cycle() {
        PluginConfig cfg = manager.config();
        int n = levelIndex + 1;
        if (n >= cfg.levelCount()) {
            n = 0;
        }
        levelIndex = n;
        SpeedLevel level = cfg.level(n);

        if (level.isDefault()) {
            // Возврат к ручному управлению — скорость плавно спадает к 0
            phase = Phase.ACCEL;
            waitTicks = 0;
            stepTicks = 0;
        } else {
            // Автономный режим: сначала выжидаем, потом плавно разгоняемся
            phase = Phase.WAIT;
            waitTicks = Math.max(1, cfg.transitionDelayTicks());
            stepTicks = 0;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            Messages m = manager.messages();
            player.sendMessage(m.get("messages.cycle-to",
                    "level", levelText(cfg, level),
                    "speed", formatSpeed(level.speed())));
        }
        manager.plugin().debug("Игрок " + playerId + " переключил уровень: " + level.key());
    }

    // ================== СКОРОСТЬ ==================

    /** Один шаг текущей скорости в сторону целевой. */
    private void stepToward(double desired, PluginConfig cfg, boolean manual) {
        double diff = desired - currentSpeed;
        double step = cfg.transitionStep();
        if (diff >= step) {
            currentSpeed += step;
        } else if (diff <= -step) {
            currentSpeed -= step;
        } else {
            currentSpeed = desired;
            if (!manual) {
                phase = Phase.STABLE;
            }
        }
    }

    /** Применяем текущую скорость к вагонетке (в направлении её курса). */
    private void applyVelocity() {
        if (Math.abs(currentSpeed) < EPS_DELTA) {
            // Скорость почти нулевая — пусть ванильное трение остановит вагонетку
            return;
        }
        double rad = Math.toRadians(cart.getLocation().getYaw());
        double dx = -Math.sin(rad) * currentSpeed;
        double dz = Math.cos(rad) * currentSpeed;

        // Сохраняем вертикальную составляющую (для уклонов и рельс в горку)
        Vector velocity = cart.getVelocity();
        velocity.setX(dx);
        velocity.setZ(dz);
        cart.setVelocity(velocity);
    }

    // ================== ОТОБРАЖЕНИЕ ==================

    private void updateDisplay(Player player, PluginConfig cfg, double desired) {
        Messages m = manager.messages();
        String levelText = levelText(cfg, cfg.level(levelIndex));
        String speedText = formatSpeed(currentSpeed);

        boolean show = cfg.displayShowAlways();
        if (!show) {
            show = Math.abs(currentSpeed - desired) > EPS_DELTA;
        }
        if (!show) {
            hideDisplay();
            return;
        }

        switch (cfg.displayType()) {
            case ACTIONBAR:
                player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(
                        m.getPlain("messages.actionbar",
                                "level", levelText, "speed", speedText)));
                break;
            case BOSSBAR:
                ensureBossBar(player, cfg);
                if (bossBar != null) {
                    double progress = clamp(currentSpeed / Math.max(0.01D, cfg.bossbarFullProgressAt()), 0.0D, 1.0D);
                    bossBar.setProgress(progress);
                    bossBar.setTitle(m.get("messages.bossbar-title",
                            "level", levelText, "speed", speedText));
                }
                break;
            default:
                break;
        }
    }

    private void ensureBossBar(Player player, PluginConfig cfg) {
        // Пересоздаём боссбар, если цвет/стиль изменились после /qqm reload
        if (bossBar != null
                && bossBarColor == cfg.bossbarColor()
                && bossBarStyle == cfg.bossbarStyle()) {
            return;
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
        bossBar = Bukkit.createBossBar("", cfg.bossbarColor(), cfg.bossbarStyle());
        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        bossBarColor = cfg.bossbarColor();
        bossBarStyle = cfg.bossbarStyle();
    }

    private void hideDisplay() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    private String levelText(PluginConfig cfg, SpeedLevel level) {
        if (level == null) return "?";
        if (level.isDefault()) {
            return manager.messages().getPlain("messages.level-default");
        }
        return level.key();
    }

    private static String formatSpeed(double speed) {
        return String.format(Locale.ROOT, "%.2f", speed);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ================== ОБСЛУЖИВАНИЕ ЖИЗНЕННОГО ЦИКЛА ==================

    /** Эта вагонетка? (используется для "последний севший управляет"). */
    boolean sameCart(UUID otherCartId) {
        return cartId.equals(otherCartId);
    }

    /** Отмена задачи и скрытие боссбара (без удаления из менеджера). */
    void cancel() {
        if (task != null && !task.isCancelled()) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // задача уже завершена/отменена — не страшно
            }
        }
        hideDisplay();
        manager.forget(playerId);
    }

    /** Полная очистка: отмена + удаление из менеджера. */
    private void finish() {
        cancel();
        manager.detach(playerId);
    }
}