import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Screenshots of second stages: glass collapsing inward, Crucio's burst ring, Avada's follow-up. */
public class StageShot {
    public static void main(String[] args) throws Exception {
        Game g = new Game(1.0);
        g.timer.stop();
        g.setSize(1280, 720);
        Object[][] cases = { { World.Attack.GLASS, 230 }, { World.Attack.CRUCIO, 140 }, { World.Attack.AVADA, 104 } };
        for (Object[] c : cases) {
            World w = MechTest.quiet(5);
            w.p.x = 360; w.p.y = 330; w.b.x = 520; w.b.y = 300;
            MechTest.spawn(w, (World.Attack) c[0]);
            g.world = w; g.state = Game.State.RUN; g.clearFx();
            for (int i = 0; i < (Integer) c[1]; i++) w.step();
            g.updateCamera(0);
            BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D gg = img.createGraphics();
            g.paint(gg);
            gg.dispose();
            ImageIO.write(img, "png", new File(args[0] + "/stage_" + c[0] + ".png"));
        }
        System.exit(0);
    }
}
