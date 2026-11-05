import java.util.ArrayList;
import java.util.Random;

/**
 * Through the real Game: every committed plan plays out exactly as previewed. The hits the preview
 * predicts are the hits that happen (and no others), and no bullet time opens before the plan is done.
 */
public class PlanFidelityTest {
    public static void main(String[] args) {
        ActionType[] pool = { ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP, ActionType.APPARATE, ActionType.WAIT,
            ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.STUPEFY, ActionType.SERPENSORTIA,
            ActionType.KICK, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW, ActionType.PROTEGO };
        int plans = 0, mismatched = 0, interrupted = 0, predictedHits = 0;
        for (int gi = 0; gi < 15; gi++) {
            Random r = new Random(gi);
            Game g = new Game(1.0);
            g.timer.stop();
            g.seed = 900 + gi;
            g.newGame();
            int frames = 0;
            while (g.state != Game.State.END && frames < 60 * 200) {
                if (g.state == Game.State.PLAN) {
                    g.lastMapWX = g.world.p.x + r.nextInt(300) - 150; g.lastMapWY = g.world.p.y + r.nextInt(300) - 150;
                    int n = 2 + r.nextInt(7);
                    for (int i = 0; i < n; i++) {
                        int c = r.nextInt(10);
                        if (c < 3) g.command(g.itemFor(ActionType.RUN, r.nextInt(3) - 1, r.nextInt(3) - 1));
                        else if (c == 3) g.runTo(g.world.p.x + r.nextInt(200) - 100, g.world.p.y + r.nextInt(200) - 100);
                        else g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
                    }
                    int planSteps = g.previewPlanEnd < 0 ? (int) Math.round(g.planTotal() * 60) : g.previewPlanEnd;
                    ArrayList<Integer> predicted = new ArrayList<>();
                    for (Game.Mark m : g.hits) { int s = (int) Math.round(m.t * 60); if (s <= planSteps) predicted.add(s); }
                    predictedHits += predicted.size();
                    g.execute();
                    plans++;
                    ArrayList<Integer> actual = new ArrayList<>();
                    int start = g.world.steps, s = 0, guard = 0;
                    boolean early = false;
                    // play the plan out; mash the movement keys, which must change nothing while it runs.
                    // Count world steps, not frames: hit-stop holds frames without stepping the world.
                    while (s < planSteps && g.state == Game.State.RUN && guard++ < 5000) {
                        g.kLeft = r.nextBoolean(); g.kUp = r.nextBoolean();
                        int before = g.world.steps;
                        g.runFrame(World.DT);
                        s = g.world.steps - start;
                        if (g.world.steps != before) for (World.Ev e : g.world.ev) if (e.type == World.EvType.HIT) actual.add(s);
                        if (g.state == Game.State.PLAN && s < planSteps) early = true;
                    }
                    g.kLeft = false; g.kUp = false;
                    if (early) interrupted++;
                    if (!predicted.equals(actual) && g.state != Game.State.END) {
                        mismatched++;
                        System.out.println("game " + gi + " plan " + plans + ": predicted " + predicted + " actual " + actual);
                    }
                    frames += s;
                } else {
                    g.runFrame(World.DT);
                    frames++;
                }
            }
        }
        System.out.printf("plans=%d predictedHits=%d mismatched=%d interruptedMidPlan=%d%n", plans, predictedHits, mismatched, interrupted);
        System.out.println(mismatched == 0 && interrupted == 0 ? "PLAN FIDELITY OK" : "PLAN FIDELITY FAILED");
        System.exit(0);
    }
}
