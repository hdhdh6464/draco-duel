import java.util.ArrayList;
import java.util.Random;

/**
 * Plays many games through the real Game and reports what a player would see go wrong:
 * a hit with no red cross on the timeline, and a bullet time opening before the planned actions have played out.
 */
public class PlanDiag {
    public static void main(String[] args) {
        ActionType[] pool = { ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP, ActionType.APPARATE, ActionType.WAIT,
            ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.STUPEFY, ActionType.SERPENSORTIA,
            ActionType.KICK, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW, ActionType.PROTEGO };
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        int plans = 0, hits = 0, unmarked = 0, offTimeline = 0, early = 0, earlyAfterHit = 0, earlyOther = 0;
        for (int gi = 0; gi < games; gi++) {
            Random r = new Random(gi * 7 + 1);
            Game g = new Game(1.0);
            g.timer.stop();
            g.seed = 3000 + gi;
            g.newGame();
            int frames = 0;
            while (g.state != Game.State.END && frames < 60 * 240) {
                if (g.state != Game.State.PLAN) { g.runFrame(World.DT); frames++; continue; }
                g.lastMapWX = g.world.p.x + r.nextInt(300) - 150; g.lastMapWY = g.world.p.y + r.nextInt(300) - 150;
                // fill most of the window, the way a player does
                for (int i = 0; i < 12 && g.roomSteps() > 10; i++) {
                    int c = r.nextInt(10);
                    if (c < 3) g.command(g.itemFor(ActionType.RUN, r.nextInt(3) - 1, r.nextInt(3) - 1));
                    else if (c == 3) g.runTo(g.world.p.x + r.nextInt(200) - 100, g.world.p.y + r.nextInt(200) - 100);
                    else g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
                }
                ArrayList<int[]> marks = new ArrayList<>();
                for (Game.Mark m : g.hits) marks.add(new int[] { (int) Math.round(m.t * 60), m.t <= g.planWindow + 1e-6 ? 1 : 0 });
                int planned = (int) Math.round(g.planTotal() * 60);
                g.execute();
                plans++;
                int start = g.world.steps, s = 0, guard = 0;
                boolean hitInPlan = false;
                while (g.state == Game.State.RUN && guard++ < 6000) {
                    int before = g.world.steps;
                    g.runFrame(World.DT);
                    s = g.world.steps - start;
                    if (g.world.steps == before) continue;
                    for (World.Ev e : g.world.ev) {
                        if (e.type != World.EvType.HIT) continue;
                        hits++;
                        hitInPlan = true;
                        boolean found = false, shown = false;
                        for (int[] m : marks) if (Math.abs(m[0] - s) <= 1) { found = true; shown = m[1] == 1; }
                        if (!found) { unmarked++; if (unmarked <= 8) System.out.println("unmarked hit: game " + gi + " plan " + plans + " step " + s + " marks=" + marksStr(marks)); }
                        else if (!shown) { offTimeline++; if (offTimeline <= 8) System.out.println("cross off the timeline: game " + gi + " step " + s + " window=" + Math.round(g.planWindow * 60)); }
                    }
                }
                frames += s;
                if (g.state == Game.State.PLAN && s < planned) {
                    early++;
                    if (hitInPlan) earlyAfterHit++; else earlyOther++;
                    if (!hitInPlan && earlyOther <= 8) System.out.println("early bullet time without a hit: game " + gi + " after " + s + " of " + planned + " steps, reason=" + g.planReason);
                }
            }
        }
        System.out.printf("plans=%d hits=%d unmarked=%d crossOffTimeline=%d earlyBulletTime=%d (after a hit %d, other %d)%n",
                          plans, hits, unmarked, offTimeline, early, earlyAfterHit, earlyOther);
        System.exit(0);
    }

    static String marksStr(ArrayList<int[]> marks) {
        StringBuilder b = new StringBuilder("[");
        for (int[] m : marks) b.append(m[0]).append(m[1] == 1 ? "" : "(off)").append(' ');
        return b.append(']').toString();
    }
}
