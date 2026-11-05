import java.util.ArrayList;
import java.util.Random;

public class SimTest {
    public static void main(String[] args) {
        Random r = new Random(1);
        int wins = 0, losses = 0, mismatches = 0;
        long steps = 0;
        java.util.EnumMap<World.Attack, Integer> seen = new java.util.EnumMap<>(World.Attack.class);
        long t0 = System.nanoTime();
        for (int game = 0; game < 300; game++) {
            World w = new World(game);
            int guard = 0;
            boolean cast = false;
            while (!w.over && guard++ < 60 * 400) {
                if (cast) {
                    cast = false;
                    double win = w.bulletWindow();
                    ArrayList<World.PlanItem> plan = Plans.random(r, Math.max(0.1, win - w.remainingPlan()), w.p);
                    World pre = w.copy();
                    pre.commit(plan, win); w.commit(plan, win);
                    for (int i = 0; i < 120; i++) { pre.step(); w.step(); steps++; count(w, seen); }
                    cast = false;
                    if (pre.score != w.score || pre.p.x != w.p.x || pre.p.y != w.p.y || pre.b.hp != w.b.hp || pre.objs.size() != w.objs.size()) mismatches++;
                } else { w.step(); steps++; count(w, seen); for (World.Ev e : w.ev) if (e.type == World.EvType.CAST_START) cast = true; }
            }
            if (w.won) wins++; else if (w.over) losses++;
            if (w.p.x < World.MIN_X || w.p.x > World.MAX_X || w.p.y < World.MIN_Y || w.p.y > World.MAX_Y || World.inObstacle(w.p.x, w.p.y, 4.9)) System.out.println("player out of bounds/obstacle in game " + game);
        }
        System.out.printf("random bot: wins=%d losses=%d steps=%d previewMismatches=%d  (%.1f us/step)%n", wins, losses, steps, mismatches, (System.nanoTime() - t0) / 1e3 / steps);
        System.out.println("attacks seen: " + seen);
    }
    static void count(World w, java.util.EnumMap<World.Attack, Integer> seen) {
        for (World.Ev e : w.ev) if (e.type == World.EvType.CAST_START) seen.merge(w.b.last, 1, Integer::sum);
    }
}
