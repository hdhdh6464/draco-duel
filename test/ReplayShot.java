import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

public class ReplayShot {
    public static void main(String[] args) throws Exception {
        Random r = new Random(9);
        Game g = new Game(1.0);
        g.timer.stop();
        g.setSize(1280, 720);
        g.seed = 509;
        g.newGame();
        ActionType[] pool = { ActionType.ROLL, ActionType.DENSAUGEO, ActionType.STUPEFY, ActionType.EXPELLIARMUS, ActionType.DASH, ActionType.PROTEGO };
        while (g.state != Game.State.END) {
            if (g.state == Game.State.PLAN) {
                for (int i = 0; i < 5; i++) g.command(g.itemFor(pool[r.nextInt(pool.length)], 0, 0));
                g.execute();
            } else g.runFrame(1 / 60.0);
        }
        g.endDelay = 0;
        shot(g, args[0] + "/r0_end.png");
        g.startReplay();
        for (int i = 0; i < 700; i++) { g.replayFrame(1 / 60.0); g.updateFx(1 / 60.0); g.updateCamera(0); }
        shot(g, args[0] + "/r1_replay.png");
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
