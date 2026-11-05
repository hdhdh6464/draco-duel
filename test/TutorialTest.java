import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** The solo edition's title and the tutorial walked through end to end; saves screenshots to args[0]. */
public class TutorialTest {
    static int fails = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + "  (" + info + ")");
        if (!ok) fails++;
    }

    public static void main(String[] args) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { try { body(args.length > 0 ? args[0] : null); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println(fails == 0 ? "TUTORIAL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void key(Game g, int k) {
        g.keyPressed(new KeyEvent(g, KeyEvent.KEY_PRESSED, 0, 0, k, KeyEvent.CHAR_UNDEFINED));
        g.keyReleased(new KeyEvent(g, KeyEvent.KEY_RELEASED, 0, 0, k, KeyEvent.CHAR_UNDEFINED));
        g.tutorialTick();
    }

    static void body(String out) throws Exception {
        Game.soloOnly = true;
        Game g = new Game(1.0);
        g.setSize(1280, 720);
        g.seed = 7;
        shot(g, out, "solo_title.png");
        key(g, KeyEvent.VK_2);
        check("2 on the title opens the tutorial in bullet time", g.tutorial != null && g.state == Game.State.PLAN && g.world.practice, "state=" + g.state);
        shot(g, out, "tut_1.png");
        int s0 = g.tutorial.step;
        key(g, KeyEvent.VK_ENTER);
        check("Enter reads on, and does not play the plan", g.tutorial.step == s0 + 1 && g.state == Game.State.PLAN, "step=" + g.tutorial.step);

        g.addItem(World.PlanItem.move(ActionType.RUN, 1, 0, 24)); g.tutorialTick();
        check("a move passes the moving lesson", g.tutorial.step == 2, "step=" + g.tutorial.step);
        key(g, KeyEvent.VK_ENTER);
        g.addItem(World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps)); g.tutorialTick();
        check("a roll passes the dodging lesson", g.tutorial.step == 4, "step=" + g.tutorial.step);
        g.addItem(World.PlanItem.aimed(ActionType.EXPELLIARMUS, false, 1, 0)); g.tutorialTick();
        check("charging alone is not enough", g.tutorial.step == 4, "step=" + g.tutorial.step);
        g.addItem(World.PlanItem.aimed(ActionType.EXPELLIARMUS, true, 1, 0)); g.tutorialTick();
        check("casting the stored spell passes the spell lesson", g.tutorial.step == 5, "step=" + g.tutorial.step + " draft=" + g.draft.size() + " room=" + g.roomSteps() + " toast=" + g.toast);
        shot(g, out, "tut_spell.png");
        key(g, KeyEvent.VK_ENTER);
        check("Enter plays the plan", g.state == Game.State.RUN && g.tutorial.step == 6, "state=" + g.state + " step=" + g.tutorial.step);

        // play on; hits stagger her but she never falls
        int hits0 = g.world.hitsTaken;
        for (int i = 0; i < 3000 && g.tutorial != null && g.tutorial.step < 7; i++) { g.runFrame(World.DT); g.tutorialTick(); }
        check("bullet time comes back", g.state == Game.State.PLAN && g.tutorial.step == 7, "state=" + g.state + " step=" + g.tutorial.step);
        key(g, KeyEvent.VK_ENTER);
        key(g, KeyEvent.VK_ENTER);
        check("the last task: play a plan", g.tutorial.step == 9 && g.state == Game.State.RUN, "step=" + g.tutorial.step + " state=" + g.state);
        for (int i = 0; i < 4000 && !g.world.over && g.world.hitsTaken == hits0; i++) {
            if (g.state == Game.State.PLAN) g.execute();
            g.runFrame(World.DT);
        }
        check("in the tutorial a hit does not end the duel", g.world.hitsTaken > hits0 && !g.world.over, "hits=" + g.world.hitsTaken + " over=" + g.world.over);
        for (int i = 0; i < 600 && g.state != Game.State.PLAN; i++) g.runFrame(World.DT);
        shot(g, out, "tut_end.png");
        key(g, KeyEvent.VK_ENTER);
        check("Enter after the last card starts a real duel", g.tutorial == null && g.state == Game.State.PLAN && !g.world.practice, "state=" + g.state);
        key(g, KeyEvent.VK_ESCAPE);
        key(g, KeyEvent.VK_3);
        check("the solo edition has no two-player modes", g.state == Game.State.TITLE && !g.pvp && !g.bossMode, "state=" + g.state);
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
