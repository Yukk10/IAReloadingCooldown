package me.yukio.iareloading;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Оборачивает обработчики ItemsAdder для событий использования предмета.
 * Пока предмет на перезарядке, ItemsAdder просто не получает событие — его events/actions
 * не выполняются. Это работает независимо от внутренностей ItemsAdder.
 *
 * Обёртка идемпотентна: можно вызывать после каждого /iareload (ItemsAdder может перерегистрировать слушатели).
 */
public final class ListenerGate {

    private static final Class<?>[] EVENTS = {
            PlayerInteractEvent.class,
            PlayerInteractEntityEvent.class,
            PlayerInteractAtEntityEvent.class,
            EntityDamageByEntityEvent.class, // делит HandlerList с EntityDamageEvent
            PlayerItemConsumeEvent.class,
            PlayerDropItemEvent.class,
            EntityShootBowEvent.class,
            ProjectileLaunchEvent.class,
            PlayerLaunchProjectileEvent.class,
    };

    private final IAReloadingPlugin plugin;
    private final List<Gated> wrapped = new ArrayList<>();

    public ListenerGate(IAReloadingPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return сколько новых обработчиков обёрнуто */
    public int wrap(Plugin target) {
        if (target == null || !target.isEnabled()) return 0;
        wrapped.removeIf(g -> !isRegistered(g));
        int count = 0;
        for (HandlerList list : handlerLists()) {
            for (RegisteredListener rl : list.getRegisteredListeners()) {
                if (rl instanceof Gated || rl.getPlugin() != target) continue;
                Gated gated = new Gated(rl, plugin.gate());
                gated.list = list;
                list.unregister(rl);
                list.register(gated);
                wrapped.add(gated);
                count++;
            }
        }
        return count;
    }

    /** Вернуть оригинальные обработчики (выключение аддона). */
    public void unwrapAll() {
        for (Gated g : wrapped) {
            HandlerList list = g.list;
            if (list == null || !isRegistered(g)) continue;
            list.unregister(g);
            list.register(g.original);
        }
        wrapped.clear();
    }

    public int wrappedCount() {
        return wrapped.size();
    }

    private boolean isRegistered(Gated g) {
        if (g.list == null) return false;
        for (RegisteredListener rl : g.list.getRegisteredListeners()) if (rl == g) return true;
        return false;
    }

    private List<HandlerList> handlerLists() {
        Set<HandlerList> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<HandlerList> out = new ArrayList<>();
        for (Class<?> c : EVENTS) {
            try {
                HandlerList hl = (HandlerList) c.getMethod("getHandlerList").invoke(null);
                if (seen.add(hl)) out.add(hl);
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("Нет HandlerList для " + c.getSimpleName() + ": " + e);
            }
        }
        return out;
    }

    /** RegisteredListener-обёртка: те же listener/priority/plugin, но с проверкой перезарядки. */
    static final class Gated extends RegisteredListener {
        final RegisteredListener original;
        final UseGate gate;
        HandlerList list;

        Gated(RegisteredListener original, UseGate gate) {
            super(original.getListener(), (l, e) -> { }, original.getPriority(), original.getPlugin(),
                    original.isIgnoringCancelled());
            this.original = original;
            this.gate = gate;
        }

        @Override
        public void callEvent(Event event) throws EventException {
            if (isIgnoringCancelled() && event instanceof Cancellable c && c.isCancelled()) return;
            if (gate.shouldBlock(event)) return;
            original.callEvent(event);
        }
    }
}
