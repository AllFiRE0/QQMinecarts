package dev.qm.qqminecarts.listener;

import dev.qm.qqminecarts.manager.SpeedManager;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/**
 * Отслеживает посадку/выход игрока в вагонетку и запускает/останавливает
 * контроль скорости.
 */
public final class VehicleListener implements Listener {

    private final SpeedManager manager;

    public VehicleListener(SpeedManager manager) {
        this.manager = manager;
    }

    /** Игрок сел в вагонетку. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        try {
            if (!(event.getEntered() instanceof Player player)) return;
            if (!(event.getVehicle() instanceof Minecart cart)) return;
            manager.attach(player, cart);
        } catch (Throwable t) {
            log(t);
        }
    }

    /** Игрок покинул вагонетку. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExit(VehicleExitEvent event) {
        try {
            if (!(event.getExited() instanceof Player player)) return;
            manager.detach(player.getUniqueId());
        } catch (Throwable t) {
            log(t);
        }
    }

    /** Игрок вышел с сервера — чистим состояние. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            manager.detach(event.getPlayer().getUniqueId());
        } catch (Throwable t) {
            log(t);
        }
    }

    private void log(Throwable t) {
        manager.plugin().getLogger().warning("Ошибка в VehicleListener: " + t.getMessage());
        if (manager.config().debug()) {
            t.printStackTrace();
        }
    }
}