package dev.qm.qqminecarts.manager;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.ConfigManager;
import dev.qm.qqminecarts.config.Messages;
import dev.qm.qqminecarts.config.PluginConfig;
import dev.qm.qqminecarts.config.SpeedLevel;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Центральный менеджер: привязывает игроков к их вагонеткам, обрабатывает
 * ввод (клавиша W), право на уровни и переключение скорости.
 *
 * Все изменяемые коллекции — потокобезопасные (ConcurrentHashMap),
 * потому что события и задачи приходят из разных потоков (регион игрока,
 * регион вагонетки, глобальный планировщик).
 */
public final class SpeedManager {

    public static final String PERM_USE = "qqminecarts.use";
    public static final String PERM_BYPASS = "qqminecarts.bypass";

    /** Базовое право на уровень скорости: qqminecarts.speed.<key> */
    public static final String PERM_SPEED = "qqminecarts.speed.";

    /** Время (в наносекундах), после которого отсутствие инпута
     *  считается "клавиша W отпущена". 0.4 секунды. */
    private static final long INPUT_TIMEOUT_NANOS = 400_000_000L;

    /** Окно, в котором ЛКМ считается ударом по сущности, а не кликом в воздухе. */
    private static final long ATTACK_RECENT_NANOS = 700_000_000L;

    /** Состояние ввода игрока — обновляется из события PlayerInputEvent
     *  (поток региона игрока), читается в задаче вагонетки (поток региона
     *  вагонетки). Поля volatile для безопасной межпотоковой видимости. */
    static final class InputState {
        volatile boolean forward;
        volatile long lastInputNanos = System.nanoTime();
    }

    private final QQMinecarts plugin;
    private final ConfigManager configManager;

    /** Активные контроллеры: UUID игрока -> контроллер вагонетки. */
    private final Map<UUID, MinecartController> controllers = new ConcurrentHashMap<>();
    /** Ввод игроков (клавиша W и др.). */
    private final Map<UUID, InputState> inputStates = new ConcurrentHashMap<>();
    /** Время последнего удара в вагонетке (для распознавания "ЛКМ по сущности"). */
    private final Map<UUID, Long> lastAttackNanos = new ConcurrentHashMap<>();

    public SpeedManager(QQMinecarts plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    // ================== ДОСТУП К ДАННЫМ ==================

    public QQMinecarts plugin() { return plugin; }

    public PluginConfig config() { return configManager.config(); }

    public Messages messages() { return configManager.messages(); }

    public boolean isBusy(UUID playerId) { return controllers.containsKey(playerId); }

    // ================== ПРИВЯЗКА / ОТВЯЗКА ==================

    /** Игрок сел в вагонетку — запускаем управление. */
    public void attach(Player player, Minecart cart) {
        UUID playerId = player.getUniqueId();
        detach(playerId);

        // Если игроков в одной вагонетке несколько — управляет последний севший
        for (Map.Entry<UUID, MinecartController> entry : controllers.entrySet()) {
            if (entry.getValue().sameCart(cart.getUniqueId())) {
                detach(entry.getKey());
            }
        }

        if (isWorldAllowed(cart.getWorld().getName(), player)) {
            MinecartController controller = new MinecartController(this, player, cart);
            controllers.put(playerId, controller);
            plugin.debug("Привязка: " + player.getName() + " -> вагонетка #" + cart.getUniqueId());
        } else {
            player.sendMessage(messages().get("messages.disabled-world"));
        }
    }

    /** Игрок покинул вагонетку или вышел — останавливаем управление. */
    public void detach(UUID playerId) {
        MinecartController controller = controllers.remove(playerId);
        if (controller != null) {
            controller.cancel();
        }
    }

    // ================== ПЕРЕКЛЮЧЕНИЕ СКОРОСТИ ==================

    /** Переключение скорости на следующий уровень (F или ЛКМ). */
    public boolean cycle(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller == null) return false;

        if (!isWorldAllowed(player.getWorld().getName(), player)) {
            player.sendMessage(messages().get("messages.disabled-world"));
            return false;
        }

        // Проверка права на следующий уровень (если включено в конфиге)
        if (config().permissionPerLevel()) {
            SpeedLevel next = controller.nextLevel();
            if (!next.isDefault() && !player.hasPermission(PERM_SPEED + next.key())) {
                player.sendMessage(messages().get("messages.speed-level-denied", "level", next.key()));
                return false;
            }
        }

        controller.cycle();
        return true;
    }

    /** Разрешена ли механика в указанном мире (надо ли проверять право bypass). */
    public boolean isWorldAllowed(String worldName, Player player) {
        if (worldName == null || worldName.isEmpty()) return true;
        if (!config().disabledWorlds().contains(worldName)) return true;
        return player.hasPermission(PERM_BYPASS);
    }

    // ================== ВВОД (КЛАВИША W) ==================

    /** Обновление ввода из PlayerInputEvent. */
    public void handleInput(Player player, boolean forward) {
        UUID playerId = player.getUniqueId();
        InputState state = inputStates.computeIfAbsent(playerId, k -> new InputState());
        state.forward = forward;
        state.lastInputNanos = System.nanoTime();
    }

    /** Зажата ли W в данный момент (с учётом таймаута отсутствия инпута). */
    public boolean isForwardHeld(UUID playerId) {
        InputState state = inputStates.get(playerId);
        return state != null
                && state.forward
                && (System.nanoTime() - state.lastInputNanos) < INPUT_TIMEOUT_NANOS;
    }

    // ================== РАСПОЗНАВАНИЕ "ЛКМ ПО СУЩЕСТВУ" ==================

    /** Запоминаем, что игрок только что ударил сущность. */
    public void markAttack(Player player) {
        lastAttackNanos.put(player.getUniqueId(), System.nanoTime());
    }

    /** Была ли атака сущности совсем недавно (удар, а не клик в воздухе). */
    public boolean attackedRecently(Player player) {
        Long t = lastAttackNanos.get(player.getUniqueId());
        return t != null && (System.nanoTime() - t) < ATTACK_RECENT_NANOS;
    }

    /** Очистка вспомогательных данных игрока при отвязке. */
    void forget(UUID playerId) {
        inputStates.remove(playerId);
        lastAttackNanos.remove(playerId);
    }

    // ================== ЗАВЕРШЕНИЕ ==================

    /** Полная остановка при выключении плагина. */
    public void shutdown() {
        controllers.values().forEach(MinecartController::cancel);
        controllers.clear();
        inputStates.clear();
        lastAttackNanos.clear();
    }
}