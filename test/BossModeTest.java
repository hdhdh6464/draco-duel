import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Malfoy against a second player's Voldemort, driven through the game itself; saves screenshots to args[0]. */
public class BossModeTest {
    static int fails = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + "  (" + info + ")");
        if (!ok) fails++;
    }

    public static void main(String[] args) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { try { body(args.length > 0 ? args[0] : null); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println(fails == 0 ? "BOSS MODE OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static final int[] KEYS = { KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0, KeyEvent.VK_MINUS,
                                KeyEvent.VK_EQUALS, KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE };

    /** Runs real-time frames until the state leaves RUN or n steps have passed; returns the steps run. */
    static int run(Game g, int n) {
        int s0 = g.world.steps;
        for (int i = 0; i < n * 3 && g.state == Game.State.RUN && g.world.steps - s0 < n; i++) g.runFrame(World.DT);
        return g.world.steps - s0;
    }

    static World.Attack castable(World w) {
        for (World.Attack a : World.Attack.values()) if (w.canCast(a)) return a;
        return null;
    }

    static void body(String out) throws Exception {
        Game g = new Game(1.0);
        g.setSize(1280, 720);
        g.seed = 5;
        g.newBossGame();
        World w = g.world;
        w.p.invuln = 1e9; // she stands about in his spells: the test is about the turns, not about dodging
        g.rec.start = w.copy(); // the replay starts from the same (unhittable) Malfoy
        check("Voldemort's turn opens the duel, in real time", g.state == Game.State.RUN && w.bossHuman && w.bossTurn(), "state=" + g.state);

        double x0 = w.b.x;
        g.p2Key(KeyEvent.VK_LEFT, true);
        run(g, 30);
        g.p2Key(KeyEvent.VK_LEFT, false);
        check("arrows float him in his turn", x0 - w.b.x > 25, String.format("moved %.1f", x0 - w.b.x));
        shot(g, out, "p2_turn.png");

        World.Attack a = castable(w);
        g.p2Key(KEYS[a.ordinal()], true);
        run(g, 10);
        check("a spell key casts at once, and Malfoy gets bullet time", g.state == Game.State.PLAN && w.b.busy > 0 && w.b.last == a,
              "state=" + g.state + " spell=" + a + " busy=" + w.b.busy);
        check("his choice is cleared for her bullet time", w.bossOrder == null && w.bossDx == 0, "order=" + w.bossOrder);

        // Malfoy waits a second; arrows and a spell key while her plan plays do nothing to him yet
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 60, false, false, 0, 0));
        g.execute();
        double bx = w.b.x, by = w.b.y;
        g.p2Key(KeyEvent.VK_RIGHT, true);
        run(g, 30);
        check("he cannot move while her plan plays", w.b.x == bx && w.b.y == by, String.format("dx=%.2f", w.b.x - bx));
        g.p2Key(KeyEvent.VK_RIGHT, false);

        // play on through bullet times with empty plans; with nothing chosen his turn runs out and she plans again
        boolean hesitated = false;
        for (int round = 0; round < 12 && !w.over && !hesitated; round++) {
            if (g.state == Game.State.PLAN) g.execute();
            int s0 = w.steps;
            run(g, 600);
            if (g.state == Game.State.PLAN && (g.planReason.contains("遲疑") || g.planReason.contains("hesitates"))) {
                hesitated = true;
                check("an unused turn ends after " + Game.P2_TURN + " s", w.steps - s0 >= Game.P2_TURN * 60 - 2, "steps=" + (w.steps - s0));
            }
        }
        check("his turn passes unused: she plans again", hesitated, "reason=" + g.planReason);

        // a spell chosen while her plan plays goes off the moment his turn opens
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 40, false, false, 0, 0));
        g.execute();
        run(g, 5);
        World.Attack want = null;
        for (World.Attack x : World.Attack.values()) if (x != w.b.last && World.bandsOf(x)[World.rangeBand(Math.hypot(w.p.x - w.b.x, w.p.y - w.b.y))]) { want = x; break; }
        g.p2Key(KEYS[want.ordinal()], true);
        int planEnd = -1;
        for (int i = 0; i < 200 && g.state == Game.State.RUN; i++) { g.runFrame(World.DT); if (planEnd < 0 && !w.p.planActive()) planEnd = w.steps; }
        check("a spell picked in advance is cast as his turn opens", g.state == Game.State.PLAN && w.b.last == want && w.b.castTime <= planEnd * World.DT + 0.05,
              "spell=" + want + " last=" + w.b.last + " cast at " + w.b.castTime + " plan end " + planEnd * World.DT);

        // apparating
        g.execute();
        for (int r = 0; r < 20 && !(g.state == Game.State.RUN && w.bossTurn()) && !w.over; r++) {
            if (g.state == Game.State.PLAN) g.execute();
            for (int i = 0; i < 600 && g.state == Game.State.RUN && !w.bossTurn(); i++) g.runFrame(World.DT);
        }
        if (g.state == Game.State.RUN && w.bossTurn()) {
            double ax = w.b.x, ay = w.b.y;
            g.p2Key(KeyEvent.VK_UP, true);
            g.p2Key(KeyEvent.VK_ENTER, true);
            run(g, 2);
            g.p2Key(KeyEvent.VK_UP, false);
            double d = Math.hypot(w.b.x - ax, w.b.y - ay);
            check("Enter apparates him the way he holds", d > 60 && w.b.blinkCd > 0, String.format("moved %.1f cd=%.1f", d, w.b.blinkCd));
            shot(g, out, "p2_blink.png");
        } else check("reached another turn of his to apparate in", false, "state=" + g.state);

        // the recording plays the duel back exactly, his inputs included
        g.rec.endStep = w.steps;
        Replay.Player rp = new Replay.Player(g.rec);
        rp.seek(w.steps);
        World r = rp.world;
        check("the replay matches the duel", r.steps == w.steps && r.b.x == w.b.x && r.b.y == w.b.y && r.b.hp == w.b.hp && r.p.x == w.p.x && r.p.y == w.p.y
                  && r.objs.size() == w.objs.size() && r.b.castId == w.b.castId,
              String.format("steps %d/%d boss %.1f,%.1f / %.1f,%.1f casts %d/%d", r.steps, w.steps, r.b.x, r.b.y, w.b.x, w.b.y, r.b.castId, w.b.castId));
    }

    static void shot(Game g, String dir, String name) throws Exception {
        if (dir == null) return;
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = img.createGraphics();
        g.paint(gg);
        gg.dispose();
        ImageIO.write(img, "png", new File(dir, name));
    }
}
