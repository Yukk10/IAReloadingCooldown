package me.yukio.iareloading;

import org.bukkit.configuration.ConfigurationSection;

/** Битовые маски триггеров ItemsAdder, которые аддон умеет отслеживать. */
public final class Triggers {

    // interact / interact_mainhand / interact_offhand -> подключи
    public static final int CLICK_RIGHT = 1;
    public static final int CLICK_LEFT = 1 << 1;
    public static final int CLICK_RIGHT_SHIFT = 1 << 2;
    public static final int CLICK_LEFT_SHIFT = 1 << 3;
    public static final int CLICK_ENTITY = 1 << 4;

    // прочие события предмета
    public static final int FLAG_ATTACK = 1;
    public static final int FLAG_EAT = 1 << 1;
    public static final int FLAG_DRINK = 1 << 2;
    public static final int FLAG_DROP = 1 << 3;
    public static final int FLAG_BOW_SHOT = 1 << 4;
    public static final int FLAG_ITEM_THROW = 1 << 5;

    private Triggers() {
    }

    static int clickMask(ConfigurationSection interact) {
        if (interact == null) return 0;
        int mask = 0;
        for (String key : interact.getKeys(false)) {
            mask |= switch (key.toLowerCase(java.util.Locale.ROOT)) {
                case "right" -> CLICK_RIGHT;
                case "left" -> CLICK_LEFT;
                case "right_shift" -> CLICK_RIGHT_SHIFT;
                case "left_shift" -> CLICK_LEFT_SHIFT;
                case "entity" -> CLICK_ENTITY;
                default -> 0;
            };
        }
        return mask;
    }

    static int flags(ConfigurationSection events) {
        if (events == null) return 0;
        int f = 0;
        if (events.contains("attack")) f |= FLAG_ATTACK;
        if (events.contains("eat")) f |= FLAG_EAT;
        if (events.contains("drink")) f |= FLAG_DRINK;
        if (events.contains("drop")) f |= FLAG_DROP;
        if (events.contains("bow_shot")) f |= FLAG_BOW_SHOT;
        if (events.contains("item_throw")) f |= FLAG_ITEM_THROW;
        return f;
    }

    /**
     * Совпадает ли клик с маской. Если зажат шифт, а *_shift не задан —
     * используется обычный right/left (как и у ItemsAdder без отдельного _shift).
     */
    static boolean matchesClick(int mask, boolean right, boolean sneaking) {
        int plain = right ? CLICK_RIGHT : CLICK_LEFT;
        if (!sneaking) return (mask & plain) != 0;
        int shift = right ? CLICK_RIGHT_SHIFT : CLICK_LEFT_SHIFT;
        return (mask & shift) != 0 || (mask & plain) != 0;
    }
}
