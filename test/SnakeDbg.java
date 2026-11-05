import java.util.Arrays;
public class SnakeDbg {
    public static void main(String[] a) throws Exception {
        World w = MechTest.quiet(60);
        w.p.x = 300; w.p.y = 380; w.b.x = 600; w.b.y = 380;
        w.p.stored[3] = true;
        MechTest.spawn(w, World.Attack.NAGINI);
        for (World.Obj o : w.objs) System.out.printf("nagini lane from %.0f,%.0f dir %.2f,%.2f delay %.2f%n", o.x, o.y, o.vx, o.vy, o.delay);
        for (int i = 0; i < 2; i++) w.step();
        w.commit(Arrays.asList(World.PlanItem.release(ActionType.SERPENSORTIA)));
        for (int i = 0; i < 110; i++) {
            w.step();
            for (World.Ev e : w.ev) if (e.type != World.EvType.SCORE && e.type != World.EvType.KEY_POSE) System.out.println("step " + w.steps + " " + e.type + " " + e.text);
            if (i % 8 == 0) for (World.Obj o : w.objs) if (o.kind == World.Kind.P_SNAKE || o.kind == World.Kind.NAGINI)
                System.out.printf("step %d %s at %.0f,%.0f active=%b c=%.0f e=%.0f%n", w.steps, o.kind, o.x, o.y, o.active, o.c, o.e);
        }
    }
}
