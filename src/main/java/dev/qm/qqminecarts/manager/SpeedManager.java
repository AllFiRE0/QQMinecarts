package dev.qm.qqminecarts.manager;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.config.ConfigManager;
import dev.qm.qqminecarts.config.Messages;
import dev.qm.qqminecarts.config.PluginConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Центральный менеджер: привязывает игроков к их вагонеткам и езде верхом,
 * обрабатывает ввод (клавиша F, пробел, W/S) и уровень скорости.
 *
 * Все изменяемые коллекции — потокобезопасные (ConcurrentHashMap),
 * потому что события и задачи приходят из разных потоков (регион игрока,
 * регион транспорта, глобальный планировщик).
 */
public final class SpeedManager {

    public static final String PERM_USE = "qqminecarts.use";
    public static final String PERM_BYPASS = "qqminecarts.bypass";

    private final QQMinecarts plugin;
    private final ConfigManager configManager;

    /** Активные контроллеры: UUID игрока -> контроллер вагонетки. */
    private final Map<UUID, MinecartController> controllers = new ConcurrentHashMap<>();

    /** Езда верхом на животных. */
    private final RideController rideController;

    public SpeedManager(QQMinecarts plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.rideController = new RideController(this);
    }

    // ================== ДОСТУП К ДАННЫМ ==================

    public QQMinecarts plugin() { return plugin; }

    public PluginConfig config() { return configManager.config(); }

    public Messages messages() { return configManager.messages(); }

    public boolean isBusy(UUID playerId) {
        return controllers.containsKey(playerId) || rideController.has(playerId);
    }

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
            messages().sendMessage(player, "messages.disabled-world");
        }
    }

    /** Игрок сел на животное — запускаем управление (если тип разрешён в конфиге). */
    public void attachRide(Player player, Entity vehicle) {
        rideController.attach(player, vehicle);
    }

    /** Игрок покинул транспорт или вышел — останавливаем управление. */
    public void detach(UUID playerId) {
        MinecartController controller = controllers.remove(playerId);
        if (controller != null) {
            controller.cancel();
        }
        rideController.detach(playerId);
    }

    /** Внутренняя очистка после завершения тик-задачи езды верхом. */
    void detachRide(UUID playerId) {
        rideController.detach(playerId);
    }

    /** Разрешена ли механика езды верхом на этой сущности. */
    public boolean isRideable(Entity vehicle) {
        return rideController.isRideable(vehicle);
    }

    // ================== УРОВЕНЬ СКОРОСТИ ==================

    /** Клавиша F: следующий уровень скорости (вагонетка или езда верхом). */
    public boolean cycle(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        boolean riding = controller == null && rideController.has(playerId);
        if (controller == null && !riding) return false;

        if (!isWorldAllowed(player.getWorld().getName(), player)) {
            messages().sendMessage(player, "messages.disabled-world");
            return false;
        }

        if (!player.hasPermission(PERM_USE)) {
            messages().sendMessage(player, "messages.no-permission");
            return false;
        }

        if (controller != null) {
            return controller.boost();
        }
        return rideController.cycle(player);
    }

    /** Пробел: сброс/остановка. holdingMoveKeys — игрок держит вперёд/назад. */
    public boolean reset(Player player, boolean holdingMoveKeys) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller != null) {
            return controller.reset(holdingMoveKeys);
        }
        return rideController.reset(player);
    }

    /** Игрок нажал клавиши движения (W/S): запоминаем состояние для авто-разгона
     *  и отпускаем тормоз остановки на энергорельсе. Для езды верхом W/S —
     *  признак, что игрок "скачет". */
    public boolean onMoveKeys(Player player, boolean forward, boolean backward) {
        MinecartController controller = controllers.get(player.getUniqueId());
        if (controller != null) {
            controller.setMovementKeys(forward, backward);
            return true;
        }
        rideController.setForwardHeld(player.getUniqueId(), forward);
        return rideController.has(player.getUniqueId());
    }

    /** Разрешена ли механика в указанном мире (надо ли проверять право bypass). */
    public boolean isWorldAllowed(String worldName, Player player) {
        if (worldName == null || worldName.isEmpty()) return true;
        if (!config().disabledWorlds().contains(worldName)) return true;
        return player.hasPermission(PERM_BYPASS);
    }

    // ================== ДАННЫЕ ДЛЯ ПЛЕЙСХОЛДЕРОВ ==================

    public boolean hasMinecart(UUID playerId) {
        return controllers.containsKey(playerId);
    }

    public boolean hasRide(UUID playerId) {
        return rideController.has(playerId);
    }

    /** Выбранный уровень скорости (0 — обычная). */
    public int level(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller != null) {
            return controller.currentLevelIndex();
        }
        return rideController.level(playerId);
    }

    /** Прибавка выбранного уровня (блоков в тик). 0 — обычная скорость. */
    public double levelBoost(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller != null) {
            return controller.selectedLevelSpeed();
        }
        return rideController.selectedLevelSpeed(playerId);
    }

    /** Базовая максимальная скорость транспорта (вагонетка без учёта бонуса
     *  — как в purpur.yml; животное — его атрибут скорости). 0 — не в транспорте. */
    public double baseMaxSpeed(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller != null) {
            return controller.baseMaxSpeed();
        }
        double ride = rideController.baseMaxSpeed(playerId);
        if (ride >= 0) {
            return ride;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Minecart) {
            // В вагонетке, но без привязки (нет прав/мир отключён) — всё равно
            // отдаём реальную максимальную скорость рельс из самой вагонетки.
            return ((Minecart) vehicle).getMaxSpeed();
        }
        return 0.0;
    }

    /** Текущая скорость движения (км/ч) из кэша контроллера, либо 0. */
    public double speedKmh(Player player) {
        UUID playerId = player.getUniqueId();
        MinecartController controller = controllers.get(playerId);
        if (controller != null) {
            return controller.speedKmh();
        }
        return rideController.speedKmh(playerId);
    }

    // ================== ЗАВЕРШЕНИЕ ==================

    /** Полная остановка при выключении плагина. */
    public void shutdown() {
        controllers.values().forEach(MinecartController::cancel);
        controllers.clear();
        rideController.shutdown();
    }
}