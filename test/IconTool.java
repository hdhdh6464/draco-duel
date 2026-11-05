import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.File;
import javax.imageio.ImageIO;

/** Writes a multi-size .ico (PNG-compressed entries) from Sprites.icon, plus a preview PNG. */
public class IconTool {
    public static void main(String[] args) throws Exception {
        int[] sizes = { 256, 64, 48, 32, 16 };
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(Sprites.icon(sizes[i]), "png", b);
            pngs[i] = b.toByteArray();
        }
        ImageIO.write(Sprites.icon(256), "png", new File(args[1]));
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(args[0]))) {
            le16(out, 0); le16(out, 1); le16(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                out.writeByte(sizes[i] >= 256 ? 0 : sizes[i]);
                out.writeByte(0); out.writeByte(0);
                le16(out, 1); le16(out, 32);
                le32(out, pngs[i].length); le32(out, offset);
                offset += pngs[i].length;
            }
            for (byte[] p : pngs) out.write(p);
        }
        System.exit(0);
    }
    static void le16(DataOutputStream o, int v) throws Exception { o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF); }
    static void le32(DataOutputStream o, int v) throws Exception { le16(o, v & 0xFFFF); le16(o, (v >>> 16) & 0xFFFF); }
}
