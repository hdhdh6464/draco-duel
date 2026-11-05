import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Every kind of cancel blast side by side, a few frames in: java FxShot out.png. */
public class FxShot {
    public static void main(String[] args) throws Exception {
        Game g = new Game(1.0);
        g.timer.stop();
        g.setSize(1280, 720);
        g.seed = 77;
        g.newGame();
        World w = g.world;
        w.objs.clear();
        w.p.x = 300; w.p.y = 470; w.b.x = 560; w.b.y = 470;
        g.state = Game.State.RUN;
        g.snapCamera(430, 400);
        String[] kinds = { "SHARD", "M_BOLT", "AVADA", "CRUCIO", "REFLECT", "SERPENT", "FIRE_RING", "NAGINI", "INFERI", "CHIP" };
        for (int i = 0; i < kinds.length; i++) g.cancelFx(kinds[i], 260 + (i % 5) * 45, 360 + (i / 5) * 70);
        for (int i = 0; i < 5; i++) g.updateFx(1 / 60.0);
        g.banner = null;
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = img.createGraphics();
        g.paint(gg);
        gg.dispose();
        ImageIO.write(img, "png", new File(args[0]));
        System.exit(0);
    }
}
