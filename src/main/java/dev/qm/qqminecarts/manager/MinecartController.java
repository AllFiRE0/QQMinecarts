package dev.qm.qqminecarts.manager;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.Messages;
import dev.qm.qqminecarts.config.PluginConfig;
import dev.qm.qqminecarts.config.SpeedLevel;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Контроллер — состояние управления одной вагонетки с игроком.
 *
 * Логика тикается через {@code EntityScheduler} вагонетки:
 * на Leaf/Folia/новых версиях Paper это поток региона (т.е. НЕ основной
 * поток). Вся математика скорости выполняется именно там.
 *
 * Принцип работы:
 * - клавиша F повышает уровень (1 -> 2 -> ... -> max);
 *   итоговая скорость вагонетки = текущая скорость + значение уровня;
 * - плагин НЕ переопределяет движение: повороты, разгон клавишами
 *   вперёд/назад и направление по взгляду игрока остаются ванильными.
 *   Плагин лишь поднимает лимит скорости рельс через
 *   {@link Minecart#setMaxSpeed(double)}:
 *   на энергорельсах до нового лимита разгоняет сама рельса,
 *   на обычных — удержание "вперёд" разгоняет до него;
 * - прибавка действует только на рельсах; на земле лимит ванильный
 *   и вагонетка не едет сама по себе;
 * - пробел (клавиша прыжка): на обычных — сброс до обычной скорости,
 *   на энергорельсах — полная остановка (в ванилле активная
 *   энергорельса не даёт остановиться).
 */
final class MinecartController implements Runnable {

    /** Порог, ниже которого скорость считается нулевой (вагонетку не трогаем). */
    private static final double EPS_DELTA = 0.0001D;

    /** Метка последнего успешного клика (анти-спам / защита от двойного события). */
    private long lastBoostNanos = 0L;

    private final SpeedManager manager;
    private final UUID playerId;
    private final UUID cartId;
    private final Minecart cart;

    /** Ванильный лимит скорости рельс (обычно 0.4 блок/тик). Запоминаем,
     *  чтобы вернуть его при сбросе/выходе. */
    private final double vanillaMaxSpeed;

    private ScheduledTask task;

    // ---- изменяемое состояние (доступно только из задачи планировщика) ----
    private int levelIndex = 0;       // 0 = обычная скорость, 1..N = уровни из конфига
    private double boost = 0.0D;      // текущая прибавка
    private double boostStart = 0.0D; // прибавка в момент начала разгона
    private double boostTarget = 0.0D;// куда едем (значение выбранного уровня)
    private int rampElapsed = 0;      // сколько тиков уже прошло в разгоне
    private int rampTotal = 0;        // всего тиков на разгон
    private int delayRemaining = 0;   // сколько тиков ещё ждать перед разгоном
    private int displayTicks = 0;     // счётчик обновления экрана

    /** Полная остановка (пробел стоя на энергорельсе): рельса не даёт
     *  остановиться ванильными средствами, поэтому держим maxSpeed=0
     *  и гасим скорость каждый тик, пока находимся на энергорельсе. */
    private boolean forceStopped = false;

    /** Хистерезис смены целевой прибавки. Позиция вагонетки по высоте
     *  "дрожит" (то рельса у неё под ногами, то пустота над ней), из-за чего
     *  определение типа рельс может мигать между обычными/энерго и кандидат
     *  в цель поддерживается, чтобы не перезапускать плавный разгон каждый тик. */
    private double stableTarget = Double.NaN;
    private int targetChangeTicks = 0;

    /** Текущая скорость вагонетки, км/ч (кэш для плейсхолдера player_speed).
     *  Измеряется по смещению позиции между тиками — реальную скорость нельзя
     *  взять из getVelocity(), а из maxSpeed видно лишь «потенциальную». */
    private double speedKmh = 0.0D;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    /** Смещение за последний измеренный тик (для авто-разгона и км/ч). */
    private double lastSampleDist = 0.0D;

    /** Усреднение км/ч скользящим окном (детали — см. код RideController).
     *  Окно-сумма компенсирует пропущенные планировщиком тики без флагов. */
    private static final int KMH_WINDOW = 16;
    private final java.util.ArrayDeque<Double> kmhSamples = new java.util.ArrayDeque<>(KMH_WINDOW);

    // ---- авто-разгон (режим auto) ----
    private boolean moveForward = false;
    private boolean moveBackward = false;
    private int autoTicks = 0;              // тиков активного разгона
    private int decelTicks = 0;             // тиков бездействия (слив скорости)

    private BossBar bossBar;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;

    MinecartController(SpeedManager manager, Player player, Minecart cart) {
        this.manager = manager;
        this.playerId = player.getUniqueId();
        this.cartId = cart.getUniqueId();
        this.cart = cart;
        double max = cart.getMaxSpeed();
        this.vanillaMaxSpeed = (max > 0.0D) ? max : 0.4D;

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

            // АВТО-РАЗГОН / СЛИВ (режим auto): пока игрок держит W/S и вагонетка
            // реально движется — каждые accel-tick-interval тиков уровень +1
            // (до максимума). Как только разгон прекратился (W/S отпущены или
            // тележка упёрлась/замерла) — каждые decel-minecart-tick-interval
            // тиков уровень -1 до обычного. Прибавка применяется существующим
            // плавным переходом ниже.
            if (cfg.autoMode() && !forceStopped) {
                if ((moveForward || moveBackward) && lastSampleDist > 0.002) {
                    decelTicks = 0;
                    if (++autoTicks >= cfg.accelTickIntervalTicks()) {
                        autoTicks = 0;
                        if (levelIndex < cfg.levelCount()) {
                            levelIndex++;
                            manager.plugin().debug("Авто-разгон вагонетки: уровень " + levelIndex);
                        }
                    }
                } else {
                    autoTicks = 0;
                    if (++decelTicks >= cfg.decelMinecartTickIntervalTicks()) {
                        decelTicks = 0;
                        if (levelIndex > 0) {
                            levelIndex--;
                            manager.plugin().debug("Слив скорости вагонетки: уровень " + levelIndex);
                        }
                    }
                }
            }

            // Цель уровня зависит от типа рельс (обычные/энерго) — пересчитываем
            // при смене типа рельс и плавно доезжаем до неё. Но только если
            // новая цель продержалась стабильно 2 тика (иначе мигание между
            // типами рельс на стыке постоянно перезапускало бы разгон).
            double desiredBoost = currentLevelSpeed(cfg);
            if (Math.abs(desiredBoost - boostTarget) > 1e-4) {
                if (Math.abs(desiredBoost - stableTarget) < 1e-6) {
                    if (++targetChangeTicks >= 2) {
                        startTransition(cfg, desiredBoost);
                        stableTarget = desiredBoost;
                        targetChangeTicks = 0;
                    }
                } else {
                    stableTarget = desiredBoost;
                    targetChangeTicks = 0;
                }
            } else {
                stableTarget = desiredBoost;
                targetChangeTicks = 0;
            }
            advanceTransition(cfg);

            // Режим полной остановки на энергорельсе: держим maxSpeed=0 и
            // гасим скорость, пока тележка стоит на энергорельсе.
            if (forceStopped) {
                if (!isOnPoweredRail()) {
                    forceStopped = false;
                } else {
                    cart.setMaxSpeed(0.0);
                    if (cart.getVelocity().lengthSquared() > 1e-6) {
                        cart.setVelocity(new Vector(0.0, 0.0, 0.0));
                    }
                    speedKmh = 0.0D;
                    lastX = cart.getLocation().getX();
                    lastZ = cart.getLocation().getZ();
                    if (++displayTicks >= cfg.displayUpdateIntervalTicks()) {
                        displayTicks = 0;
                        updateDisplay(player, cfg);
                    }
                    return;
                }
            }

            // ДВИЖЕНИЕ вагонеткой мы НЕ переопределяем: повороты, разгон
            // клавишами вперёд/назад и направление остаются ванильными.
            // Плагин меняет только МАКСИМАЛЬНУЮ скорость рельс и тормозит
            // на поворотах, чтобы вагонетка не слетала с рельс.
            boolean onRail = isOnAnyRail();
            boolean onCurve = onRail && cfg.curveMaxSpeed() > 0.0 && isOnCurve();

            double target = vanillaMaxSpeed + boost;
            if (onCurve) {
                // На повороте скорость не выше curve-max-speed (но не ниже ванильной)
                target = Math.max(vanillaMaxSpeed, Math.min(target, cfg.curveMaxSpeed()));
            }

            if (onRail) {
                if (Math.abs(cart.getMaxSpeed() - target) > 1e-4) {
                    cart.setMaxSpeed(target);
                }
            } else {
                if (Math.abs(cart.getMaxSpeed() - vanillaMaxSpeed) > 1e-4) {
                    cart.setMaxSpeed(vanillaMaxSpeed);
                }
            }

            // Подрезаем скорость до лимита (на повороте — до curve-max-speed).
            // Не задаёт направление — только ограничивает разгон "вперёд",
            // поэтому ванильное поведение клавиш сохраняется.
            if (onRail) {
                trimVelocityTo(target);
            }

            // Кэш скорости (км/ч) для плейсхолдера player_speed
            measureSpeedKmh();

            // Периодическое обновление экрана (экшнбар / боссбар)
            if (++displayTicks >= cfg.displayUpdateIntervalTicks()) {
                displayTicks = 0;
                updateDisplay(player, cfg);
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

    /** Один шаг плавного изменения прибавки (ждём delay -> разгон до цели). */
    private void advanceTransition(PluginConfig cfg) {
        if (delayRemaining > 0) {
            delayRemaining--;
            return;
        }
        if (rampElapsed >= rampTotal) {
            boost = boostTarget;
            return;
        }
        rampElapsed++;
        double progress = rampTotal == 0 ? 1.0 : (double) rampElapsed / rampTotal;
        boost = boostStart + (boostTarget - boostStart) * progress;
        if (Math.abs(boost) < EPS_DELTA) {
            boost = 0.0D;
        }
    }

    /** Начать плавный переход прибавки к указанному значению. */
    private void startTransition(PluginConfig cfg, double target) {
        boostStart = boost;
        boostTarget = Math.max(0.0D, target);
        rampElapsed = 0;
        rampTotal = Math.max(1, cfg.transitionRampTicks());
        delayRemaining = Math.max(0, cfg.transitionDelayTicks());
    }

    /** Блок рельсы под/в вагонетке. Позиция вагонетки по высоте нестабильна,
     *  поэтому рельсу ищем и в текущем блоке, и в блоке ниже. */
    private Block railBlock() {
        try {
            Block here = cart.getLocation().getBlock();
            if (isRailMaterial(here.getType())) {
                return here;
            }
            Block below = here.getRelative(BlockFace.DOWN);
            if (isRailMaterial(below.getType())) {
                return below;
            }
        } catch (Throwable ignored) {
            // вагонетка ещё недоступна — не страшно
        }
        return null;
    }

    /** Вагонетка стоит на каком-либо виде рельс. */
    private boolean isOnAnyRail() {
        return railBlock() != null;
    }

    /** Текущая позиция вагонетки стоит на энергорельсе. */
    private boolean isOnPoweredRail() {
        Block block = railBlock();
        return block != null && block.getType() == Material.POWERED_RAIL;
    }

    private static boolean isRailMaterial(Material material) {
        return material == Material.RAIL
                || material == Material.POWERED_RAIL
                || material == Material.DETECTOR_RAIL
                || material == Material.ACTIVATOR_RAIL;
    }

    /** На повороте ли вагонетка (текущий или следующий по ходу блок — рельса-поворот). */
    private boolean isOnCurve() {
        try {
            Block here = railBlock();
            if (here == null) {
                return false;
            }
            if (isCurveRail(here.getType())) {
                return true;
            }
            Vector v = cart.getVelocity();
            int dx = (v.getX() > 0.05) ? 1 : (v.getX() < -0.05 ? -1 : 0);
            int dz = (v.getZ() > 0.05) ? 1 : (v.getZ() < -0.05 ? -1 : 0);
            if (dx == 0 && dz == 0) {
                return false;
            }
            Block next = here.getWorld().getBlockAt(here.getX() + dx, here.getY(), here.getZ() + dz);
            return isCurveRail(next.getType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCurveRail(Material material) {
        try {
            if (!isRailMaterial(material)) {
                return false;
            }
            if (material.createBlockData() instanceof Rail rail) {
                Rail.Shape shape = rail.getShape();
                return shape == Rail.Shape.NORTH_EAST
                        || shape == Rail.Shape.NORTH_WEST
                        || shape == Rail.Shape.SOUTH_EAST
                        || shape == Rail.Shape.SOUTH_WEST;
            }
        } catch (Throwable ignored) {
            // блок может быть без BlockData — просто не считаем поворотом
        }
        return false;
    }

    /** Если скорость выше лимита — подрезаем её до лимита, НЕ меняя направление. */
    private void trimVelocityTo(double cap) {
        try {
            Vector velocity = cart.getVelocity();
            double length = velocity.length();
            if (length > cap && length > EPS_DELTA) {
                cart.setVelocity(velocity.clone().normalize().multiply(cap));
            }
        } catch (Throwable ignored) {
            // сущность уже недоступна — не страшно
        }
    }

    /** Скорость в км/ч: смещение по XY-плоскости за один тик * 72
     *  (20 тиков/с * 3.6), усреднённое скользящим окном (см. KMH_WINDOW).
     *  Не зависит от getVelocity(). */
    private void measureSpeedKmh() {
        double x = cart.getLocation().getX();
        double z = cart.getLocation().getZ();
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

    /** Значение текущего уровня под ТЕКУЩИЙ тип рельс (обычные/энерго). */
    private SpeedLevel currentLevel(PluginConfig cfg) {
        if (levelIndex < 1) {
            return null;
        }
        List<SpeedLevel> list = isOnPoweredRail() ? cfg.poweredSpeedLevels() : cfg.speedLevels();
        if (list.isEmpty()) {
            return null;
        }
        int idx = Math.min(levelIndex - 1, list.size() - 1);
        return list.get(idx);
    }

    private double currentLevelSpeed(PluginConfig cfg) {
        SpeedLevel level = currentLevel(cfg);
        return level == null ? 0.0 : level.speed();
    }

    // ================== УРОВНИ ==================

    /** Клавиша F: следующий уровень скорости. true — скорость изменена.
     *  В режиме auto не используется (разгон управляется временем). */
    boolean boost() {
        PluginConfig cfg = manager.config();
        if (cfg.autoMode()) return false;

        // Анти-спам: несколько событий одного клика (или безумный макрос)
        long now = System.nanoTime();
        if (now - lastBoostNanos < cfg.clickCooldownMs() * 1_000_000L) {
            return false;
        }
        lastBoostNanos = now;

        if (levelIndex >= cfg.levelCount()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                manager.messages().sendMessage(player, "messages.boost-max");
            }
            return false;
        }

        // Выходим из режима остановки и снова позволяем рельсам разгонять
        forceStopped = false;
        levelIndex++;

        Player player = Bukkit.getPlayer(playerId);
        SpeedLevel level = currentLevel(cfg);
        if (player != null && level != null) {
            manager.messages().sendMessage(player, "messages.boost",
                    "level", level.key(),
                    "speed", formatSpeed(level.speed()));
        }
        manager.plugin().debug("Игрок " + playerId + " выбрал уровень " + levelIndex
                + (level != null ? " (" + level.key() + "/" + level.speed() + ")" : ""));
        return true;
    }

    /** Пробел: на обычных рельсах — сброс до обычной скорости;
     *  на энергорельсе — полная остановка (рельсы не дают остановиться
     *  ванильными средствами).
     *  Если игрок ДЕРЖИТ вперёд/назад (holdingMoveKeys) — тормоз не
     *  блокируем: просто сбрасываем скорость, ванильные W/S продолжают
     *  вести вагонетку. */
    boolean reset(boolean holdingMoveKeys) {
        Player player = Bukkit.getPlayer(playerId);
        boolean onPowered = isOnPoweredRail();

        // При удержании вперёд/назад не входим в режим жёсткой остановки
        boolean stopRequested = onPowered && !holdingMoveKeys;

        if (levelIndex == 0 && boostTarget == 0.0 && !stopRequested && !forceStopped) {
            return false;
        }

        levelIndex = 0;

        if (stopRequested) {
            // Полная остановка: гасим скорость и держим maxSpeed=0,
            // пока стоим на энергорельсе (внешний тик рельсы перезаписывал
            // бы скорость обратно, поэтому и maxSpeed обнуляем).
            boost = 0.0;
            boostStart = 0.0;
            boostTarget = 0.0;
            rampElapsed = 0;
            rampTotal = 0;
            delayRemaining = 0;
            forceStopped = true;
            cart.setMaxSpeed(0.0);
            cart.setVelocity(new Vector(0.0, 0.0, 0.0));
            if (player != null) {
                manager.messages().sendMessage(player, "messages.boost-stopped");
            }
            manager.plugin().debug("Игрок " + playerId + " остановил вагонетку на энергорельсе");
            return true;
        }

        // Обычные рельсы: плавно возвращаемся к обычной скорости
        forceStopped = false;
        startTransition(manager.config(), 0.0D);
        if (player != null) {
            manager.messages().sendMessage(player, "messages.boost-reset");
        }
        manager.plugin().debug("Игрок " + playerId + " сбросил скорость к обычной");
        return true;
    }

    /** Обновить состояние клавиш движения (W/S). Нужно для авто-разгона
     *  и для отпускания тормоза после остановки на энергорельсе. */
    void setMovementKeys(boolean forward, boolean backward) {
        moveForward = forward;
        moveBackward = backward;
        // Остановленную пробелом вагонетку можно снова вести W/S
        if (forceStopped && (forward || backward)) {
            releaseBrake(forward, backward);
        }
    }

    /** Игрок нажал W/S, пока включён тормоз (остановка на энергорельсе):
     *  отпускаем тормоз, чтобы ванильные клавиши снова вели вагонетку. */
    boolean releaseBrake(boolean forward, boolean backward) {
        if (!forceStopped) return false;
        if (!forward && !backward) return false;
        forceStopped = false;
        manager.plugin().debug("Игрок " + playerId + " отпустил тормоз (W/S)");
        return true;
    }

    // ================== ОТОБРАЖЕНИЕ ==================

    private void updateDisplay(Player player, PluginConfig cfg) {
        Messages m = manager.messages();
        String levelText = levelText(cfg);
        String boostText = formatSpeed(boost);
        String kmhText = String.valueOf(Math.round(speedKmh));

        boolean show = cfg.displayShowAlways() || boost > EPS_DELTA;
        if (!show) {
            hideDisplay();
            return;
        }

        switch (cfg.displayType()) {
            case ACTIONBAR:
                m.sendActionBar(player, "messages.actionbar",
                        "level", levelText, "boost", boostText, "kmh", kmhText);
                break;
            case BOSSBAR:
                ensureBossBar(player, cfg);
                if (bossBar != null) {
                    double progress = clamp(boost / Math.max(0.01D, cfg.bossbarFullProgressAt()), 0.0D, 1.0D);
                    bossBar.setProgress(progress);
                    String title = m.get("messages.bossbar-title", "level", levelText, "boost", boostText, "kmh", kmhText);
                    if (!title.isEmpty()) {
                        bossBar.setTitle(title);
                    }
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

    private String levelText(PluginConfig cfg) {
        if (levelIndex == 0) {
            return manager.messages().getPlain("messages.level-default");
        }
        SpeedLevel level = currentLevel(cfg);
        return level != null ? level.key() : "?";
    }

    private static String formatSpeed(double speed) {
        return String.format(Locale.ROOT, "%.2f", speed);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ================== ДОСТУП (для плейсхолдеров) ==================

    int currentLevelIndex() {
        return levelIndex;
    }

    double selectedLevelSpeed() {
        return currentLevelSpeed(manager.config());
    }

    double baseMaxSpeed() {
        return vanillaMaxSpeed;
    }

    double speedKmh() {
        return speedKmh;
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
        // Возвращаем вагонетке ванильный лимит скорости рельс
        if (cart != null && cart.isValid()) {
            try {
                cart.setMaxSpeed(vanillaMaxSpeed);
            } catch (Throwable ignored) {
                // сущность уже недоступна — не страшно
            }
        }
        hideDisplay();
    }

    /** Полная очистка: отмена + удаление из менеджера. */
    private void finish() {
        cancel();
        manager.detach(playerId);
    }
}