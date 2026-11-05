import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/** Screens in both languages, to check that every text fits. */
public class LangShot {
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        for (boolean en : new boolean[] { true, false }) {
            Lang.en = en;
            String p = dir + "/" + (en ? "en_" : "zh_");
            Game g = new Game(1.0);
            g.timer.stop();
            g.setSize(1280, 720);
            shot(g, p + "1_title.png");
            g.help = true;
            shot(g, p + "2_help.png");
            g.help = false;
            g.seed = 509;
            g.newGame();
            Random r = new Random(9);
            ActionType[] pool = { ActionType.ROLL, ActionType.DENSAUGEO, ActionType.STUPEFY, ActionType.EXPELLIARMUS, ActionType.DASH, ActionType.PROTEGO };
            for (int i = 0; i < 3; i++) g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
            g.hover = 12;
            shot(g, p + "3_plan_hover.png");
            g.hover = -1; g.pressCard = 13; g.pressT = 1;
            shot(g, p + "4_skill_info.png");
            g.pressCard = -1;
            while (g.state != Game.State.END) {
                if (g.state == Game.State.PLAN) {
                    for (int i = 0; i < 5; i++) g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
                    g.execute();
                } else g.runFrame(1 / 60.0);
            }
            g.endDelay = 0;
            shot(g, p + "5_end.png");
            g.startReplay();
            for (int i = 0; i < 400; i++) { g.replayFrame(1 / 60.0); g.updateFx(1 / 60.0); g.updateCamera(0); }
            shot(g, p + "6_replay.png");
        }
        System.exit(0);
    }

    static void shot(Game g, String path) throws Exception {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = img.createGraphics();
        g.paint(gg);
        gg.dispose();
        ImageIO.write(img, "png", new File(path));
    }
}
