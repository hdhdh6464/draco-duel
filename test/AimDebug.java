import java.util.Arrays;
public class AimDebug {
    public static void main(String[] a) {
        World w = MechTest.quiet(23);
        w.p.x = 240; w.p.y = 380; w.b.x = 300; w.b.y = 380;
        World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, 240, 260);
        de.b = 30; de.a = 5;
        w.p.stored[2] = true;
        w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.STUPEFY, true, 0, -1)));
        for (int i = 0; i < 40; i++) {
            w.step();
            for (World.Ev e : w.ev) System.out.println("step " + w.steps + " ev " + e.type + " " + e.text + " fizzle=" + w.lastFizzle);
            for (World.Obj o : w.objs) if (!o.hostile) System.out.printf("step %d bolt %s at %.1f,%.1f v=%.1f,%.1f e=%.0f%n", w.steps, o.kind, o.x, o.y, o.vx, o.vy, o.e);
            if (i % 10 == 0) System.out.printf("de at %.1f,%.1f a=%.2f dead=%b%n", de.x, de.y, de.a, de.dead);
        }
    }
}
