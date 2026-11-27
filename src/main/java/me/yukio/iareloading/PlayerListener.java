package me.yukio.iareloading;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** Жизненный цикл игрока: вход/выход/смена мира/респавн, плюс /iareload. */
public final class PlayerListener implements Listener {

    private final IAReloadingPlugin plugin;

    public PlayerListener(IAReloadingPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.cooldowns().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.cooldowns().onQuit(event.getPlayer());
    }

    /** Клиент пересоздаёт игрока при смене мира и теряет шторки — переотправляем. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.cooldowns().resendLater(event.getPlayer());
    }

    /** То же после смерти/респавна (в том числе в том же мире). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        plugin.cooldowns().resendLater(event.getPlayer());
    }

    /**
     * Взял в руку копию предмета без компонента группы (например, получил из сундука во время КД) —
     * ставим компонент, чтобы шторка была видна. Работает только если у игрока есть активные КД.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.cooldowns().hasAny(player.getUniqueId())) return;
        ItemStack stack = player.getInventory().getItem(event.getNewSlot());
        if (!plugin.registry().mayMatch(stack)) return;
        if (plugin.cooldowns().stampIfNeeded(stack)) player.getInventory().setItem(event.getNewSlot(), stack);
    }

    /** Подобранная во время КД копия тоже получает компонент (и складывается с уже помеченными). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!plugin.cooldowns().hasAny(player.getUniqueId())) return;
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        if (!plugin.registry().mayMatch(stack)) return;
        if (plugin.cooldowns().stampIfNeeded(stack)) entity.setItemStack(stack);
    }

    /** /iareload и первая загрузка ItemsAdder: перечитать конфиги и переобернуть слушатели. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemsAdderLoad(ItemsAdderLoadDataEvent event) {
        plugin.reloadContent(null);
    }
}
