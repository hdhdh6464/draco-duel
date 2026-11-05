import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** The arena with Malfoy, Voldemort and his servants close together, at game scale and cropped 2x: java SceneShot dir. */
public class SceneShot {
    public static void main(String[] args) throws Exception {
        Game g = new Game(1.0);
        g.timer.stop();
        g.setSize(1280, 720);
        g.seed = 77;
        g.newGame();
        World w = g.world;
        w.objs.clear();
        w.p.x = 300; w.p.y = 420; w.p.face = 1;
        w.b.x = 370; w.b.y = 410;
        World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, 345, 460); de.stage = 1;
        World.Obj inf = MeleeTest.enemy(w, World.Kind.INFERI, 255, 445); inf.vx = 1;
        w.objs.add(de); w.objs.add(inf);
        String[] names = { "stand", "jab", "charge" };
        for (int k = 0; k < names.length; k++) {
            w.p.cur = null;
            if (k == 1) { w.p.cur = new World.PlanItem(ActionType.KICK, 1, 0, ActionType.KICK.steps, false, false, 0, 0); w.p.step = 11; }
            if (k == 2) { w.p.cur = new World.PlanItem(ActionType.STUPEFY, 1, 0, ActionType.STUPEFY.steps, false, false, 0, 0); w.p.step = 40; }
            g.state = Game.State.RUN;
            g.snapCamera(330, 420);
            BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D gg = img.createGraphics();
            g.paint(gg);
            gg.dispose();
            BufferedImage crop = img.getSubimage(340, 120, 600, 360);
            BufferedImage big = new BufferedImage(1200, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D bg = big.createGraphics();
            bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            bg.drawImage(crop, 0, 0, 1200, 720, null);
            bg.dispose();
            ImageIO.write(big, "png", new File(args[0] + "/scene_" + names[k] + ".png"));
        }
        System.exit(0);
    }
}
