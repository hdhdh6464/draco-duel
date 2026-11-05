import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Key frames of every attack and hit reaction, scaled up for inspection. */
public class AnimSheet {
    public static void main(String[] args) throws Exception {
        Object[][] poses = {
            { "idle", null, 0, false }, { "jab wind", ActionType.KICK, 3, false }, { "jab hit", ActionType.KICK, 10, false }, { "jab end", ActionType.KICK, 20, false },
            { "lunge", ActionType.FLURRY, 10, false }, { "lunge hit", ActionType.FLURRY, 27, false }, { "spin", ActionType.SPIN, 14, false },
            { "grab", ActionType.THROW, 6, false }, { "lift", ActionType.THROW, 18, false }, { "slam", ActionType.THROW, 32, false },
            { "charge", ActionType.STUPEFY, 30, false }, { "cock", ActionType.STUPEFY, 1, true }, { "release", ActionType.STUPEFY, 5, true },
            { "dens", ActionType.DENSAUGEO, 8, false }, { "protego", ActionType.PROTEGO, 30, false }, { "hurt", ActionType.HURT, 4, false }, { "stagger", ActionType.HURT, 18, false },
        };
        int fw = 46, fh = 50, sc = 5, cols = 9;
        int rows = (poses.length + cols - 1) / cols + 1;
        BufferedImage img = new BufferedImage(fw * cols, fh * rows, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A2416)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < poses.length; i++) {
            World.Player p = new World.Player();
            p.x = (i % cols) * fw + 18; p.y = (i / cols) * fh + 44; p.face = 1;
            ActionType a = (ActionType) poses[i][1];
            if (a != null) {
                p.cur = new World.PlanItem(a, -1, 0, a == ActionType.STUPEFY && (Boolean) poses[i][3] ? ActionType.RELEASE_STEPS : a.steps, (Boolean) poses[i][3], false, 0, 0);
                p.step = (Integer) poses[i][2];
            }
            Sprites.draco(g, p, 0.3, null);
            if (a == ActionType.PROTEGO) Sprites.shield(g, p, 0.3, null);
        }
        // Voldemort: idle, wind-up, cast, hit; then a Death Eater and an Inferius
        int ry = (rows - 1) * fh + 46;
        for (int k = 0; k < 4; k++) {
            World.Boss b = new World.Boss();
            b.x = k * fw + 22; b.y = ry; b.castTime = k == 1 ? 0.0 : -5;
            if (k == 3) { b.hitT = 0.3; b.hitDx = 1; b.hitDy = 0; }
            Sprites.voldemort(g, b, k == 1 ? 0.25 : 1.0, k == 1 || k == 2, null, -1);
        }
        World w = new World(1);
        World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, 4 * fw + 22, ry); de.stage = 1;
        Sprites.obj(g, w, de, 1.0);
        World.Obj inf = MeleeTest.enemy(w, World.Kind.INFERI, 5 * fw + 22, ry);
        Sprites.obj(g, w, inf, 1.0);
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * sc, img.getHeight() * sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = big.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        bg.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        bg.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18)); bg.setColor(Color.WHITE);
        for (int i = 0; i < poses.length; i++) bg.drawString((String) poses[i][0], (i % cols) * fw * sc + 6, (i / cols) * fh * sc + 22);
        String[] v = { "V idle", "V wind-up", "V cast", "V hit", "Death Eater", "Inferius" };
        for (int k = 0; k < v.length; k++) bg.drawString(v[k], k * fw * sc + 6, (rows - 1) * fh * sc + 22);
        bg.dispose();
        ImageIO.write(big, "png", new File(args[0]));
    }
}
