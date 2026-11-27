package me.yukio.iareloading;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Неизменяемое описание предмета ItemsAdder с indicator: RELOADING.
 *
 * @param id        namespace:id предмета ItemsAdder
 * @param group     ванильная группа перезарядки (use_cooldown.cooldown_group)
 * @param ticks     длительность перезарядки
 * @param material  материал предмета (для быстрого префильтра), null если неизвестен
 * @param mainMask  клики (Triggers.CLICK_*), которые запускают действия в основной руке
 * @param offMask   клики, которые запускают действия во второй руке
 * @param flags     прочие триггеры (Triggers.FLAG_*)
 */
public record ReloadingItem(
        String id,
        NamespacedKey group,
        int ticks,
        @Nullable Material material,
        int mainMask,
        int offMask,
        int flags,
        UseCooldown component
) {

    public static ReloadingItem create(String id, NamespacedKey group, int ticks, @Nullable Material material,
                                       int mainMask, int offMask, int flags) {
        UseCooldown component = UseCooldown.useCooldown(Math.max(1, ticks) / 20.0f)
                .cooldownGroup(group)
                .build();
        return new ReloadingItem(id, group, Math.max(1, ticks), material, mainMask, offMask, flags, component);
    }

    /** true, если на стаке уже стоит наша группа перезарядки. */
    public boolean isStamped(ItemStack stack) {
        UseCooldown current = stack.getData(DataComponentTypes.USE_COOLDOWN);
        return current != null && group.equals(current.cooldownGroup());
    }

    /** Ставит компонент use_cooldown с нашей группой. Возвращает true, если стак изменён. */
    public boolean stamp(ItemStack stack) {
        if (isStamped(stack)) return false;
        stack.setData(DataComponentTypes.USE_COOLDOWN, component);
        return true;
    }
}
