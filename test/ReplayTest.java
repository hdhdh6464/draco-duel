import java.util.Random;

/** Plays whole duels through the real Game input paths, then checks the replay reproduces them exactly. */
public class ReplayTest {
    public static void main(String[] args) throws Exception {
        int ok = 0, games = 12;
        ActionType[] pool = { ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP, ActionType.APPARATE, ActionType.WAIT,
            ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.STUPEFY, ActionType.SERPENSORTIA,
            ActionType.KICK, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW, ActionType.PROTEGO };
        for (int gi = 0; gi < games; gi++) {
            Random r = new Random(gi);
            Game g = new Game(1.0);
            g.timer.stop();
            g.seed = 500 + gi;
            g.newGame();
            int frames = 0;
            while (g.state != Game.State.END && frames < 60 * 240) {
                if (g.state == Game.State.PLAN) {
                    int n = 1 + r.nextInt(7);
                    for (int i = 0; i < n; i++) {
                        int c = r.nextInt(10);
                        if (c < 3) g.command(g.itemFor(ActionType.RUN, r.nextInt(3) - 1, r.nextInt(3) - 1));
                        else if (c == 3) g.runTo(g.world.p.x + r.nextInt(200) - 100, g.world.p.y + r.nextInt(200) - 100);
                        else g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
                    }
                    if (r.nextInt(6) == 0) g.undo();
                    if (r.nextInt(15) == 0) g.clearPlan();
                    if (r.nextInt(5) == 0) g.walkMode = !g.walkMode;
                    g.execute();
                } else {
                    if (r.nextInt(20) == 0) { g.kLeft = r.nextBoolean(); g.kRight = !g.kLeft && r.nextBoolean(); }
                    if (r.nextInt(20) == 0) { g.kUp = r.nextBoolean(); g.kDown = !g.kUp && r.nextBoolean(); }
                    g.runFrame(1 / 60.0 * (0.5 + r.nextDouble()));
                    frames++;
                }
            }
            World live = g.world;
            if (g.rec.endStep == 0) g.rec.endStep = live.steps;
            Replay.Player rp = new Replay.Player(g.rec);
            while (rp.advance()) { }
            World w = rp.world;
            boolean same = w.steps == live.steps && w.score == live.score && w.p.x == live.p.x && w.p.y == live.p.y
                           && w.p.hp == live.p.hp && w.b.hp == live.b.hp && w.over == live.over;
            // seeking back and forth lands on the same state as playing straight through
            rp.seek(live.steps / 2);
            World half = rp.world.copy();
            Replay.Player rp2 = new Replay.Player(g.rec);
            while (rp2.world.steps < live.steps / 2 && rp2.advance()) { }
            boolean seekOk = half.score == rp2.world.score && half.p.x == rp2.world.p.x && half.b.hp == rp2.world.b.hp;
            if (same && seekOk) ok++;
            System.out.printf("game %2d: %-5s steps=%d score=%d/%d hp=%.0f/%.0f bossHp=%.0f/%.0f inputs=%d %s%s%n", gi,
                    live.over ? (live.won ? "WIN" : "LOSS") : "time", live.steps, live.score, w.score, live.p.hp, w.p.hp,
                    live.b.hp, w.b.hp, g.rec.inputs.size(), same ? "replay matches" : "REPLAY DIFFERS", seekOk ? "" : " SEEK DIFFERS");
        }
        System.out.println(ok == games ? "REPLAY OK (" + ok + "/" + games + ")" : "REPLAY FAILED " + ok + "/" + games);
        System.exit(0);
    }
}
