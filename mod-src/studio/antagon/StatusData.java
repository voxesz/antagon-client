package studio.antagon;

import static studio.antagon.Reflect.*;

import java.util.*;

final class StatusData {
    static final class Armor {
        final Object stack;
        final int remaining, maximum, count;

        Armor(Object stack) throws Exception {
            this.stack = stack;
            maximum =
                    ((Number) call(stack, new String[] {"func_77958_k", "getMaxDamage"}))
                            .intValue();
            int damage =
                    ((Number) call(stack, new String[] {"func_77952_i", "getItemDamage"}))
                            .intValue();
            remaining = Math.max(0, maximum - damage);
            count = ((Number) field(stack, "field_77994_a", "stackSize")).intValue();
        }

        String text(String mode) {
            if (mode.equals("off")) return "";
            if (maximum <= 0) return count > 1 ? "x" + count : "";
            return mode.equals("restante")
                    ? remaining + "/" + maximum
                    : Math.round(remaining * 100f / maximum) + "%";
        }
    }

    static final class Potion {
        final String name;
        final int duration, icon;

        Potion(String name, int duration, int icon) {
            this.name = name;
            this.duration = duration;
            this.icon = icon;
        }
    }

    static Object item(int id) throws Exception {
        Class<?> type = Class.forName("net.minecraft.item.Item");
        Object item = invoke(type, null, new String[] {"func_150899_d", "getItemById"}, id);
        return Class.forName("net.minecraft.item.ItemStack")
                .getConstructor(type, int.class)
                .newInstance(item, 1);
    }

    static List<Armor> armor(Object player, boolean held, boolean preview) throws Exception {
        List<Armor> result = new ArrayList<Armor>();
        Object inventory = field(player, "field_71071_by", "inventory");
        Object[] armor = (Object[]) field(inventory, "field_70460_b", "armorInventory");
        for (int i = 3; i >= 0; i--) {
            Object stack = armor[i];
            if (stack == null && preview) stack = item(313 - i);
            if (stack != null) result.add(new Armor(stack));
        }
        if (held) {
            Object stack = call(player, new String[] {"func_70694_bm", "getHeldItem"});
            if (stack == null && preview) stack = item(276);
            if (stack != null) result.add(new Armor(stack));
        }
        return result;
    }

    static List<Potion> potions(Object player, boolean preview) throws Exception {
        List<Potion> result = new ArrayList<Potion>();
        Object[] types =
                (Object[])
                        Class.forName("net.minecraft.potion.Potion")
                                .getField("field_76425_a")
                                .get(null);
        for (Object effect :
                (Collection<?>)
                        call(player, new String[] {"func_70651_bq", "getActivePotionEffects"})) {
            int id =
                    ((Number) call(effect, new String[] {"func_76456_a", "getPotionID"}))
                            .intValue();
            if (id < 0 || id >= types.length || types[id] == null) continue;
            Object potion = types[id];
            String key = String.valueOf(call(potion, new String[] {"func_76393_a", "getName"}));
            String name =
                    String.valueOf(
                            invoke(
                                    Class.forName("net.minecraft.client.resources.I18n"),
                                    null,
                                    new String[] {"func_135052_a", "format"},
                                    key,
                                    new Object[0]));
            int level =
                    ((Number) call(effect, new String[] {"func_76458_c", "getAmplifier"}))
                                    .intValue()
                            + 1;
            if (level > 1) name += " " + amplifier(level);
            int duration =
                    ((Number) call(effect, new String[] {"func_76459_b", "getDuration"}))
                            .intValue();
            int icon =
                    Boolean.TRUE.equals(
                                    call(potion, new String[] {"func_76400_d", "hasStatusIcon"}))
                            ? ((Number)
                                            call(
                                                    potion,
                                                    new String[] {
                                                        "func_76392_e", "getStatusIconIndex"
                                                    }))
                                    .intValue()
                            : -1;
            result.add(new Potion(name, duration, icon));
        }
        Collections.sort(result, (a, b) -> a.name.compareTo(b.name));
        if (result.isEmpty() && preview) {
            result.add(new Potion("Velocidade II", 2400, 0));
            result.add(new Potion("Força", 900, 5));
        }
        return result;
    }

    static String amplifier(int level) {
        String[] roman = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 0 && level < roman.length ? roman[level] : Integer.toString(level);
    }

    static String duration(int ticks) {
        int seconds = Math.max(0, ticks / 20);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
