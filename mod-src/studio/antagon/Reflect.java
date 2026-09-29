package studio.antagon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class Reflect {
    static final Map<String, Field> FIELDS = new HashMap<String, Field>();

    static final Map<String, Method> METHODS = new HashMap<String, Method>();

    static Field findField(Class<?> type, String[] names) throws NoSuchFieldException {
        String key = type.getName() + Arrays.toString(names);
        Field cached = FIELDS.get(key);
        if (cached != null) return cached;
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (Arrays.asList(names).contains(f.getName())) {
                    f.setAccessible(true);
                    FIELDS.put(key, f);
                    return f;
                }
        throw new NoSuchFieldException(Arrays.toString(names));
    }

    static Object field(Object target, String... names) throws Exception {
        return findField(target.getClass(), names).get(target);
    }

    static void setField(Object target, Object value, String... names) throws Exception {
        findField(target.getClass(), names).set(target, value);
    }

    static float getF(Object target, String name) throws Exception {
        return findField(target.getClass(), new String[] {name}).getFloat(target);
    }

    static void setF(Object target, String name, float value) throws Exception {
        findField(target.getClass(), new String[] {name}).setFloat(target, value);
    }

    static Object invoke(Class<?> type, Object target, String[] names, Object... args)
            throws Exception {
        String key = type.getName() + Arrays.toString(names) + args.length;
        Method cached = METHODS.get(key);
        if (cached == null) {
            search:
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if (Arrays.asList(names).contains(m.getName())
                            && m.getParameterTypes().length == args.length) {
                        m.setAccessible(true);
                        cached = m;
                        break search;
                    }
            if (cached == null) throw new NoSuchMethodException(Arrays.toString(names));
            METHODS.put(key, cached);
        }
        return cached.invoke(target, args);
    }

    static Object call(Object target, String[] names, Object... args) throws Exception {
        return invoke(target.getClass(), target, names, args);
    }

    static double d(Object target, String name) throws Exception {
        return findField(target.getClass(), new String[] {name}).getDouble(target);
    }

    static double lerp(Object e, String last, String now, float pt) throws Exception {
        double a = d(e, last), b = d(e, now);
        return a + (b - a) * pt - b;
    }
}
