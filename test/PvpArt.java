import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Both duellists side by side, and the second one being thrown and lying down, scaled 6x. */
public class PvpArt {
    public static void main(String[] args) throws Exception {
        int fw = 60, fh = 56, sc = 6, n = 5;
        BufferedImage img = new BufferedImage(fw * n, fh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A2416)); g.fillRect(0, 0, fw * n, fh);
        double[] rots = { 0, 0, 120, 200, 270 };
        for (int i = 0; i < n; i++) {
            World.Player p = new World.Player();
            p.x = i * fw + fw / 2; p.y = fh - 6; p.side = i == 0 ? 0 : 1;
            if (i == 2 || i == 3) { p.carried = true; p.lift = 12; p.rot = rots[i]; }
            if (i == 4) { p.downT = 0.5; p.rot = 270; }
            Chars.alt = p.side == 1;
            Sprites.draco(g, p, 0, null);
            Chars.alt = false;
        }
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * sc, img.getHeight() * sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = big.createGraphics();
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        b.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        b.dispose();
        ImageIO.write(big, "png", new File(args[0]));
    }
}
