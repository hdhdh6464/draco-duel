import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class ThrowSheet {
    public static void main(String[] args) throws Exception {
        World w = MechTest.quiet(40);
        w.p.x = 300; w.p.y = 380; w.b.x = 316; w.b.y = 380;
        w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
        int[] at = { 6, 15, 20, 25, 30, 37, 42, 47, 52, 59, 64, 69, 74, 80 };
        int fw = 70, fh = 80, sc = 4;
        BufferedImage img = new BufferedImage(fw * 7, fh * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A2416)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int done = 0;
        for (int k = 0; k < at.length; k++) {
            while (done < at[k]) { w.step(); done++; }
            Graphics2D c = (Graphics2D) g.create();
            c.translate((k % 7) * fw + 35 - 300, (k / 7) * fh + 72 - 380);
            c.setClip(300 - 35, 380 - 72, fw, fh);
            if (w.b.y + w.b.z * 0 < w.p.y) { Sprites.voldemort(c, w.b, w.time, false, null, w.p.x >= w.b.x ? 1 : -1); Sprites.draco(c, w.p, w.time, null); }
            else { Sprites.draco(c, w.p, w.time, null); Sprites.voldemort(c, w.b, w.time, false, null, w.p.x >= w.b.x ? 1 : -1); }
            c.dispose();
        }
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * sc, img.getHeight() * sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = big.createGraphics();
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        b.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        b.setColor(Color.WHITE); b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        for (int k = 0; k < at.length; k++) b.drawString("step " + at[k], (k % 7) * fw * sc + 6, (k / 7) * fh * sc + 20);
        b.dispose();
        ImageIO.write(big, "png", new File(args[0]));
    }
}
