import java.util.ArrayList;
import java.util.Random;

/** A director bot using the same look-ahead as the ghost preview, to check the duel is winnable. */
public class BotTest {
    public static void main(String[] args) {
        int wins = 0, games = Integer.parseInt(args.length > 0 ? args[0] : "6"), tries = Integer.parseInt(args.length > 1 ? args[1] : "250");
        for (int game = 0; game < games; game++) {
            World w = new World(1000 + game);
            Random r = new Random(game);
            int plans = 0;
            boolean replan = false;
            while (!replan) { w.step(); for (World.Ev e : w.ev) if (e.type == World.EvType.CAST_START) replan = true; }
            while (!w.over && w.time < 400) {
                if (replan) {
                    w.p.queue.clear();
                    double win = w.bulletWindow();
                    ArrayList<World.PlanItem> best = null;
                    double bestV = -1e18;
                    for (int k = 0; k < tries; k++) {
                        ArrayList<World.PlanItem> plan = Plans.random(r, Math.max(0.1, win - w.remainingPlan()), w.p);
                        World s = w.copy();
                        s.commit(plan, win);
                        int hits0 = s.hitsTaken, sc0 = s.score; double hp0 = s.b.hp;
                        int n = (int) ((win + 0.6) * 60);
                        for (int i = 0; i < n && !s.over; i++) s.step();
                        double v = (s.score - sc0) + (hp0 - s.b.hp) * 4 - (s.hitsTaken - hits0) * 400 - (s.over && !s.won ? 1e6 : 0);
                        if (v > bestV) { bestV = v; best = plan; }
                    }
                    w.commit(best, win);
                    plans++;
                    replan = false;
                }
                w.step();
                for (World.Ev e : w.ev) if (e.type == World.EvType.CAST_START) replan = true;
            }
            if (w.won) wins++;
            System.out.printf("game %d: %s time=%.1fs score=%d hp=%.0f bossHp=%.0f hits=%d maxCombo=%d interrupts=%d plans=%d%n",
                    game, w.won ? "WIN " : "LOSS", w.time, w.score, w.p.hp, w.b.hp, w.hitsTaken, w.maxCombo, w.interrupts, plans);
        }
        System.out.println("bot wins " + wins + "/" + games + " (tries per plan=" + tries + ")");
    }
}
