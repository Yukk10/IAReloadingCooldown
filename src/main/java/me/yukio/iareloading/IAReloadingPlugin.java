package me.yukio.iareloading;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class IAReloadingPlugin extends JavaPlugin {

    private volatile ItemRegistry registry = ItemRegistry.EMPTY;
    private CooldownManager cooldowns;
    private UseGate gate;
    private ListenerGate listenerGate;
    private final AtomicInteger scanGeneration = new AtomicInteger();

    private boolean debug;
    private boolean persist;
    private List<Path> contentFolders = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();

        cooldowns = new CooldownManager(this);
        gate = new UseGate(this);
        listenerGate = new ListenerGate(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        registerCommand();

        // Перезапуск аддона при живых игроках (plugman и т.п.) — восстановить их КД из PDC.
        for (Player p : Bukkit.getOnlinePlayers()) cooldowns.onJoin(p);

        reloadContent(null);
    }

    @Override
    public void onDisable() {
        if (listenerGate != null) listenerGate.unwrapAll();
        if (gate != null) gate.clearCache();
        if (cooldowns != null) cooldowns.shutdown();
        registry = ItemRegistry.EMPTY;
    }

    private void readConfig() {
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        persist = getConfig().getBoolean("persist", true);
        Path plugins = getDataFolder().toPath().getParent();
        List<Path> folders = new ArrayList<>();
        for (String s : getConfig().getStringList("content-folders")) folders.add(plugins.resolve(s).normalize());
        contentFolders = List.copyOf(folders);
    }

    /**
     * Асинхронно перечитывает конфиги ItemsAdder, затем на главном потоке подменяет реестр
     * и (пере)оборачивает слушатели ItemsAdder. Устаревшие результаты параллельных перезагрузок отбрасываются.
     */
    public void reloadContent(CommandSender feedback) {
        int generation = scanGeneration.incrementAndGet();
        List<Path> folders = contentFolders;
        ContentScanner scanner = new ContentScanner(getLogger(), debug);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, ReloadingItem> items = scanner.scan(folders);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!isEnabled() || generation != scanGeneration.get()) return;
                ItemRegistry fresh = new ItemRegistry(items);
                registry = fresh;
                gate.clearCache();
                cooldowns.onRegistryChanged(fresh);
                int newly = listenerGate.wrap(itemsAdder());
                String msg = "RELOADING-предметов: " + fresh.size() + ", обработчиков ItemsAdder под контролем: "
                        + listenerGate.wrappedCount() + (newly > 0 ? " (+" + newly + ")" : "");
                getLogger().info(msg);
                if (feedback != null) feedback.sendRichMessage("<green>[IAReloading] " + msg);
                if (fresh.size() > 0 && listenerGate.wrappedCount() == 0) {
                    getLogger().warning("Не найдено ни одного обработчика ItemsAdder для событий предметов — "
                            + "блокировка действий во время перезарядки работать не будет.");
                }
            });
        });
    }

    private Plugin itemsAdder() {
        return Bukkit.getPluginManager().getPlugin("ItemsAdder");
    }

    private void registerCommand() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("iareloading")
                    .requires(src -> src.getSender().hasPermission("iareloading.admin"))
                    .then(Commands.literal("reload").executes(ctx -> {
                        readConfig();
                        reloadContent(ctx.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("info").executes(ctx -> {
                        CommandSender s = ctx.getSource().getSender();
                        s.sendRichMessage("<gray>[IAReloading] предметов: <white>" + registry.size()
                                + "</white>, обработчиков ItemsAdder под контролем: <white>" + listenerGate.wrappedCount());
                        for (ReloadingItem item : registry.items()) {
                            s.sendRichMessage("<gray> - <white>" + item.id() + "</white> " + item.ticks() + "t <dark_gray>" + item.group());
                        }
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("clear")
                            .then(Commands.argument("targets", ArgumentTypes.players()).executes(ctx -> {
                                List<Player> targets = ctx.getArgument("targets", PlayerSelectorArgumentResolver.class)
                                        .resolve(ctx.getSource());
                                for (Player p : targets) cooldowns.clear(p);
                                ctx.getSource().getSender().sendRichMessage("<green>[IAReloading] перезарядки сброшены: " + targets.size());
                                return Command.SINGLE_SUCCESS;
                            })))
                    .build();
            event.registrar().register(node, "IAReloadingCooldown", List.of("iarl"));
        });
    }

    public ItemRegistry registry() {
        return registry;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public UseGate gate() {
        return gate;
    }

    public boolean debug() {
        return debug;
    }

    public boolean persist() {
        return persist;
    }
}
