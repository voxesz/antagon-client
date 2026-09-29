package studio.antagon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class Reflect {
    private static final ClassValue<Map<String, Field>> FIELDS =
            new ClassValue<Map<String, Field>>() {
                protected Map<String, Field> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<String, Field>();
                }
            };
    private static final ClassValue<Map<String, List<Candidate>>> METHODS =
            new ClassValue<Map<String, List<Candidate>>>() {
                protected Map<String, List<Candidate>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<String, List<Candidate>>();
                }
            };

    private static final List<Class<?>> NUMERIC =
            Arrays.<Class<?>>asList(
                    Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);

    private static final class Candidate {
        final Method method;
        final Class<?>[] parameters;

        Candidate(Method method) {
            this.method = method;
            this.parameters = method.getParameterTypes();
        }
    }

    private Reflect() {}

    static Field findField(Class<?> type, String[] names) throws NoSuchFieldException {
        Map<String, Field> fields = FIELDS.get(type);
        Field cached = fields.get(names[0]);
        if (cached != null) return cached;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    fields.put(names[0], field);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + ": " + Arrays.toString(names));
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

    private static List<Candidate> methods(Class<?> type, String[] names) {
        Map<String, List<Candidate>> cache = METHODS.get(type);
        List<Candidate> cached = cache.get(names[0]);
        if (cached != null) return cached;
        List<Candidate> found = new ArrayList<Candidate>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)) continue;
                    method.setAccessible(true);
                    found.add(new Candidate(method));
                }
            }
        }
        cache.put(names[0], found);
        return found;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }

    private static int match(Class<?> type, Object argument) {
        if (argument == null) return type.isPrimitive() ? -1 : 0;
        Class<?> actual = argument.getClass();
        if (boxed(type) == actual) return 4;
        if (type.isInstance(argument)) return 2;
        if (!type.isPrimitive() || !(argument instanceof Number)) return -1;
        int source = NUMERIC.indexOf(actual), target = NUMERIC.indexOf(boxed(type));
        return source >= 0 && target > source ? 1 : -1;
    }

    static Object invoke(Class<?> type, Object target, String[] names, Object... args)
            throws Exception {
        Method best = null;
        int bestScore = -1;
        for (Candidate candidate : methods(type, names)) {
            Method method = candidate.method;
            Class<?>[] parameters = candidate.parameters;
            if (parameters.length != args.length
                    || target == null && !Modifier.isStatic(method.getModifiers())) continue;
            int score = 0;
            for (int i = 0; i < args.length; i++) {
                int value = match(parameters[i], args[i]);
                if (value < 0) {
                    score = -1;
                    break;
                }
                score += value;
            }
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null)
            throw new NoSuchMethodException(type.getName() + ": " + Arrays.toString(names));
        return best.invoke(target, args);
    }

    static Object call(Object target, String[] names, Object... args) throws Exception {
        return invoke(target.getClass(), target, names, args);
    }

    static double d(Object target, String name) throws Exception {
        return findField(target.getClass(), new String[] {name}).getDouble(target);
    }

    static double lerp(Object entity, String last, String now, float partialTick) throws Exception {
        double previous = d(entity, last), current = d(entity, now);
        return previous + (current - previous) * partialTick - current;
    }
}
