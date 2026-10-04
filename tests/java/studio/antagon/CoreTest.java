package studio.antagon;

public final class CoreTest {
    private static class Parent {
        private float amount = 3f;
    }

    private static final class Target extends Parent {
        private String overloaded(String value) {
            return "string:" + value;
        }

        private String overloaded(Integer value) {
            return "integer:" + value;
        }

        private String overloaded(Runnable value) {
            value.run();
            return "task";
        }

        private static long numeric(long value) {
            return value + 1;
        }

        private String noArgument() {
            return "ok";
        }
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Target target = new Target();
        check(
                "string:test"
                        .equals(
                                Reflect.call(
                                        target, new String[] {"missing", "overloaded"}, "test")));
        check(
                "integer:42"
                        .equals(Reflect.call(target, new String[] {"missing", "overloaded"}, 42)));
        check(
                "string:again"
                        .equals(
                                Reflect.call(
                                        target, new String[] {"missing", "overloaded"}, "again")));
        check(
                "task"
                        .equals(
                                Reflect.call(
                                        target, new String[] {"overloaded"}, (Runnable) () -> {})));
        check(((Long) Reflect.invoke(Target.class, null, new String[] {"numeric"}, 4)) == 5L);
        check("ok".equals(Reflect.call(target, new String[] {"noArgument"})));
        check(Reflect.getF(target, "amount") == 3f);
        Reflect.setF(target, "amount", 7f);
        check(Reflect.getF(target, "amount") == 7f);
        for (int[] size :
                new int[][] {{320, 240}, {427, 240}, {640, 400}, {960, 540}, {1280, 720}}) {
            int height = MenuLayout.homeHeight(size[0], size[1]);
            float scale = MenuLayout.scale(size[0], size[1], 0);
            check(MenuLayout.WIDTH * scale <= size[0] - 23);
            check(height * scale <= size[1] - 23);
            float max = MenuLayout.maxScroll(21, height);
            float[] last = MenuLayout.card(20, 0, 0, max);
            check(last[1] + last[3] <= height - MenuLayout.FOOTER + .01f);
            check(MenuLayout.clampScroll(9999, 21, height) == max);
            check(MenuLayout.clampScroll(-1, 21, height) == 0);
        }
        check("ambos".equals(ModuleRegistry.defaultValue("cps.buttons")));
        check("on".equals(ModuleRegistry.defaultValue("cps.suffix")));
        check("7 | 4 CPS".equals(ModuleRegistry.cpsText(7, 4, "ambos", true)));
        check("7 | 4".equals(ModuleRegistry.cpsText(7, 4, "ambos", false)));
        check("7 CPS".equals(ModuleRegistry.cpsText(7, 4, "esquerdo", true)));
        check("7".equals(ModuleRegistry.cpsText(7, 4, "esquerdo", false)));
        check("4 CPS".equals(ModuleRegistry.cpsText(7, 4, "direito", true)));
        check("4".equals(ModuleRegistry.cpsText(7, 4, "direito", false)));
        check("0:00".equals(StatusData.duration(-20)));
        check("1:05".equals(StatusData.duration(1300)));
        check("II".equals(StatusData.amplifier(2)));
        check("11".equals(StatusData.amplifier(11)));
        System.out.println("Java OK: reflection, menu bounds and all CPS display modes.");
    }
}
