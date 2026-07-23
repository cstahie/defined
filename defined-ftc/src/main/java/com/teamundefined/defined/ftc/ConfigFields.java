package com.teamundefined.defined.ftc;

import com.teamundefined.defined.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Builds {@link PreStartMenu} rows from your config class by name, so you list the settings
 * you want to edit instead of writing a getter/setter pair for each one.
 *
 * <p>Given a typical FTC config holder:
 *
 * <pre>{@code
 * public class Config {
 *     public static Alliance color = Alliance.RED;
 *
 *     public static class Telemetry {
 *         public static boolean TELEMETRY_ON = true;
 *     }
 *     public static class Flywheel {
 *         public static double SHOT_VELOCITY = 2100;
 *     }
 * }
 * }</pre>
 *
 * you get an editable menu with one line:
 *
 * <pre>{@code
 * enablePreStartMenu(Config.class,
 *         "Config.color", "Telemetry.TELEMETRY_ON", "Flywheel.SHOT_VELOCITY");
 * }</pre>
 *
 * <h2>Key format</h2>
 * {@code "<Owner>.<FIELD>"}. {@code Owner} is either your root config class's simple name
 * (so {@code "Config.color"} reaches a field on {@code Config} itself) or the simple name of
 * one of its nested classes ({@code "Telemetry.TELEMETRY_ON"}). Fields must be
 * {@code public static}.
 *
 * <h2>Supported types</h2>
 * {@code boolean}, any {@code enum} (cycles through its constants), and the numeric
 * primitives — {@code int}/{@code long} step by 1, {@code double}/{@code float} by 0.1,
 * both scaled by the menu's coarse/fine multiplier.
 *
 * <p>A key that names no field, or names a field of an unsupported type, is skipped with a
 * warning rather than crashing the OpMode — a typo costs you a row, not a match. Reflection
 * runs once per key during init, never in the loop.
 */
public final class ConfigFields {

    private static final String TAG = "ConfigFields";

    private ConfigFields() {}

    /**
     * Resolves {@code key} against {@code rootClass} and builds the matching row.
     *
     * @return the row, or {@code null} if the key cannot be resolved (a warning is logged)
     */
    public static PreStartMenu.Item toItem(Class<?> rootClass, String key) {
        Field field = resolve(rootClass, key);
        if (field == null) {
            Log.w(TAG, "No config field for key: " + key);
            return null;
        }

        field.setAccessible(true);
        Class<?> type = field.getType();
        String label = shortLabel(key);

        if (type == boolean.class || type == Boolean.class) {
            return new PreStartMenu.BooleanItem(label,
                    () -> getBoolean(field),
                    v -> set(field, v));
        }

        if (type.isEnum()) {
            return enumItem(label, field, type);
        }

        if (type == double.class || type == float.class) {
            return new PreStartMenu.NumberItem(label,
                    () -> getNumber(field),
                    v -> set(field, type == float.class ? (Object) (float) v : (Object) v),
                    0.1);
        }

        if (type == int.class || type == long.class) {
            // Whole numbers: step by 1 and render without a misleading decimal tail.
            return new PreStartMenu.NumberItem(label,
                    () -> getNumber(field),
                    v -> set(field, type == int.class ? (Object) (int) Math.round(v)
                                                      : (Object) Math.round(v)),
                    1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1);
        }

        Log.w(TAG, "Unsupported config field type " + type.getSimpleName() + " for " + key);
        return null;
    }

    /** Adds a row per key, skipping any that fail to resolve. */
    public static void addAll(PreStartMenu menu, Class<?> rootClass, String... keys) {
        if (menu == null || keys == null) return;
        for (String key : keys) {
            PreStartMenu.Item item = toItem(rootClass, key);
            if (item != null) menu.add(item);
        }
    }

    // ---- internals ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PreStartMenu.Item enumItem(String label, Field field, Class<?> type) {
        return new PreStartMenu.EnumItem(label, (Class) type,
                () -> {
                    try {
                        return (Enum) field.get(null);
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                },
                v -> set(field, v));
    }

    /** {@code "Telemetry.TELEMETRY_ON"} → {@code "TELEMETRY_ON"}; keeps the display narrow. */
    private static String shortLabel(String key) {
        int dot = key.lastIndexOf('.');
        return (dot >= 0 && dot < key.length() - 1) ? key.substring(dot + 1) : key;
    }

    private static Field resolve(Class<?> rootClass, String key) {
        if (rootClass == null || key == null) return null;
        int dot = key.lastIndexOf('.');
        if (dot < 0) return null;

        String owner = key.substring(0, dot);
        String fieldName = key.substring(dot + 1);

        Class<?> ownerClass = rootClass.getSimpleName().equals(owner)
                ? rootClass
                : nestedClass(rootClass, owner);
        if (ownerClass == null) return null;

        try {
            Field f = ownerClass.getDeclaredField(fieldName);
            if (!Modifier.isStatic(f.getModifiers())) {
                Log.w(TAG, "Config field is not static: " + key);
                return null;
            }
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Class<?> nestedClass(Class<?> rootClass, String simpleName) {
        for (Class<?> c : rootClass.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) return c;
        }
        return null;
    }

    private static boolean getBoolean(Field field) {
        try {
            return field.getBoolean(null);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static double getNumber(Field field) {
        try {
            Object v = field.get(null);
            return (v instanceof Number) ? ((Number) v).doubleValue() : 0;
        } catch (IllegalAccessException e) {
            return 0;
        }
    }

    private static void set(Field field, Object value) {
        try {
            field.set(null, value);
        } catch (IllegalAccessException e) {
            Log.w(TAG, "Could not set " + field.getName() + ": " + e.getMessage());
        }
    }
}
