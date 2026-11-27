package me.yukio.iareloading;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Читает конфиги ItemsAdder и собирает предметы с indicator: RELOADING.
 * Работает только с файлами (без Bukkit API) — безопасно вызывать асинхронно.
 */
public final class ContentScanner {

    public static final String NAMESPACE = "iareloading";
    private static final String INDICATOR = "RELOADING";

    private final Logger logger;
    private final boolean debug;

    public ContentScanner(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    public Map<String, ReloadingItem> scan(List<Path> roots) {
        Map<String, ReloadingItem> result = new HashMap<>();
        int files = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> ymls = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".yml") || n.endsWith(".yaml");
                }).filter(Files::isRegularFile).forEach(ymls::add);
            } catch (IOException e) {
                logger.warning("Не удалось прочитать папку " + root + ": " + e.getMessage());
                continue;
            }
            for (Path file : ymls) {
                files++;
                scanFile(file, result);
            }
        }
        if (debug) logger.info("[debug] Просканировано файлов: " + files + ", RELOADING-предметов: " + result.size());
        return result;
    }

    private void scanFile(Path file, Map<String, ReloadingItem> out) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            logger.warning("Не удалось прочитать " + file + ": " + e.getMessage());
            return;
        }
        // Быстрый отсев: в 99% файлов нет ни RELOADING, ни reloading: — не тратим время на YAML-парсинг.
        if (!raw.contains(INDICATOR) && !raw.contains("reloading")) return;

        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.loadFromString(raw);
        } catch (Exception e) {
            // ItemsAdder сам сообщит об ошибке синтаксиса — не дублируем шум.
            if (debug) logger.info("[debug] YAML-ошибка в " + file + ": " + e.getMessage());
            return;
        }

        String namespace = yml.getString("info.namespace");
        ConfigurationSection items = yml.getConfigurationSection("items");
        if (namespace == null || items == null) return;

        for (String itemKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemKey);
            if (item == null || !item.getBoolean("enabled", true)) continue;
            ConfigurationSection cd = item.getConfigurationSection("events_settings.cooldown");
            if (cd == null) continue;

            String indicator = cd.getString("indicator", "");
            boolean reloading = INDICATOR.equalsIgnoreCase(indicator) || cd.getBoolean("reloading", false);
            if (!reloading) continue;

            String id = namespace + ":" + itemKey;
            int ticks = cd.getInt("ticks", 20);
            if (ticks <= 0) {
                logger.warning(id + ": ticks должен быть > 0, предмет пропущен");
                continue;
            }

            NamespacedKey group = buildGroup(namespace, itemKey, cd.getString("group"));
            if (group == null) {
                logger.warning(id + ": некорректный group '" + cd.getString("group") + "', предмет пропущен");
                continue;
            }

            Material material = null;
            String matName = item.getString("resource.material");
            if (matName != null) material = Material.matchMaterial(matName);

            ConfigurationSection events = item.getConfigurationSection("events");
            int all = 0, main = 0, off = 0;
            if (events != null) {
                all = Triggers.clickMask(events.getConfigurationSection("interact"));
                main = all | Triggers.clickMask(events.getConfigurationSection("interact_mainhand"));
                off = all | Triggers.clickMask(events.getConfigurationSection("interact_offhand"));
            }
            int flags = Triggers.flags(events);
            if (main == 0 && off == 0 && flags == 0) {
                logger.warning(id + ": нет поддерживаемых событий (interact/interact_mainhand/interact_offhand, attack, eat, drink, drop, bow_shot, item_throw) — перезарядка не будет запускаться");
            }

            ReloadingItem prev = out.put(id, ReloadingItem.create(id, group, ticks, material, main, off, flags));
            if (prev != null) logger.warning("Дубликат предмета " + id + " (" + file.getFileName() + ")");
            if (debug) logger.info("[debug] " + id + " ticks=" + ticks + " group=" + group + " material=" + material
                    + " main=" + Integer.toBinaryString(main) + " off=" + Integer.toBinaryString(off)
                    + " flags=" + Integer.toBinaryString(flags));
        }
    }

    /** Группа по умолчанию — свой id предмета; можно задать общую: cooldown.group: my_group */
    private static NamespacedKey buildGroup(String namespace, String itemKey, String custom) {
        String value = custom == null || custom.isBlank()
                ? namespace + "/" + itemKey
                : "group/" + custom;
        return NamespacedKey.fromString(NAMESPACE + ":" + value.toLowerCase(Locale.ROOT));
    }
}
