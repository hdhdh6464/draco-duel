import java.util.Random;

/** Bullet times chain: the frame a plan finishes, the next bullet time is already open. */
public class ChainTest {
    public static void main(String[] args) {
        int idleFrames = 0, plans = 0, emptyLoops = 0;
        for (int gi = 0; gi < 8; gi++) {
            Random r = new Random(gi);
            Game g = new Game(1.0);
            g.timer.stop();
            g.seed = 700 + gi;
            g.newGame();
            int frames = 0;
            while (g.state != Game.State.END && frames < 60 * 150) {
                if (g.state == Game.State.PLAN) {
                    // sometimes commit nothing at all: time must still move on
                    if (r.nextInt(4) > 0)
                        for (int i = 0; i < 1 + r.nextInt(4); i++) g.command(g.itemFor(ActionType.RUN, r.nextInt(3) - 1, r.nextInt(3) - 1));
                    int before = g.world.steps;
                    g.execute();
                    plans++;
                    int f = 0;
                    while (g.state == Game.State.RUN && f++ < 2000) {
                        g.runFrame(World.DT);
                        if (g.state == Game.State.RUN && g.world.p.idle()) idleFrames++;
                    }
                    if (g.world.steps == before) emptyLoops++;
                    frames += f;
                } else { g.runFrame(World.DT); frames++; }
            }
        }
        MechTest.check("no idle real-time frames between plans", idleFrames == 0, "idleFrames=" + idleFrames + " over " + plans + " plans");
        MechTest.check("an empty plan still advances time", emptyLoops == 0, "stuckBulletTimes=" + emptyLoops);
        System.out.println(MechTest.fails == 0 ? "CHAIN OK" : MechTest.fails + " FAILED");
        System.exit(0);
    }
}
