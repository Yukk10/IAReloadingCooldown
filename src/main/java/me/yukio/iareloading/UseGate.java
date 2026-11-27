package me.yukio.iareloading;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Решает, нужно ли пропустить обработчики ItemsAdder для события (предмет на перезарядке),
 * и запускает перезарядку, когда ItemsAdder выполняет действия предмета.
 *
 * Решение принимается ОДИН раз на событие (сколько бы обработчиков ItemsAdder его ни слушали):
 * кольцевой кэш по identity события, устойчив к вложенным событиям.
 */
public final class UseGate {

    private static final int CACHE = 8;

    private final IAReloadingPlugin plugin;
    private final Event[] cachedEvents = new Event[CACHE];
    private final boolean[] cachedBlocked = new boolean[CACHE];
    private int cursor;

    public UseGate(IAReloadingPlugin plugin) {
        this.plugin = plugin;
    }

    /** true — обработчик ItemsAdder вызывать нельзя (предмет на перезарядке). */
    public boolean shouldBlock(Event event) {
        for (int i = 0; i < CACHE; i++) {
            if (cachedEvents[i] == event) return cachedBlocked[i];
        }
        boolean blocked;
        try {
            blocked = evaluate(event);
        } catch (Throwable t) {
            plugin.getLogger().warning("Ошибка при проверке перезарядки (" + event.getEventName() + "): " + t);
            blocked = false;
        }
        int slot = cursor;
        cursor = (cursor + 1) & (CACHE - 1);
        cachedEvents[slot] = event;
        cachedBlocked[slot] = blocked;
        return blocked;
    }

    /** Сбросить ссылки на события (чтобы не держать объекты игроков после выхода/выключения). */
    public void clearCache() {
        java.util.Arrays.fill(cachedEvents, null);
    }

    private boolean evaluate(Event event) {
        ItemRegistry registry = plugin.registry();
        if (registry.isEmpty()) return false;

        Player player;
        ItemStack stack;
        int click = -1;        // для interact: 1 = правый, 0 = левый, 2 = entity
        boolean offHand = false;
        int flag = 0;

        if (event instanceof PlayerInteractEvent e) {
            Action a = e.getAction();
            if (a == Action.PHYSICAL || e.getHand() == null) return false;
            player = e.getPlayer();
            stack = e.getItem();
            offHand = e.getHand() == EquipmentSlot.OFF_HAND;
            click = a.isRightClick() ? 1 : 0;
        } else if (event instanceof PlayerInteractEntityEvent e) { // + PlayerInteractAtEntityEvent
            player = e.getPlayer();
            offHand = e.getHand() == EquipmentSlot.OFF_HAND;
            stack = player.getInventory().getItem(e.getHand());
            click = 2;
        } else if (event instanceof EntityDamageByEntityEvent e) {
            if (!(e.getDamager() instanceof Player p)) return false;
            player = p;
            stack = p.getInventory().getItemInMainHand();
            flag = Triggers.FLAG_ATTACK;
        } else if (event instanceof PlayerItemConsumeEvent e) {
            player = e.getPlayer();
            stack = e.getItem();
            offHand = e.getHand() == EquipmentSlot.OFF_HAND;
            flag = Triggers.FLAG_EAT | Triggers.FLAG_DRINK;
        } else if (event instanceof PlayerDropItemEvent e) {
            player = e.getPlayer();
            stack = e.getItemDrop().getItemStack();
            flag = Triggers.FLAG_DROP;
        } else if (event instanceof EntityShootBowEvent e) {
            if (!(e.getEntity() instanceof Player p)) return false;
            player = p;
            stack = e.getBow();
            flag = Triggers.FLAG_BOW_SHOT;
        } else if (event instanceof PlayerLaunchProjectileEvent e) {
            player = e.getPlayer();
            stack = e.getItemStack();
            flag = Triggers.FLAG_ITEM_THROW;
        } else if (event instanceof ProjectileLaunchEvent e) {
            Projectile proj = e.getEntity();
            if (!(proj.getShooter() instanceof Player p) || !(proj instanceof ThrowableProjectile tp)) return false;
            player = p;
            stack = tp.getItem();
            flag = Triggers.FLAG_ITEM_THROW;
        } else {
            return false;
        }

        // Дешёвый префильтр по материалу до обращения к API ItemsAdder.
        if (!registry.mayMatch(stack)) return false;
        ReloadingItem item = registry.identify(stack);
        if (item == null) return false;

        boolean triggers;
        if (click >= 0) {
            int mask = offHand ? item.offMask() : item.mainMask();
            triggers = click == 2
                    ? (mask & Triggers.CLICK_ENTITY) != 0
                    : Triggers.matchesClick(mask, click == 1, player.isSneaking());
        } else {
            triggers = (item.flags() & flag) != 0;
        }
        if (!triggers) return false;

        CooldownManager cooldowns = plugin.cooldowns();
        int now = Bukkit.getCurrentTick();
        CooldownManager.Entry entry = cooldowns.get(player.getUniqueId(), item.group(), now);
        if (entry != null) {
            // Одно действие игрока порождает несколько разных событий в один тик
            // (например PlayerLaunchProjectileEvent + ProjectileLaunchEvent) — их пропускаем,
            // иначе ItemsAdder, слушающий «второе» событие, никогда бы не сработал.
            // Повторное событие того же типа (двойной клик/спам) — блокируем.
            boolean sameAction = entry.startTick == now && entry.startEvent != null
                    && entry.startEvent != event.getClass();
            if (sameAction) return false;
            if (plugin.debug()) plugin.getLogger().info("[debug] " + player.getName() + " " + item.id()
                    + " на перезарядке, осталось " + (entry.expiryTick - now) + "t (" + event.getEventName() + ")");
            return true;
        }

        cooldowns.start(player, item, event.getClass());
        if (plugin.debug()) plugin.getLogger().info("[debug] " + player.getName() + " " + item.id()
                + " -> перезарядка " + item.ticks() + "t (" + event.getEventName() + ")");
        return false;
    }
}
