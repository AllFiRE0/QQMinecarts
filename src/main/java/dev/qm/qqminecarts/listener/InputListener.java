package dev.qm.qqminecarts.listener;

import dev.qm.qqminecarts.QQMinecarts;
import dev.qm.qqminecarts.manager.SpeedManager;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Обрабатывает ввод игрока:
 * - клавиша F (PlayerSwapHandItemsEvent) — переключение скорости;
 * - ЛКМ в воздухе (PlayerAnimationEvent, ARM_SWING) — переключение скорости;
 * - PlayerInputEvent (клавиша W) — ручное управление на уровне "default";
 * - EntityDamageByEntityEvent — чтобы не срабатывало переключение при ударе
 *   по сущности (только для клика "в воздухе").
 */
public final class InputListener implements Listener {

    private final QQMinecarts plugin;
    private final SpeedManager manager;

    public InputListener(QQMinecarts plugin, SpeedManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Клавиша F — переключение скорости. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        try {
            if (!manager.config().triggerSwap()) return;
            Player player = event.getPlayer();
            if (!(player.getVehicle() instanceof Minecart)) return;

            // Не даём предметам меняться местами
            event.setCancelled(true);
            manager.cycle(player);
        } catch (Throwable t) {
            log(t);
        }
    }

    /** ЛКМ в воздухе (взмах рукой) — переключение скорости. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        try {
            if (!manager.config().triggerLeftClick()) return;
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

            Player player = event.getPlayer();
            if (!(player.getVehicle() instanceof Minecart)) return;

            // Если это был удар по сущности — не переключаем скорость
            if (manager.attackedRecently(player)) return;

            event.setCancelled(true); // скрываем взмах от других игроков
            manager.cycle(player);
        } catch (Throwable t) {
            log(t);
        }
    }

    /** Клавиша W — отслеживаем ввод для ручного управления. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInput(PlayerInputEvent event) {
        try {
            Player player = event.getPlayer();
            if (!manager.isBusy(player.getUniqueId())) return;
            manager.handleInput(player, event.getInput().isForward());
        } catch (Throwable t) {
            log(t);
        }
    }

    /** Помечаем удар по сущности, чтобы не путать его с кликом "в воздухе". */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        try {
            if (event.getDamager() instanceof Player player
                    && player.getVehicle() instanceof Minecart) {
                manager.markAttack(player);
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