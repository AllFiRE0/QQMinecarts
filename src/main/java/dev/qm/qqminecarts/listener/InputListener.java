package dev.qm.qqminecarts.listener;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.manager.SpeedManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Обрабатывает ввод игрока:
 * - клавиша F (PlayerSwapHandItemsEvent) — следующий уровень скорости
 *   (вагонетка или езда верхом на животном);
 * - клавиша прыжка / ПРОБЕЛ (PlayerInputEvent, Input#isJump) — сброс
 *   скорости или полная остановка на энергорельсе;
 * - клавиши движения W/S (PlayerInputEvent, Input#isForward/isBackward) —
 *   отпускание "тормоза" после остановки пробелом на энергорельсе.
 * ЛКМ плагином НЕ используется — игрок свободно атакует мобов и
 * разрушает блоки при езде.
 */
public final class InputListener implements Listener {

    private final QQMinecarts plugin;
    private final SpeedManager manager;

    public InputListener(QQMinecarts plugin, SpeedManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Клавиша F — следующий уровень скорости (только в режиме manual;
     *  в auto предметы свободно меняются местами). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        try {
            if (!manager.config().triggerSwap()) return;
            if (manager.config().autoMode()) return;
            Player player = event.getPlayer();
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof Minecart) && !manager.isRideable(vehicle)) return;

            // Не даём предметам меняться местами
            event.setCancelled(true);
            manager.cycle(player);
        } catch (Throwable t) {
            log(t);
        }
    }

    /** Пробел (клавиша прыжка) — сброс скорости; W/S — состояние движения
     *  (нужно и для авто-разгона, и для отпускания тормоза). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        try {
            org.bukkit.Input input = event.getInput();
            Player player = event.getPlayer();
            if (!manager.isBusy(player.getUniqueId())) return;

            boolean forward = input.isForward();
            boolean backward = input.isBackward();

            // Клавиши движения передаём в контроллер всегда
            manager.onMoveKeys(player, forward, backward);

            if (input.isJump() && manager.config().spaceReset()) {
                // Если игрок держит вперёд/назад — не блокируем движение,
                // а только сбрасываем скорость (ванильные W/S ведут тележку).
                manager.reset(player, forward || backward);
            }
        } catch (Throwable t) {
            log(t);
        }
    }

    private void log(Throwable t) {
        plugin.getLogger().warning("Ошибка в InputListener: " + t.getMessage());
        plugin.debug(t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}