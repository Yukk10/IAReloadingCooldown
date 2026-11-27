package me.yukio.iareloading;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Хранит активные перезарядки и синхронизирует их с ванильными (клиентская шторка на предмете).
 *
 * Оптимизация:
 *  - никаких повторяющихся задач: истёкшие записи удаляются лениво при проверке;
 *  - время в серверных тиках (как у ванилы) — шторка и блокировка заканчиваются в один тик;
 *  - для переживания выхода/рестарта в PDC игрока пишется абсолютное время окончания (epoch ms)
 *    только в момент старта перезарядки — без работы на выходе/выключении.
 *
 * Всё вызывается только с главного потока.
 */
public final class CooldownManager {

    private static final NamespacedKey PDC_KEY = new NamespacedKey(ContentScanner.NAMESPACE, "cooldowns");

    static final class Entry {
        final int expiryTick;
        final int startTick;
        final Class<?> startEvent; // какой тип события запустил перезарядку (null — восстановлена)

        Entry(int expiryTick, int startTick, Class<?> startEvent) {
            this.expiryTick = expiryTick;
            this.startTick = startTick;
            this.startEvent = startEvent;
        }
    }

    private final IAReloadingPlugin plugin;
    private final Map<UUID, Map<NamespacedKey, Entry>> active = new HashMap<>();
    /** Стаки-«носители» группы для отправки ванильной перезарядки без реального предмета. */
    private final Map<NamespacedKey, ItemStack> carriers = new HashMap<>();

    public CooldownManager(IAReloadingPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ проверки

    /** Активная запись или null (истёкшая удаляется). */
    Entry get(UUID uuid, NamespacedKey group, int now) {
        Map<NamespacedKey, Entry> map = active.get(uuid);
        if (map == null) return null;
        Entry e = map.get(group);
        if (e == null) return null;
        if (e.expiryTick <= now) {
            map.remove(group);
            if (map.isEmpty()) active.remove(uuid);
            return null;
        }
        return e;
    }

    public boolean hasAny(UUID uuid) {
        return active.containsKey(uuid);
    }

    // ------------------------------------------------------------------ старт / сброс

    public void start(Player player, ReloadingItem item, Class<?> eventType) {
        int now = Bukkit.getCurrentTick();
        active.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>(4))
                .put(item.group(), new Entry(now + item.ticks(), now, eventType));
        sendVanilla(player, item.group(), item.ticks());
        if (plugin.persist()) writePdc(player, item.group(), System.currentTimeMillis() + item.ticks() * 50L);
        // Ставим компонент группы на все копии предмета в инвентаре в следующем тике,
        // чтобы не мешать ItemsAdder, который сейчас обрабатывает этот же стак.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) stampInventory(player, item);
        });
    }

    public void clear(Player player) {
        Map<NamespacedKey, Entry> map = active.remove(player.getUniqueId());
        if (map != null) for (NamespacedKey group : map.keySet()) sendVanilla(player, group, 0);
        player.getPersistentDataContainer().remove(PDC_KEY);
    }

    // ------------------------------------------------------------------ жизненный цикл игрока

    /** Вход: восстановить перезарядки из PDC (защита от «перезашёл — КД сбросилось»). */
    public void onJoin(Player player) {
        if (!plugin.persist()) {
            player.getPersistentDataContainer().remove(PDC_KEY);
            return;
        }
        PersistentDataContainer root = player.getPersistentDataContainer();
        PersistentDataContainer stored = root.get(PDC_KEY, PersistentDataType.TAG_CONTAINER);
        if (stored == null) return;
        long nowMs = System.currentTimeMillis();
        int now = Bukkit.getCurrentTick();
        boolean dirty = false;
        Map<NamespacedKey, Entry> map = null;
        for (NamespacedKey group : stored.getKeys()) {
            Long until = stored.get(group, PersistentDataType.LONG);
            long leftMs = until == null ? 0 : until - nowMs;
            // Группы удалённых предметов не проверяем здесь: реестр может быть ещё не загружен
            // (старт аддона). Их уберёт onRegistryChanged, а запись в PDC просто истечёт.
            if (leftMs <= 0) {
                stored.remove(group);
                dirty = true;
                continue;
            }
            int ticks = (int) Math.min(Integer.MAX_VALUE / 2, (leftMs + 49) / 50);
            if (map == null) map = active.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>(4));
            map.put(group, new Entry(now + ticks, now, null));
        }
        if (dirty) {
            if (stored.isEmpty()) root.remove(PDC_KEY);
            else root.set(PDC_KEY, PersistentDataType.TAG_CONTAINER, stored);
        }
        if (map != null) resendLater(player);
    }

    /** Выход: данные уже в PDC, освобождаем память. */
    public void onQuit(Player player) {
        active.remove(player.getUniqueId());
    }

    /**
     * Клиент теряет все перезарядки при пересоздании игрока (смена мира, смерть/респавн, вход).
     * Сервер о них помнит — переотправляем оставшееся время через 1 тик.
     */
    public void resendLater(Player player) {
        if (!hasAny(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) resendNow(player);
        }, 1L);
    }

    public void resendNow(Player player) {
        Map<NamespacedKey, Entry> map = active.get(player.getUniqueId());
        if (map == null) return;
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<NamespacedKey, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<NamespacedKey, Entry> e = it.next();
            int left = e.getValue().expiryTick - now;
            if (left <= 0) {
                it.remove();
                continue;
            }
            sendVanilla(player, e.getKey(), left);
            ReloadingItem item = plugin.registry().byGroup(e.getKey());
            if (item != null) stampInventory(player, item);
        }
        if (map.isEmpty()) active.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ /iareload

    /** После перезагрузки ItemsAdder: убрать перезарядки удалённых/изменённых предметов, переотправить остальные. */
    public void onRegistryChanged(ItemRegistry registry) {
        carriers.keySet().removeIf(g -> !registry.hasGroup(g));
        Iterator<Map.Entry<UUID, Map<NamespacedKey, Entry>>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<NamespacedKey, Entry>> e = it.next();
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            e.getValue().keySet().removeIf(group -> {
                if (registry.hasGroup(group)) return false;
                sendVanilla(player, group, 0);
                return true;
            });
            if (e.getValue().isEmpty()) it.remove();
            else resendLater(player); // ItemsAdder мог пересоздать стаки и снять компонент
        }
    }

    public void shutdown() {
        active.clear();
        carriers.clear();
    }

    // ------------------------------------------------------------------ внутреннее

    private void sendVanilla(Player player, NamespacedKey group, int ticks) {
        // setCooldown(ItemStack) берёт группу из компонента use_cooldown стака-носителя:
        // клиент покажет шторку ровно на стаках с этой группой, другие IRON_SWORD не затронуты.
        ItemStack carrier = carriers.computeIfAbsent(group, g -> {
            ItemStack s = ItemStack.of(Material.STONE);
            s.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(1f).cooldownGroup(g).build());
            return s;
        });
        player.setCooldown(carrier, Math.max(0, ticks));
    }

    /** Ставит компонент группы на все копии предмета в инвентаре (41 слот, с префильтром по материалу). */
    void stampInventory(Player player, ReloadingItem item) {
        PlayerInventory inv = player.getInventory();
        int size = inv.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null) continue;
            if (item.material() != null ? stack.getType() != item.material() : stack.getType().isAir()) continue;
            if (item.isStamped(stack)) continue;
            ReloadingItem found = plugin.registry().identify(stack);
            if (found == null || !found.group().equals(item.group())) continue;
            if (found.stamp(stack)) inv.setItem(slot, stack);
        }
    }

    /** Ставит компонент на один стак, если это RELOADING-предмет (для подбора / смены слота). */
    boolean stampIfNeeded(ItemStack stack) {
        ReloadingItem item = plugin.registry().identify(stack);
        return item != null && item.stamp(stack);
    }

    private void writePdc(Player player, NamespacedKey group, long untilMs) {
        PersistentDataContainer root = player.getPersistentDataContainer();
        PersistentDataContainer stored = root.get(PDC_KEY, PersistentDataType.TAG_CONTAINER);
        if (stored == null) stored = root.getAdapterContext().newPersistentDataContainer();
        long nowMs = System.currentTimeMillis();
        for (NamespacedKey key : stored.getKeys()) { // чистим протухшие
            Long until = stored.get(key, PersistentDataType.LONG);
            if (until == null || until <= nowMs) stored.remove(key);
        }
        stored.set(group, PersistentDataType.LONG, untilMs);
        root.set(PDC_KEY, PersistentDataType.TAG_CONTAINER, stored);
    }
}
