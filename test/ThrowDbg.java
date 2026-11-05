import java.util.Arrays;
public class ThrowDbg {
    public static void main(String[] a) {
        World w = MechTest.quiet(40);
        w.p.x = 300; w.p.y = 380; w.b.x = 316; w.b.y = 380;
        w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
        for (int i = 1; i <= 40; i++) { w.step(); if (i % 3 == 0) System.out.printf("step %d boss x=%.1f y=%.1f z=%.1f hidden=%b grabbed=%b face=%d%n", i, w.b.x, w.b.y, w.b.z, w.b.hidden, w.p.grabbed, w.p.face); }
    }
}
