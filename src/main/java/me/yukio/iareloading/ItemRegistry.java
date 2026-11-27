package me.yukio.iareloading;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Неизменяемый снимок RELOADING-предметов. Подменяется целиком (volatile-ссылка в плагине),
 * поэтому чтение на главном потоке не требует синхронизации.
 */
public final class ItemRegistry {

    public static final ItemRegistry EMPTY = new ItemRegistry(Map.of());

    private final Map<String, ReloadingItem> byId;
    private final Map<NamespacedKey, ReloadingItem> byGroup;
    private final Set<Material> materials;
    /** Если у какого-то предмета материал неизвестен — префильтр по материалу отключается. */
    private final boolean anyMaterial;

    public ItemRegistry(Map<String, ReloadingItem> items) {
        this.byId = Map.copyOf(items);
        Map<NamespacedKey, ReloadingItem> groups = new HashMap<>();
        EnumSet<Material> mats = EnumSet.noneOf(Material.class);
        boolean any = false;
        for (ReloadingItem item : items.values()) {
            groups.putIfAbsent(item.group(), item);
            if (item.material() == null) any = true;
            else mats.add(item.material());
        }
        this.byGroup = Map.copyOf(groups);
        this.materials = mats;
        this.anyMaterial = any;
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    public Collection<ReloadingItem> items() {
        return byId.values();
    }

    public @Nullable ReloadingItem byGroup(NamespacedKey group) {
        return byGroup.get(group);
    }

    public boolean hasGroup(NamespacedKey group) {
        return byGroup.containsKey(group);
    }

    /** Дешёвая проверка: может ли стак вообще быть RELOADING-предметом. */
    public boolean mayMatch(@Nullable ItemStack stack) {
        if (stack == null) return false;
        Material type = stack.getType();
        if (type.isAir()) return false;
        return anyMaterial || materials.contains(type);
    }

    /** Определяет RELOADING-предмет по стаку (через API ItemsAdder), или null. */
    public @Nullable ReloadingItem identify(@Nullable ItemStack stack) {
        if (byId.isEmpty() || !mayMatch(stack)) return null;
        CustomStack custom = CustomStack.byItemStack(stack);
        if (custom == null) return null;
        return byId.get(custom.getNamespacedID());
    }
}
