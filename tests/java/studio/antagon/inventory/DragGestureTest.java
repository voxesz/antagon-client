package studio.antagon.inventory;

import java.util.List;
import java.util.Set;

public final class DragGestureTest {
    private static void check(boolean value) { if (!value) throw new AssertionError("Drag gesture regression"); }
    public static void main(String[] args) {
        DragGesture click = new DragGesture(9, Set.of(9,10,11));
        click.move(9); click.move(-1); click.move(0);
        check(click.drain().isEmpty() && click.release());
        DragGesture sweep = new DragGesture(9, Set.of(9,10,11));
        sweep.move(10);
        check(sweep.drain().equals(List.of(9,10)));
        sweep.move(9); sweep.move(10); sweep.move(0); sweep.move(-1);
        check(sweep.drain().isEmpty());
        sweep.move(11);
        check(sweep.drain().equals(List.of(11)));
        check(!sweep.release() && !sweep.holding());
        sweep.move(10); check(sweep.drain().isEmpty());
        try {
            new DragGesture(0, Set.of(9,10));
            throw new AssertionError("Protected slot accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("OK: normal click, held sweep, deduplication, protected slots and release.");
    }
}
