import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Character art. Each character is a small pixel rig: a pose (joint positions in pixels, keyframed per action and
 * played on twos like hand-drawn sprites) is painted into a scratch canvas with hue-shifted colour ramps, then
 * finished with a selective outline (every edge darkens the colour it borders) and a rim of light along the top.
 *
 * Local coordinates: the origin is between the feet on the ground, +x is the way the character faces, -y is up.
 * Light comes from the upper back (local top-left), as in a sprite authored facing right and then mirrored.
 */
final class Chars {
    private Chars() {}

    // ------------------------------------------------------------------ canvas

    private static final int SW = 150, SH = 150, CX = 75, CY = 110;
    private static final BufferedImage IMG = new BufferedImage(SW, SH, BufferedImage.TYPE_INT_ARGB);
    private static final int[] BUF = ((DataBufferInt) IMG.getRaster().getDataBuffer()).getData();
    private static final int[] TMP = new int[SW * SH];
    private static int face = 1;

    private static void begin(int f) { Arrays.fill(BUF, 0); face = f >= 0 ? 1 : -1; }

    static int rd(double v) { return (int) Math.floor(v + 0.5); }

    /** One pixel in local coordinates; translucent colours blend over what is there. */
    private static void p(int x, int y, int c) {
        int X = CX + (face > 0 ? x : -x - 1), Y = CY + y;
        if (X < 0 || Y < 0 || X >= SW || Y >= SH || (c >>> 24) == 0) return;
        int i = Y * SW + X;
        BUF[i] = (c >>> 24) == 255 ? c : over(BUF[i], c);
    }

    private static void line(double x0, double y0, double x1, double y1, int c) {
        int ax = rd(x0), ay = rd(y0), bx = rd(x1), by = rd(y1);
        int dx = Math.abs(bx - ax), dy = -Math.abs(by - ay), sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1, err = dx + dy;
        while (true) {
            p(ax, ay, c);
            if (ax == bx && ay == by) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; ax += sx; }
            if (e2 <= dx) { err += dx; ay += sy; }
        }
    }

    private static void rect(int x, int y, int w, int h, int c) { for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) p(x + i, y + j, c); }

    static int over(int d, int s) {
        int sa = s >>> 24, da = d >>> 24;
        if (da == 0) return s;
        int r = (((s >> 16) & 255) * sa + ((d >> 16) & 255) * (255 - sa)) / 255;
        int g = (((s >> 8) & 255) * sa + ((d >> 8) & 255) * (255 - sa)) / 255;
        int b = ((s & 255) * sa + (d & 255) * (255 - sa)) / 255;
        return (Math.max(sa, da) << 24) | (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    static int argb(Color c) { return c.getRGB(); }

    static int alpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

    /** A colour ramp from darkest to brightest. */
    static int[] ramp(int... rgb) { int[] r = new int[rgb.length]; for (int i = 0; i < rgb.length; i++) r[i] = 0xFF000000 | rgb[i]; return r; }

    private static int shade(int[] r, int i) { return r[Math.max(0, Math.min(r.length - 1, i))]; }

    /**
     * A limb from (x0,y0) to (x1,y1), w pixels thick, lit on the side facing the light: the back for an upright limb,
     * the top for a level one. {@code shift} darkens (negative) or brightens the whole limb, e.g. for the far side.
     */
    private static void limb(double x0, double y0, double x1, double y1, int w, int[] r, int shift) {
        int ax = rd(x0), ay = rd(y0), bx = rd(x1), by = rd(y1);
        boolean upright = Math.abs(by - ay) >= Math.abs(bx - ax);
        int dx = Math.abs(bx - ax), dy = -Math.abs(by - ay), sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1, err = dx + dy;
        int x = ax, y = ay;
        while (true) {
            for (int k = 0; k < w; k++) {
                double v = -(k - (w - 1) / 2.0);   // positive toward the light (local -x, or up)
                int idx = v > 0.25 ? 3 : v < -1.2 ? 0 : v < -0.25 ? 1 : 2;
                int off = k - (w - 1) / 2;
                if (upright) p(x + off, y, shade(r, idx + shift)); else p(x, y + off, shade(r, idx + shift));
            }
            if (x == bx && y == by) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    private static final int SEP = 0xFF050409;

    /**
     * The dark line that keeps a limb apart from the body behind it: a band one pixel wider than the limb on each
     * side, painted only over pixels already drawn, so the silhouette itself never grows.
     */
    private static void sep(double x0, double y0, double x1, double y1, int w) {
        int ax = rd(x0), ay = rd(y0), bx = rd(x1), by = rd(y1);
        boolean upright = Math.abs(by - ay) >= Math.abs(bx - ax);
        int dx = Math.abs(bx - ax), dy = -Math.abs(by - ay), sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1, err = dx + dy;
        int x = ax, y = ay;
        while (true) {
            for (int k = -1; k <= w; k++) {
                int off = k - (w - 1) / 2;
                int X = upright ? x + off : x, Y = upright ? y : y + off;
                int SX = CX + (face > 0 ? X : -X - 1), SY = CY + Y;
                if (SX >= 0 && SY >= 0 && SX < SW && SY < SH && (BUF[SY * SW + SX] >>> 24) > 0) BUF[SY * SW + SX] = SEP;
            }
            if (x == bx && y == by) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    /** Turns the whole canvas by {@code deg} (clockwise on screen) about the local point (0, pivotY). */
    private static void rotate(double deg, int pivotY) {
        double m = ((deg % 360) + 360) % 360;
        if (m < 0.5 || m > 359.5) return;
        double r = Math.toRadians(deg), c = Math.cos(r), sn = Math.sin(r);
        double px = CX, py = CY + pivotY;
        System.arraycopy(BUF, 0, TMP, 0, BUF.length);
        Arrays.fill(BUF, 0);
        for (int y = 0; y < SH; y++)
            for (int x = 0; x < SW; x++) {
                double dx = x + 0.5 - px, dy = y + 0.5 - py;
                int ix = (int) Math.floor(c * dx + sn * dy + px), iy = (int) Math.floor(-sn * dx + c * dy + py);
                if (ix >= 0 && iy >= 0 && ix < SW && iy < SH) BUF[y * SW + x] = TMP[iy * SW + ix];
            }
    }

    /** A filled ellipse shaded as a sphere lit from the upper back. */
    private static void ball(double cx, double cy, double rx, double ry, int[] r, int shift) {
        for (int y = (int) Math.floor(cy - ry); y <= Math.ceil(cy + ry); y++)
            for (int x = (int) Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
                double nx = (x + 0.5 - cx) / rx, ny = (y + 0.5 - cy) / ry, d = nx * nx + ny * ny;
                if (d > 1) continue;
                double b = -nx * 0.55 - ny * 0.75 + Math.sqrt(1 - d) * 0.35;
                int idx = b > 0.72 ? 4 : b > 0.32 ? 3 : b > -0.1 ? 2 : b > -0.5 ? 1 : 0;
                p(x, y, shade(r, idx + shift));
            }
    }

    /** Shade index across a span [xl, xr]: lit at the back edge, darkest at the front. */
    private static int across(int x, double xl, double xr) {
        double u = (x - xl) / Math.max(1, xr - xl);
        return u < 0.22 ? 3 : u < 0.6 ? 2 : u < 0.88 ? 1 : 0;
    }

    /** A hand-drawn grid: each character picks a colour from {@code pal} ('.' is empty). */
    private static void stamp(String[] rows, int x0, int y0, int[] pal) {
        for (int j = 0; j < rows.length; j++)
            for (int i = 0; i < rows[j].length(); i++) {
                char ch = rows[j].charAt(i);
                if (ch != '.' && pal[ch] != 0) p(x0 + i, y0 + j, pal[ch]);
            }
    }

    private static void fillPoly(int[] xs, int[] ys, int c) {
        Polygon pg = new Polygon(xs, ys, xs.length);
        java.awt.Rectangle bb = pg.getBounds();
        for (int y = bb.y; y <= bb.y + bb.height; y++)
            for (int x = bb.x; x <= bb.x + bb.width; x++) if (pg.contains(x + 0.5, y + 0.5)) p(x, y, c);
    }

    /** The Atrium's peacock-blue ceiling lights the tops and backs of everyone. */
    static final int RIM = 0xFFB4BEE8;

    /**
     * Finish the canvas: a rim of light along top and back edges, a selective outline, an optional hit flash,
     * then blit it with the local origin at (wx, wy). A tint turns the whole figure into a coloured ghost instead.
     */
    /** Two-player duel: the second wizard wears red where Malfoy wears Slytherin green. */
    static boolean alt;

    private static void finish(Graphics2D g, int wx, int wy, Color tint, double flash, int rim, double rimAmt) {
        if (alt && tint == null) recolour();
        if (tint != null) {
            int tc = tint.getRGB();
            for (int i = 0; i < BUF.length; i++) if ((BUF[i] >>> 24) > 0) BUF[i] = mix(BUF[i], tc, 0.62);
            g.drawImage(IMG, wx - CX, wy - CY, null);
            return;
        }
        System.arraycopy(BUF, 0, TMP, 0, BUF.length);
        int back = -face;   // the local back edge, in screen pixels
        for (int y = 1; y < SH - 1; y++)
            for (int x = 1; x < SW - 1; x++) {
                int i = y * SW + x, c = TMP[i], a = c >>> 24;
                if (a >= 140) {
                    boolean top = (TMP[i - SW] >>> 24) < 140, side = (TMP[i + back] >>> 24) < 140;
                    if (top || side) BUF[i] = mix(c, rim, top ? rimAmt : rimAmt * 0.55);
                } else if (a < 40) {
                    // the outline darkens whatever it borders; edges under the figure are darkest
                    int n, dark;
                    if ((TMP[i - SW] >>> 24) >= 140) { n = TMP[i - SW]; dark = 1; }
                    else if ((TMP[i - 1] >>> 24) >= 140) { n = TMP[i - 1]; dark = 0; }
                    else if ((TMP[i + 1] >>> 24) >= 140) { n = TMP[i + 1]; dark = 0; }
                    else if ((TMP[i + SW] >>> 24) >= 140) { n = TMP[i + SW]; dark = 0; }
                    else continue;
                    BUF[i] = mix(n | 0xFF000000, 0xFF0A0812, dark == 1 ? 0.86 : 0.74);
                }
            }
        if (flash > 0)
            for (int i = 0; i < BUF.length; i++) if ((BUF[i] >>> 24) > 0) BUF[i] = mix(BUF[i], 0xFFFFFFFF, flash);
        g.drawImage(IMG, wx - CX, wy - CY, null);
    }

    /** Swaps green hues for crimson (house colours of the second duellist). */
    private static void recolour() {
        float[] hsb = new float[3];
        for (int i = 0; i < BUF.length; i++) {
            int c = BUF[i];
            if ((c >>> 24) == 0) continue;
            Color.RGBtoHSB((c >> 16) & 255, (c >> 8) & 255, c & 255, hsb);
            if (hsb[0] > 0.22f && hsb[0] < 0.55f && hsb[1] > 0.18f)
                BUF[i] = (c & 0xFF000000) | (Color.HSBtoRGB(0.99f, Math.min(1f, hsb[1] * 1.1f), hsb[2] * 0.95f) & 0xFFFFFF);
            else if (hsb[0] > 0.10f && hsb[0] < 0.20f && hsb[1] > 0.12f && hsb[2] > 0.35f)
                // platinum blond becomes dark chestnut
                BUF[i] = (c & 0xFF000000) | (Color.HSBtoRGB(0.05f, 0.55f, 0.18f + hsb[2] * 0.32f) & 0xFFFFFF);
        }
    }

    // ------------------------------------------------------------------ poses

    static final int BX = 0, BY = 1, UP = 2, HIP = 3, LEAN = 4, HX = 5, HY = 6,
            NKX = 7, NKY = 8, NFX = 9, NFY = 10, FKX = 11, FKY = 12, FFX = 13, FFY = 14,
            NEX = 15, NEY = 16, NHX = 17, NHY = 18, FEX = 19, FEY = 20, FHX = 21, FHY = 22,
            WAND = 23, WLEN = 24, HAIR = 25, LIFT = 26, SKIRT = 27, CAPE = 28, EYES = 29, MOUTH = 30, BACK = 31, TURN = 32, TWIST = 33, N = 34;
    private static final String[] KEYS = { "b", "up", "hip", "lean", "h", "nk", "nf", "fk", "ff", "ne", "nh", "fe", "fh",
                                           "wand", "wlen", "hair", "lift", "skirt", "cape", "eyes", "mouth", "back", "turn", "tw" };
    private static final int[] SLOT = { BX, UP, HIP, LEAN, HX, NKX, NFX, FKX, FFX, NEX, NHX, FEX, FHX,
                                        WAND, WLEN, HAIR, LIFT, SKIRT, CAPE, EYES, MOUTH, BACK, TURN, TWIST };
    /** Eyes: open, shut, screwed up in pain, narrowed in concentration. */
    static final int OPEN = 0, SHUT = 1, PAIN = 2, FIERCE = 3;

    static final class Pose {
        final double[] v;
        Pose(double[] v) { this.v = v; }
    }

    /** A pose written as "key=value" pairs over a base, e.g. "lean=2 nh=11,-17 wand=0". */
    static Pose pose(Pose base, String spec) {
        double[] v = base.v.clone();
        for (String tok : spec.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            String[] kv = tok.split("=");
            int k = Arrays.asList(KEYS).indexOf(kv[0]);
            if (k < 0) throw new IllegalArgumentException("pose key " + kv[0]);
            String[] nums = kv[1].split(",");
            v[SLOT[k]] = Double.parseDouble(nums[0]);
            if (nums.length > 1) v[SLOT[k] + 1] = Double.parseDouble(nums[1]);
        }
        return new Pose(v);
    }

    static Pose lerp(Pose a, Pose b, double u) {
        double[] v = new double[N];
        for (int i = 0; i < N; i++) v[i] = a.v[i] + (b.v[i] - a.v[i]) * u;
        v[EYES] = (u < 0.5 ? a : b).v[EYES];
        v[MOUTH] = (u < 0.5 ? a : b).v[MOUTH];
        v[BACK] = (u < 0.5 ? a : b).v[BACK];
        v[TURN] = (u < 0.5 ? a : b).v[TURN];
        return new Pose(v);
    }

    /** Keyframes {step, pose, step, pose, ...}, eased between and played on twos. */
    static Pose at(Object[] keys, double step) {
        step = Math.floor(step / 2) * 2;
        if (step <= ((Number) keys[0]).doubleValue()) return (Pose) keys[1];
        for (int i = 0; i + 2 < keys.length; i += 2) {
            double s0 = ((Number) keys[i]).doubleValue(), s1 = ((Number) keys[i + 2]).doubleValue();
            if (step < s1) {
                double u = (step - s0) / Math.max(1e-9, s1 - s0);
                u = u * u * (3 - 2 * u);
                return lerp((Pose) keys[i + 1], (Pose) keys[i + 3], u);
            }
        }
        return (Pose) keys[keys.length - 1];
    }

    private static Pose set(Pose p, int slot, double v) { double[] c = p.v.clone(); c[slot] = v; return new Pose(c); }

    // ------------------------------------------------------------------ Malfoy: palette

    // platinum hair, fair skin and grey eyes; the Slytherin uniform: black robe lined in green, grey jumper and skirt
    static final int[] HAIR_C = ramp(0x75655A, 0xA8977C, 0xD6C9A2, 0xF1E9CA, 0xFFFCEE);
    static final int[] SKIN = ramp(0x9E6866, 0xD09C8C, 0xF0CBB6, 0xFDE2D1, 0xFFF2E8);
    static final int[] ROBE = ramp(0x07070C, 0x12121B, 0x1D1D2B, 0x2D2D44, 0x46466A);
    static final int[] LINING = ramp(0x08301E, 0x0F5230, 0x1C7A44, 0x2FA062, 0x66D294);
    static final int[] SHIRT = ramp(0x6E7290, 0xAEB2C6, 0xDEE0EA, 0xF8F8FF, 0xFFFFFF);
    static final int[] JUMPER = ramp(0x23242F, 0x343645, 0x474959, 0x5E6175, 0x7A7E95);
    static final int[] SKIRT_C = ramp(0x2A2C3C, 0x3C3F52, 0x525669, 0x6B7086, 0x8A90A6);
    static final int[] TIGHTS = ramp(0x15161E, 0x262836, 0x3A3D50, 0x52566C, 0x70758E);
    static final int[] SHOE = ramp(0x120C0A, 0x271A15, 0x3C2820, 0x5A3E32, 0x7E5E4C);
    static final int[] SILVER = ramp(0x545C6A, 0x8892A0, 0xB6BFCA, 0xDDE4EB, 0xFFFFFF);
    static final int[] WAND_C = ramp(0x341F12, 0x56361E, 0x7E552E, 0xA87D52, 0xD6B084);

    private static final int[] DRACO_PAL = new int[128];
    static {
        int[] P = DRACO_PAL;
        P['H'] = HAIR_C[4]; P['h'] = HAIR_C[3]; P['m'] = HAIR_C[2]; P['s'] = HAIR_C[1]; P['d'] = HAIR_C[0];
        P['K'] = SKIN[3]; P['k'] = SKIN[2]; P['q'] = SKIN[1]; P['Q'] = SKIN[0];
        P['w'] = 0xFFF6F7FB; P['e'] = 0xFF62798A; P['E'] = 0xFF242A36; P['b'] = 0xFF9C8A70;
        P['n'] = SKIN[1]; P['c'] = 0xFFF2A8A0; P['l'] = 0xFFC66872; P['o'] = 0xFF5A2228;
    }

    /** Her head, three-quarters toward the camera, facing right; row 11 is the chin. */
    private static final String[] DRACO_HEAD = {
        "....smmms...",
        "..smhHHhms..",
        ".smhHHhhhms.",
        "smhhmmhmmmms",
        "smmmsmmmsmms",
        "smmsKsmKKsms",
        "dmsqbKKbbKKs",
        "dmsqEKKwEKK.",
        "dmsqeKKweKn.",
        "ddsqkKKKkck.",
        ".dsQqkkklkq.",
        "..d.Qqqkq...",
    };

    /** The back of her head, for the frames where she has turned away mid-spin. */
    private static final String[] DRACO_BACK = {
        "....smmms...",
        "..smhHHhms..",
        ".smhHHhhhms.",
        "smhhmmhmmmms",
        "smmhmmmmmhms",
        "smmmmsmmmmms",
        "dmmsmmmsmmms",
        "dmsmmmsmmms.",
        "dmsmmmmsmms.",
        "ddsmmsmmsss.",
        ".dssmsmsss..",
        "..dssssd....",
    };

    private static String[] headFor(int eyes, int mouth) {
        String[] h = DRACO_HEAD.clone();
        switch (eyes) {
            case SHUT: h[7] = "dmsqKKKKKKK."; h[8] = "dmsqEKKEEKn."; break;
            case PAIN: h[6] = "dmsqKbKKKbKs"; h[7] = "dmsqEKKKEbK."; h[8] = "dmsqKKKEEKn."; break;
            case FIERCE: h[6] = "dmsqKKKKKKKs"; h[7] = "dmsqbKKbbEK."; break;
            default: break;
        }
        if (mouth == 1) { h[10] = ".dsQqkkkokq."; h[11] = "..d.Qqqoq..."; }
        return h;
    }

    // ------------------------------------------------------------------ Malfoy: poses

    // About 39 px tall, three and a quarter heads: feet at 0, knees -7, hips -14, shoulders -26, crown -38.
    // Arm coordinates are written for an upright torso; leaning, crouching and bobbing carry them along.
    static final Pose IDLE;
    static {
        double[] v = new double[N];
        v[HIP] = -14;
        v[NKX] = 1; v[NKY] = -7; v[NFX] = 1; v[NFY] = -1; v[FKX] = -2; v[FKY] = -7; v[FFX] = -2; v[FFY] = -1;
        v[NEX] = 4; v[NEY] = -19; v[NHX] = 5; v[NHY] = -14; v[FEX] = -4; v[FEY] = -19; v[FHX] = -4; v[FHY] = -14;
        v[WAND] = 62; v[WLEN] = 8;
        IDLE = new Pose(v);
    }
    static final Pose READY = pose(IDLE, "ne=5,-20 nh=8,-17 wand=25 fh=-3,-15");

    // Upper body and limbs, after the classic rules of animation and Slynyrd's punch and kick breakdowns:
    //  - the shoulders counter-rotate against the hips (tw), so the chest is never one rigid block;
    //  - power runs feet -> hips -> chest -> shoulder -> elbow -> hand, so the chest turns into a strike first;
    //  - the free arm swings the opposite way to balance the working one;
    //  - the chest leans further than the hips, the head drops a pixel on the hit, and the hit frame is held;
    //  - a recovery overshoots a pixel back before settling.

    /**
     * Six-frame sprint (contact, down, passing for each leg): the body pitched forward and the head leading, knees
     * driving high and the back heel kicking up, both arms pumping hard with the elbows bent opposite the legs; the
     * whole figure springs up off the ground on each passing frame.
     */
    static final Pose[] RUN = {
        pose(IDLE, "lean=3 h=1,1 tw=-1 nk=5,-7 nf=8,-1 fk=-4,-6 ff=-9,-4 ne=-1,-19 nh=-4,-16 wand=170 fe=3,-19 fh=6,-22 hair=5 cape=4 skirt=2"),
        pose(IDLE, "lean=3 h=1,1 tw=-0.5 up=1 hip=-13 nk=4,-5 nf=3,-1 fk=-3,-8 ff=-7,-9 ne=0,-19 nh=-3,-17 wand=160 fe=2,-19 fh=5,-21 hair=6 cape=5 skirt=1"),
        pose(IDLE, "lean=3 h=1,1 up=-2 nk=1,-7 nf=-1,-1 fk=5,-11 ff=3,-7 ne=3,-19 nh=4,-17 wand=100 fe=0,-19 fh=1,-18 hair=5 lift=-1 cape=4 skirt=2"),
        pose(IDLE, "lean=3 h=1,1 tw=1 fk=5,-7 ff=8,-1 nk=-4,-6 nf=-9,-4 ne=7,-19 nh=10,-22 wand=-40 fe=-3,-19 fh=-6,-16 hair=5 cape=4 skirt=2"),
        pose(IDLE, "lean=3 h=1,1 tw=0.5 up=1 hip=-13 fk=4,-5 ff=3,-1 nk=-3,-8 nf=-7,-9 ne=6,-19 nh=9,-21 wand=-30 fe=-2,-19 fh=-5,-17 hair=6 cape=5 skirt=1"),
        pose(IDLE, "lean=3 h=1,1 up=-2 fk=1,-7 ff=-1,-1 nk=5,-11 nf=3,-7 ne=4,-19 nh=6,-18 wand=40 fe=-2,-19 fh=-2,-17 hair=5 lift=-1 cape=4 skirt=2"),
    };
    /** Four-frame stroll. */
    static final Pose[] WALK = {
        pose(IDLE, "tw=-0.5 nk=2,-7 nf=3,-1 fk=-2,-7 ff=-3,-1 nh=3,-14 fh=-3,-14 hair=1 cape=1"),
        pose(IDLE, "up=-1 nk=1,-7 nf=0,-1 fk=0,-8 ff=-1,-3 hair=1"),
        pose(IDLE, "tw=0.5 nk=-2,-7 nf=-3,-1 fk=2,-7 ff=3,-1 nh=6,-14 fh=-5,-14 hair=1 cape=1"),
        pose(IDLE, "up=-1 nk=0,-8 nf=-1,-3 fk=1,-7 ff=0,-1 hair=1"),
    };

    // ---- spells: each has its own gesture, the whole body behind it

    // Densaugeo, wordless: a quick dart-thrower's flick from a cocked wrist, chest snapping round behind it
    static final Object[] DENS = {
        0, READY,
        3, pose(IDLE, "tw=-1 ne=4,-21 nh=3,-25 wand=-150 fe=-3,-20 fh=-1,-18 eyes=3"),
        6, pose(IDLE, "tw=1 lean=1 h=0,1 ne=7,-21 nh=11,-22 wand=-15 fe=-4,-20 fh=-6,-17 eyes=3"),
        8, pose(IDLE, "tw=2 lean=2 h=1,1 ne=8,-21 nh=12,-21 wand=0 wlen=9 fe=-5,-20 fh=-8,-17 nk=3,-7 nf=4,-1 hair=1 cape=1 eyes=3"),
        12, pose(IDLE, "tw=1 lean=1 ne=8,-21 nh=11,-20 wand=8 wlen=9 fe=-4,-20 fh=-7,-17 nk=3,-7 nf=4,-1 eyes=3"),
        16, pose(READY, "b=-1,0"),
        18, READY,
    };
    // Expelliarmus, released: an archer's coil -- wand drawn back to the far hip, the free hand pointing at the
    // target -- then the chest unwinds and the wand whips across into a lunging thrust
    static final Pose COIL_EXP = pose(IDLE, "hip=-13 lean=-1 tw=-2 ne=1,-20 nh=-3,-16 wand=160 fe=1,-22 fh=7,-23 nk=3,-6 nf=4,-1 fk=-3,-6 ff=-5,-1 eyes=3 hair=1");
    static final Object[] REL_EXP = {
        0, pose(COIL_EXP, "tw=-3"),
        3, pose(IDLE, "hip=-13 tw=1 lean=1 ne=6,-22 nh=9,-24 wand=-40 fe=-2,-21 fh=1,-20 nk=4,-6 nf=5,-1 fk=-3,-6 ff=-5,-1 eyes=3"),
        4, pose(IDLE, "hip=-12 lean=3 h=1,1 tw=3 ne=8,-22 nh=13,-22 wand=-5 wlen=9 fe=-5,-21 fh=-10,-19 nk=5,-7 nf=7,-1 fk=-4,-7 ff=-7,-1 hair=2 cape=2 skirt=1 eyes=3 mouth=1"),
        8, pose(IDLE, "hip=-12 lean=2 h=1,1 tw=2 ne=8,-22 nh=12,-21 wand=0 wlen=9 fe=-5,-21 fh=-9,-18 nk=5,-7 nf=7,-1 fk=-4,-7 ff=-7,-1 hair=1 cape=1 eyes=3"),
        12, READY,
    };
    // Stupefy, released: both hands raise the wand high overhead, back arched, then the whole body chops it down
    // and forward
    static final Pose COIL_STU = pose(IDLE, "hip=-13 lean=-2 h=-1,-1 ne=8,-27 nh=6,-35 wand=-100 fe=4,-28 fh=4,-34 nk=3,-6 nf=4,-1 fk=-3,-6 ff=-5,-1 eyes=3");
    static final Object[] REL_STU = {
        0, pose(COIL_STU, "lean=-3"),
        2, pose(IDLE, "hip=-13 lean=1 ne=6,-25 nh=8,-26 wand=-30 fe=3,-25 fh=6,-25 nk=4,-6 nf=5,-1 fk=-3,-6 ff=-5,-1 eyes=3"),
        4, pose(IDLE, "hip=-11 lean=5 h=2,2 tw=1 ne=7,-20 nh=11,-17 wand=20 wlen=9 fe=5,-20 fh=9,-17 nk=5,-6 nf=7,-1 fk=-4,-7 ff=-7,-1 hair=3 cape=2 skirt=1 eyes=3 mouth=1"),
        8, pose(IDLE, "hip=-11 lean=4 h=2,2 tw=1 ne=7,-20 nh=11,-17 wand=25 wlen=9 fe=5,-20 fh=8,-17 nk=5,-6 nf=7,-1 fk=-4,-7 ff=-7,-1 hair=2 cape=1 eyes=3"),
        12, READY,
    };
    // Serpensortia, released: a low scoop up and forward flings the snake out, and the wand points it at its spot
    static final Object[] REL_SNK = {
        0, pose(IDLE, "hip=-11 lean=3 h=1,1 ne=5,-18 nh=8,-13 wand=60 fe=2,-19 fh=6,-15 nk=3,-6 nf=5,-1 fk=-3,-6 ff=-5,-1 eyes=3"),
        4, pose(IDLE, "hip=-14 lean=-1 h=0,-1 tw=1 ne=7,-23 nh=11,-26 wand=-35 fe=-4,-21 fh=-8,-23 nk=3,-7 nf=4,-1 hair=1 lift=-1 cape=2 eyes=3"),
        8, pose(IDLE, "lean=1 tw=1 ne=7,-22 nh=11,-22 wand=15 fe=-4,-20 fh=-7,-18 nk=3,-7 nf=4,-1 eyes=3"),
        12, READY,
    };
    // Protego: a sweep up into a braced stance, wand upright before her and the free palm pushing out
    static final Pose PROTEGO_P = pose(IDLE, "hip=-13 lean=1 tw=1 ne=6,-23 nh=9,-27 wand=-85 fe=4,-21 fh=9,-21 nk=3,-6 nf=5,-1 fk=-4,-6 ff=-6,-1 eyes=3 hair=1 cape=1");
    static final Object[] PROTEGO = {
        0, READY,
        3, pose(IDLE, "tw=-1 ne=4,-22 nh=3,-27 wand=-120 fe=-3,-20 fh=-1,-17 eyes=3"),
        6, PROTEGO_P,
        60, PROTEGO_P,
    };

    // ---- melee

    // the flurry: a slight crouch behind a high guard, then four quick punches, near fist and far fist in turn,
    // the chest snapping round into each one and the other fist pulled back to the chin
    static final Pose GUARD = pose(IDLE, "hip=-12 lean=2 h=1,1 tw=-1 ne=4,-20 nh=6,-22 wand=125 fe=-2,-20 fh=2,-22 nk=4,-6 nf=6,-1 fk=-3,-6 ff=-6,-1 eyes=3");
    static final Pose PUNCH_N = pose(IDLE, "hip=-12 lean=3 h=1,1 tw=2 ne=8,-20 nh=12,-21 wand=120 fe=-3,-20 fh=1,-21 nk=4,-6 nf=6,-1 fk=-3,-6 ff=-6,-1 hair=1 eyes=3 mouth=1");
    static final Pose PUNCH_F = pose(IDLE, "hip=-12 lean=3 h=1,1 tw=-1 ne=3,-20 nh=5,-22 wand=125 fe=4,-20 fh=10,-21 nk=4,-6 nf=6,-1 fk=-3,-6 ff=-6,-1 hair=1 eyes=3 mouth=1");
    static final Object[] FLURRY;
    static {
        // a guard, then a punch on every hit step, near fist and far fist in turn, back to the guard in between
        java.util.ArrayList<Object> k = new java.util.ArrayList<>();
        k.add(0); k.add(READY); k.add(4); k.add(GUARD);
        int[] hits = ActionType.FLURRY_HITS;
        for (int i = 0; i < hits.length; i++) {
            k.add(hits[i]); k.add(i % 2 == 0 ? PUNCH_N : pose(PUNCH_F, i == hits.length - 1 ? "lean=4" : "lean=3"));
            k.add(hits[i] + 3); k.add(GUARD);
        }
        k.add(60); k.add(READY);
        FLURRY = k.toArray();
    }

    // the jump kick: arms swing back into the crouch and up into the spring, a hugged somersault, then the arms
    // fly wide for balance as the leg rises overhead and sweep down and back as the heel chops onto the head
    static final Object[] KICK_IN = {
        0, READY,
        4, pose(IDLE, "hip=-10 lean=2 tw=-1 nk=3,-5 nf=3,-1 fk=-3,-5 ff=-4,-1 ne=3,-19 nh=0,-15 wand=110 fe=-3,-19 fh=-7,-16 eyes=3"),
        7, pose(IDLE, "hip=-15 lean=-1 tw=1 nk=1,-8 nf=0,-2 fk=-2,-8 ff=-3,-2 ne=4,-25 nh=4,-31 wand=-90 fe=-3,-25 fh=-3,-31 hair=-2 lift=1 skirt=1 eyes=3"),
    };
    /** Knees hugged to the chest for the somersault. */
    static final Pose TUCK = pose(IDLE, "hip=-14 nk=5,-20 nf=2,-15 fk=2,-19 ff=-1,-14 ne=4,-21 nh=6,-18 wand=60 fe=-3,-21 fh=-1,-18 hair=1 eyes=3");
    static final Object[] KICK_OUT = {
        19, pose(IDLE, "hip=-14 lean=-3 nk=5,-23 nf=10,-31 fk=-2,-10 ff=-4,-6 ne=6,-23 nh=10,-25 wand=-20 fe=-6,-23 fh=-11,-24 hair=-1 lift=1 skirt=3 cape=2 eyes=3"),
        23, pose(IDLE, "hip=-14 lean=-3 nk=6,-23 nf=11,-30 fk=-2,-10 ff=-4,-6 ne=6,-23 nh=10,-25 wand=-20 fe=-6,-23 fh=-11,-24 hair=-1 lift=1 skirt=3 cape=2 eyes=3"),
        27, pose(IDLE, "hip=-14 lean=1 tw=1 nk=6,-18 nf=11,-21 fk=-2,-9 ff=-4,-5 ne=6,-21 nh=8,-16 wand=50 fe=-5,-21 fh=-9,-15 hair=1 skirt=3 cape=2 eyes=3"),
        32, pose(IDLE, "hip=-13 lean=3 h=1,2 tw=2 nk=6,-11 nf=10,-4 fk=-2,-8 ff=-4,-3 ne=6,-20 nh=7,-14 wand=70 fe=-6,-21 fh=-10,-17 hair=3 lift=-1 skirt=2 cape=2 eyes=3 mouth=1"),
        36, pose(IDLE, "hip=-10 lean=2 nk=4,-5 nf=5,-1 fk=-3,-5 ff=-4,-1 ne=5,-18 nh=8,-15 wand=30 fe=-3,-18 fh=0,-15 skirt=1 cape=1 eyes=3"),
        40, pose(READY, "b=-1,0"),
        42, READY,
    };

    // the spin kick, after the whirlwind kick of Little Fighter 2: the shoulders wind right back, the arms fly wide
    // as she turns away, each kick lands with both fists tucked to the chest and the body leaning hard off the leg
    static final Pose SPIN_KICK = pose(IDLE, "b=0,-3 hip=-14 lean=-4 h=-1,0 tw=2 nk=6,-15 nf=14,-15 fk=-1,-8 ff=-2,-3 ne=3,-21 nh=5,-19 wand=-60 fe=-3,-21 fh=1,-20 skirt=3 cape=4 hair=4 lift=-1 eyes=3");
    static final Pose SPIN_BACK = pose(IDLE, "back=1 b=0,-3 hip=-14 nk=3,-12 nf=1,-8 fk=0,-10 ff=-2,-6 ne=5,-22 nh=8,-22 wand=10 fe=-5,-22 fh=-8,-22 skirt=3 cape=3 hair=3");
    static final Object[] SPIN = {
        0, READY,
        4, pose(IDLE, "hip=-12 lean=-2 tw=-3 ne=1,-20 nh=-3,-17 wand=160 fe=-2,-20 fh=-6,-18 nk=3,-6 nf=3,-1 fk=-3,-6 ff=-4,-1 cape=-1 eyes=3"),
        7, pose(SPIN_BACK, "b=0,-2 nk=3,-12 nf=2,-8 fk=-1,-7 ff=-1,-1 ne=6,-22 nh=10,-23 fe=-6,-22 fh=-10,-23 wand=-20 cape=2"),
        10, SPIN_KICK,
        13, SPIN_KICK,
        15, SPIN_BACK,
        18, pose(SPIN_KICK, "turn=1"),
        21, pose(SPIN_KICK, "turn=1"),
        24, pose(SPIN_BACK, "b=0,-1 nk=2,-8 nf=2,-2 fk=-1,-7 ff=-1,-1 ne=4,-20 nh=6,-17 fe=-4,-20 fh=-6,-17 skirt=2"),
        27, pose(IDLE, "hip=-11 lean=1 tw=1 nk=3,-5 nf=4,-1 fk=-3,-5 ff=-4,-1 ne=5,-19 nh=8,-17 wand=20 fe=-4,-19 fh=-7,-16 skirt=1 cape=1 eyes=3"),
        33, pose(READY, "b=-1,0"),
        36, READY,
    };

    // the shoulder throw: a hunched grip, the chest turning its back into him to load him, arms locked straight
    // overhead as he passes over, then a deep bend with the chest driving down into the slam
    static final Pose GRAB = pose(IDLE, "hip=-12 lean=4 h=1,1 tw=1 ne=6,-20 nh=10,-19 wand=10 fe=5,-19 fh=9,-18 nk=3,-6 nf=4,-1 fk=-3,-6 ff=-5,-1 hair=1 eyes=3");
    static final Pose LOAD = pose(IDLE, "hip=-12 lean=-1 tw=-3 ne=1,-27 nh=-3,-30 wand=-120 fe=-2,-26 fh=-5,-29 nk=3,-6 nf=4,-1 fk=-3,-6 ff=-5,-1 eyes=3");
    static final Pose OVER = pose(IDLE, "hip=-12 lean=-1 ne=3,-29 nh=3,-35 wand=-90 fe=0,-29 fh=0,-35 nk=4,-6 nf=5,-1 fk=-4,-6 ff=-6,-1 hair=-2 eyes=3 mouth=1");
    static final Pose SLAM = pose(IDLE, "hip=-9 lean=7 h=3,3 tw=2 ne=8,-19 nh=12,-12 wand=60 fe=7,-19 fh=11,-12 nk=5,-5 nf=6,-1 fk=-4,-6 ff=-7,-1 hair=4 cape=3 skirt=1 eyes=3 mouth=1");
    static final Object[] SHOULDER = { 0, LOAD, 5, pose(LOAD, "hip=-11 lean=1 tw=-2"), 12, OVER, 19, SLAM, 22, SLAM };

    // hit: the chest is knocked back before the hips, the head snaps back, the arms fly; then a crumple and recovery
    static final Object[] HURT = {
        0, pose(IDLE, "lean=-4 h=-2,-1 tw=-1 ne=5,-24 nh=8,-29 wand=-60 fe=-6,-23 fh=-11,-26 nk=2,-7 nf=3,-1 fk=-3,-7 ff=-6,-1 hair=-3 cape=-2 skirt=1 eyes=2 mouth=1"),
        8, pose(IDLE, "lean=-3 h=-1,0 tw=-1 ne=5,-23 nh=7,-27 wand=-40 fe=-6,-22 fh=-10,-24 nk=2,-7 nf=3,-1 fk=-3,-7 ff=-6,-1 hair=-1 cape=-1 eyes=2 mouth=1"),
        14, pose(IDLE, "hip=-12 lean=2 h=1,2 ne=4,-18 nh=4,-14 wand=80 fe=-3,-18 fh=-2,-14 nk=2,-5 nf=3,-1 fk=-3,-5 ff=-4,-1 eyes=2"),
        27, IDLE,
    };
    static final Pose CROUCH = pose(IDLE, "hip=-10 lean=2 nk=3,-5 nf=3,-1 fk=-2,-5 ff=-3,-1 ne=4,-18 nh=6,-14 wand=70 fe=-2,-18 fh=0,-14 hair=2 cape=1 eyes=3");
    static final Object[] ROLL_IN = { 0, READY, 3, CROUCH };
    static final Object[] ROLL_OUT = { 25, pose(CROUCH, "hair=-1 cape=-1 nh=7,-15 wand=40"), 30, READY };
    static final Pose DASH_P = pose(IDLE, "lean=3 up=1 tw=1 nk=5,-6 nf=8,-2 fk=-5,-6 ff=-8,-2 ne=5,-21 nh=8,-17 wand=20 fe=-3,-21 fh=-8,-19 hair=5 cape=5 skirt=2 eyes=3");
    static final Object[] DASH = {
        0, pose(IDLE, "hip=-13 lean=-1 tw=-1 nk=2,-6 nf=2,-1 fk=-2,-6 ff=-3,-1 eyes=3"),
        2, DASH_P,
        11, DASH_P,
        15, pose(IDLE, "hip=-13 lean=-1 nk=3,-6 nf=4,-1 fk=-2,-7 ff=-2,-1 ne=5,-20 nh=8,-18 wand=20 skirt=2 cape=-1 hair=-1 eyes=3"),
    };
    static final Object[] FLIP_IN = {
        0, READY,
        3, pose(CROUCH, "lean=1 fe=-3,-18 fh=-5,-14"),
        6, pose(IDLE, "hip=-15 lean=-2 nk=1,-8 nf=1,-1 fk=-2,-8 ff=-3,-1 ne=3,-25 nh=3,-31 wand=-90 fe=-3,-25 fh=-4,-31 hair=-2 lift=1 skirt=1 eyes=3"),
    };
    static final Object[] FLIP_OUT = {
        28, pose(IDLE, "hip=-15 lean=1 nk=2,-8 nf=3,-1 fk=-2,-8 ff=-2,-1 ne=6,-22 nh=10,-23 wand=-20 fe=-6,-22 fh=-10,-23 hair=2 lift=-2 skirt=2 cape=2 eyes=3"),
        32, pose(CROUCH, "lean=1 ne=5,-18 nh=9,-17 wand=30 fe=-4,-18 fh=-8,-16 hair=1 skirt=1"),
        36, READY,
    };

    /** Charging any spell: the wand held up high while the power gathers, hair and robe stirring around her. */
    static final Pose CHARGE = pose(IDLE, "hip=-13 ne=4,-26 nh=5,-31 wand=-80 fe=-5,-21 fh=-9,-19 nk=2,-6 nf=3,-1 fk=-3,-6 ff=-4,-1 eyes=3");

    static Pose chargePose(ActionType a, double pr, double t) {
        Pose q = set(CHARGE, LIFT, -1 - pr * 2.5);
        q = set(q, HAIR, Math.sin(t * 9) * 0.8 - pr);
        return set(q, CAPE, 1 + Math.sin(t * 12) * (0.6 + pr));
    }

    /** Steps into a shoulder throw at which the last throw has landed and she straightens up. */
    static final int THROW_DONE = ActionType.THROW.hitStep() + ActionType.SLAM_STEPS * ActionType.SLAMS;

    /** Her pose at a given step of the current action. */
    static Pose dracoPose(World.Player p, ActionType a, int s, double t) {
        boolean free = a == null && p.freeAnim > 0;
        if (free || a == ActionType.RUN) {
            boolean walking = free ? p.walk : p.cur.alt;
            int count = free ? p.freeAnim : s;
            return walking ? WALK[(count / 9) % 4] : RUN[(count / 4) % 6];
        }
        if (a == null || a == ActionType.WAIT || p.fizzled) {
            // breathing: a long hold, then a quick drop; a blink every few seconds
            Pose q = a == ActionType.WAIT || p.fizzled ? READY : IDLE;
            double ph = t % 2.6;
            q = set(q, UP, ph < 1.5 ? 0 : 1);
            q = set(q, HAIR, ph < 1.5 ? 0 : -0.6);
            if (t % 3.7 < 0.13) q = set(q, EYES, SHUT);
            return q;
        }
        switch (a) {
            case KICK:
                if (s < 9) return at(KICK_IN, s);
                if (s < 15) return TUCK;
                if (s < 19) return lerp(TUCK, (Pose) KICK_OUT[1], (s - 15) / 4.0);
                return at(KICK_OUT, s);
            case FLURRY: {
                // shuffling in: the body bobs a pixel with each punch
                Pose q = at(FLURRY, s);
                return s >= 4 && s < 56 ? set(q, UP, (s / 6) % 2) : q;
            }
            case SPIN: return at(SPIN, s);
            case THROW: {
                if (s < 12) return lerp(READY, GRAB, Math.min(1, s / 8.0));
                if (s >= THROW_DONE) return at(new Object[] { THROW_DONE, SLAM, THROW_DONE + 6, READY }, s);
                if (!p.grabbed) return at(new Object[] { 12, GRAB, 20, pose(GRAB, "lean=3 hip=-13 eyes=2"), 40, READY }, s);
                return at(SHOULDER, (s - 13) % ActionType.SLAM_STEPS);
            }
            case HURT: return at(HURT, s);
            case PROTEGO: {
                // the shield's wind blows hair and robe back; she leans into it
                Pose q = at(PROTEGO, s);
                if (s >= 6) q = set(q, LEAN, 1 + Math.sin(t * 6) * 0.5);
                return set(set(q, CAPE, 1 + Math.sin(t * 14) * 1.2), HAIR, 1 + Math.sin(t * 11 + 1) * 0.8);
            }
            case DENSAUGEO: return at(DENS, s);
            case ROLL: return s < 3 ? at(ROLL_IN, s) : at(ROLL_OUT, s);
            case DASH: return at(DASH, s);
            case BACKFLIP: return s < 7 ? at(FLIP_IN, s) : at(FLIP_OUT, s);
            case APPARATE: return pose(READY, "hip=-13 lean=-1 ne=3,-21 nh=2,-17 fe=-3,-21 fh=-2,-17 eyes=1");
            default:
                if (a.charged() && !p.cur.alt) return chargePose(a, p.progress(), t);
                if (a.isSpell()) return at(a == ActionType.EXPELLIARMUS ? REL_EXP : a == ActionType.STUPEFY ? REL_STU : REL_SNK, s);
                return READY;
        }
    }

    /** True while a roll, backflip or jump kick is a tumbling ball rather than a standing figure. */
    private static boolean tumbling(ActionType a, int s) {
        return (a == ActionType.ROLL && s >= 3 && s < 25) || (a == ActionType.BACKFLIP && s >= 7 && s < 28);
    }

    // ------------------------------------------------------------------ Malfoy: drawing

    static void draco(Graphics2D g, World.Player p, double t, Color tint) {
        ActionType a = p.fizzled ? ActionType.WAIT : p.act();
        // thrown over a shoulder or lying on the floor: limp in the hurt pose, turned with the throw
        boolean flung = p.carried || p.downT > 0;
        if (flung) a = ActionType.HURT;
        int ox = rd(p.x), oy = rd(p.y - p.z()) + (flung ? rd(10 * Math.abs(Math.sin(Math.toRadians(p.rot)))) : 0);
        if (p.vanished()) { apparition(g, ox, oy, p.step, t, tint); return; }

        if (tint == null && a != null && p.dodgeInvuln() && a.isDodge()) {
            // invincibility frames: pale afterimages trailing the dodge
            java.awt.Composite old = g.getComposite();
            for (int i = 2; i >= 1; i--) {
                g.setComposite(Sprites.alphaComp(0.2f * (3 - i) / 2f));
                World.Player gh = p.copy();
                gh.x -= p.mdx * 7 * i; gh.y -= p.mdy * 7 * i;
                gh.invuln = 0; gh.hurtFlash = 0; gh.step = Math.max(0, p.step - 3 * i);
                draco(g, gh, t, new Color(0xE8F6FF));
            }
            g.setComposite(old);
        }

        int s = flung ? 10 : p.step;
        int f = p.face;
        // in a shoulder throw she faces the way he is about to be thrown, with him loaded on her back
        if (a == ActionType.THROW && s > 12 && (p.grabbed || s >= THROW_DONE) && Math.abs(p.gx1 - p.gx0) > 1) f = p.gx1 > p.gx0 ? 1 : -1;
        Pose q0 = a != null && tumbling(a, s) ? null : dracoPose(p, a, s, t);
        if (q0 != null && q0.v[TURN] > 0.5) f = -f;
        begin(f);
        if (q0 == null) {
            double pr = a == ActionType.ROLL ? (s - 3) / 22.0 : -(s - 7) / 21.0;
            boolean ends = a == ActionType.ROLL ? (s < 5 || s > 22) : (s < 9 || s > 25);
            paintBall(pr, ends);
        } else {
            Pose q = q0;
            // motion smears: a wand tip or a kicking foot that moved far since two steps ago leaves a sharp trail
            if (a != null && s >= 2 && !(a.charged() && !p.cur.alt) && a != ActionType.RUN) {
                Pose q2 = dracoPose(p, a, s - 2, t), q1 = dracoPose(p, a, s - 1, t);
                int c = a.isSpell() ? mix(Sprites.spellColor(a).getRGB(), 0xFFFFFFFF, 0.5) : 0xFFF4F4FF;
                if (a != ActionType.FLURRY) smear(wandTip(q2), wandTip(q1), wandTip(q), c);
                if (a == ActionType.KICK || a == ActionType.SPIN) smear(foot(q2), foot(q1), foot(q), 0xFFFFF4D8);
                if (a == ActionType.FLURRY) {
                    // each punch leaves a streak off the fist
                    smear(fist(q2, true), fist(q1, true), fist(q, true), 0xFFFFF4D8);
                    smear(fist(q2, false), fist(q1, false), fist(q, false), 0xFFFFF4D8);
                }
            }
            paintDraco(q, t, (int) (t * 6));
            if (flung) rotate(p.rot, -18);
            if (a == ActionType.KICK && s >= 9 && s < 19) {
                // the somersault: half a turn hugging her knees, the other half unfolding into the kick
                double rot = s < 15 ? 180 * (s - 9) / 6.0 : 180 + 180 * (s - 15) / 4.0;
                rotate(rot * f, -18);
            }
            if (a != null && a.charged() && !p.cur.alt) {
                // the charge gathers at the wand tip
                double[] tip = wandTip(q);
                int c = Sprites.spellColor(a).getRGB();
                int r = 1 + (int) (p.progress() * 3);
                for (int y = -r; y <= r; y++) for (int x = -r; x <= r; x++)
                    if (x * x + y * y <= r * r) p(rd(tip[0]) + x, rd(tip[1]) + y, alpha(c, 120 + (int) (p.progress() * 100)));
                p(rd(tip[0]), rd(tip[1]), 0xFFFFFFFF);
            }
        }
        double flash = p.hurtFlash > 0 && ((int) (p.hurtFlash * 30)) % 2 == 0 ? 0.8 : 0;
        Color tt = tint;
        if (tt == null && p.invuln > 0 && ((int) (t * 20)) % 2 == 0) tt = new Color(0xFFB0B0);
        int rim = RIM;
        double rimAmt = 0.22;
        if (a != null && a.charged() && !p.cur.alt) { rim = Sprites.spellColor(a).getRGB(); rimAmt = 0.12 + 0.14 * p.progress(); }
        finish(g, ox, oy, tt, flash, rim, rimAmt);
    }

    /** A thin crescent along the path a point took over the last two steps, brightest at the leading end. */
    private static void smear(double[] t0, double[] m, double[] t1, int c) {
        if (Math.hypot(t1[0] - t0[0], t1[1] - t0[1]) <= 5) return;
        line(t0[0], t0[1], m[0], m[1], alpha(c, 90));
        line(m[0], m[1], t1[0], t1[1], alpha(c, 200));
        line(m[0], m[1] + 1, t1[0], t1[1] + 1, alpha(c, 110));
    }

    static double[] hand(Pose q) {
        double up = q.v[HIP] + 14 + q.v[BY] + q.v[UP], lean = q.v[LEAN];
        return new double[] { q.v[NHX] + lean + q.v[BX], q.v[NHY] + up };
    }

    /** The flurry's blur of fists, drawn over everyone (so they pile into whoever she is hitting). */
    static void barrageOverlay(Graphics2D g, World.Player p, double t) {
        if (!p.is(ActionType.FLURRY) || p.step < ActionType.FLURRY_RUSH_FROM || p.step >= ActionType.FLURRY_RUSH_TO) return;
        Pose q = dracoPose(p, ActionType.FLURRY, p.step, t);
        begin(p.face);
        barrage(q, p.step);
        finish(g, rd(p.x), rd(p.y - p.z()), null, 0, RIM, 0.1);
    }

    /**
     * The rush, in the manner of a certain Stand: a blur of fists hammering out in front of her all at once, each a
     * sleeve and a fist trailing a speed streak, the furthest ones faded. A new spread every step.
     */
    private static void barrage(Pose q, int s) {
        double up = q.v[HIP] + 14 + q.v[BY] + q.v[UP], lean = q.v[LEAN];
        double sx = 3 + lean + q.v[BX], sy = -25 + up;   // the near shoulder
        for (int k = 0; k < 18; k++) {
            double r1 = hash(s, k, 1), r2 = hash(s, k, 2), r3 = hash(s, k, 3);
            double tx = sx + 6 + r1 * 13, ty = sy + 2 + (r2 - 0.5) * 19;
            int a = 170 + (int) (r3 * 85);
            // the sleeve (lit, so it reads on the dark floor), then the fist, then a streak of speed behind it
            line(sx - 1, sy + 1, tx - 1, ty, alpha(ROBE[4], a));
            line(sx - 1, sy + 2, tx - 1, ty + 1, alpha(ROBE[3], a));
            p(rd(tx - 1), rd(ty), alpha(LINING[2], a));
            rect(rd(tx), rd(ty) - 1, 3, 3, alpha(SKIN[3], a));
            p(rd(tx) + 2, rd(ty) - 1, alpha(SKIN[4], a)); p(rd(tx) + 2, rd(ty) + 1, alpha(SKIN[1], a));
            line(tx - 9 - r3 * 6, ty, tx - 2, ty, alpha(0xFFFFFFFF, a / 3));
            line(tx - 6 - r3 * 4, ty + 1, tx - 2, ty + 1, alpha(0xFFFFFFFF, a / 5));
        }
        // the one fist landing right now, big and close
        double r = hash(s, 99, 4);
        int fx = rd(sx + 16 + r * 4), fy = rd(sy + 1 + (hash(s, 99, 5) - 0.5) * 8);
        rect(fx - 2, fy - 3, 5, 5, SKIN[3]); rect(fx + 2, fy - 3, 1, 5, SKIN[2]); rect(fx - 2, fy - 3, 2, 1, SKIN[4]);
        p(fx, fy - 1, SKIN[1]); p(fx, fy + 1, SKIN[1]);
        for (int i = 0; i <= 4; i++) line(fx - 5 - i * 3, fy - 2 + i, fx - 3, fy - 2 + i, alpha(0xFFFFFFFF, 170 - i * 25));
    }

    /** A fixed pseudo-random number in [0, 1) for step s, item k and channel c (the same every time it is asked). */
    private static double hash(int s, int k, int c) {
        double v = Math.sin(s * 12.9898 + k * 78.233 + c * 37.719) * 43758.5453;
        return v - Math.floor(v);
    }

    /** The centre of the near or far fist. */
    static double[] fist(Pose q, boolean near) {
        double up = q.v[HIP] + 14 + q.v[BY] + q.v[UP], lean = q.v[LEAN];
        return new double[] { (near ? q.v[NHX] : q.v[FHX]) + lean + q.v[BX] + 1, (near ? q.v[NHY] : q.v[FHY]) + up + 1 };
    }

    static double[] foot(Pose q) { return new double[] { q.v[NFX] + q.v[BX] + 2, q.v[NFY] + q.v[BY] }; }

    static double[] wandTip(Pose q) {
        double[] h = hand(q);
        double an = Math.toRadians(q.v[WAND]);
        return new double[] { h[0] + 1 + Math.cos(an) * q.v[WLEN], h[1] + 1 + Math.sin(an) * q.v[WLEN] };
    }

    private static void paintDraco(Pose q, double t, int phase) {
        double[] v = q.v;
        double bx = v[BX], by = v[BY], lean = v[LEAN];
        double up = v[HIP] + 14 + by + v[UP];
        boolean back = v[BACK] > 0.5;
        int top = rd(-26 + up);                     // shoulders
        int hipY = rd(v[HIP] + by);
        int hem = hipY + 2;                         // robe hem
        double cape = v[CAPE];
        double hx0 = lean + v[HX] + bx, hy0 = v[HY];
        // the chest turns against the hips: shoulders swing one forward and one back, the head follows a little
        double tw = v[TWIST];
        hx0 += tw * 0.3;
        double nsx = 3 + tw * 0.6 + lean + bx, fsx = -3 - tw * 0.4 + lean + bx;
        double nsy = top + 1 - shrug(v[NHY] + up, top), fsy = top + 1 - shrug(v[FHY] + up, top);

        // long platinum hair falling down her back
        if (!back) hairBack(-3 + hx0, top - 8 + hy0, v[HAIR], v[LIFT], t, phase, 16, 4.6);

        // far arm, a shade darker, behind the body
        arm(fsx, fsy, v[FEX] + lean + bx, v[FEY] + up, v[FHX] + lean + bx, v[FHY] + up, -1, false);

        // the back of the robe drapes behind the legs and swings with momentum
        if (!back)
            for (int y = top + 3; y <= hem + 4; y++) {
                double s = (y - top - 3) / (double) Math.max(1, hem + 4 - top - 3);
                int xl = rd(-4 + lean * (1 - s) - cape * s * s - (s > 0.5 ? 1 : 0) + bx), xr = rd(-1 + bx);
                for (int x = xl; x <= xr; x++) p(x, y, shade(ROBE, across(x, xl, xr + 3) - 1));
                if (y == hem + 4 && cape > 1.5) for (int x = xl; x <= xl + 2; x++) p(x, y, LINING[1]);
            }

        // legs: black tights and polished shoes; the near leg is outlined against the far one
        leg(-2 + bx, hipY, v[FKX] + bx, v[FKY] + by, v[FFX] + bx, v[FFY] + by, -1, false);
        // a kicking leg raised to the hip or higher swings in front of the skirt and robe instead of behind them
        boolean kickFront = v[NFY] + by < hipY + 4 || v[NKY] + by < hipY;
        if (!kickFront) leg(1 + bx, hipY, v[NKX] + bx, v[NKY] + by, v[NFX] + bx, v[NFY] + by, 0, true);

        // pleated skirt, flaring when she moves fast
        int fl = rd(v[SKIRT]);
        for (int y = hipY; y <= hipY + 4; y++) {
            int wid = y - hipY >= 3 ? fl : fl / 2;
            int xl = -5 - wid + rd(bx), xr = 5 + wid + rd(bx);
            for (int x = xl; x <= xr; x++) {
                int pl = Math.floorMod(x - rd(bx), 3) == 0 ? -1 : 0;
                p(x, y, shade(SKIRT_C, across(x, xl, xr) + pl + (y == hipY + 4 ? -1 : 0)));
            }
        }

        // the robe: open at the front over shirt, tie and grey jumper, lined in Slytherin green
        for (int y = top; y <= hem; y++) {
            double s = (y - top) / (double) Math.max(1, hem - top);
            double sh = lean * (1 - s) + bx;
            int xl = rd(-4 - (s > 0.5 ? 1 : 0) - cape * 0.35 * s * s + sh), xr = rd(4 + (s > 0.65 ? 1 : 0) + (s < 0.5 ? tw * 0.4 : 0) + sh);
            if (y == top) { xl += 1; xr -= 1; }
            for (int x = xl; x <= xr; x++) p(x, y, shade(ROBE, across(x, xl, xr) + (y >= hem - 1 ? -1 : 0)));
            // the front opening slides round with the chest, narrowing as she turns away
            int fx = rd(sh + tw * 0.6 * (1 - s)), ow = tw <= -1.5 ? 1 : tw <= -0.5 ? 2 : 3;
            if (back) {
                // seen from behind: a seam down the middle of the robe
                if (y > top) p(fx, y, ROBE[1]);
                continue;
            }
            if (y > top) {
                // the front opening
                for (int x = fx; x < fx + ow; x++) {
                    int c;
                    if (y <= top + 3) c = x == fx + 1 && y == top + 2 ? LINING[3] : shade(SHIRT, y == top + 1 ? 3 : 2);
                    else if (y < hipY) c = shade(JUMPER, across(x, fx - 1, fx + 3));
                    else c = shade(SKIRT_C, across(x, fx - 1, fx + 3));
                    p(x, y, c);
                }
                if (ow >= 2 && y >= top + 2 && y <= top + 9) p(fx + 1, y, (y - top) % 2 == 1 ? SILVER[2] : LINING[2]);   // striped tie
                if (y >= top + 2) p(fx + ow, y, LINING[y > hem - 2 ? 1 : 2]);                                                // green lining at the front edge
                if (y >= top + 3 && tw > -1.5) p(fx - 1, y, LINING[1]);
            }
        }
        if (!back) {
            // Slytherin crest on the chest
            int cx = rd(-3 + lean * 0.7 + bx + tw * 0.4), cy = top + 5;
            p(cx, cy, LINING[3]); p(cx + 1, cy, LINING[2]); p(cx, cy + 1, LINING[2]); p(cx + 1, cy + 1, SILVER[3]);
        }

        if (kickFront) leg(1 + bx, hipY + 2, v[NKX] + bx, v[NKY] + by, v[NFX] + bx, v[NFY] + by, 0, true);

        // head
        stamp(back ? DRACO_BACK : headFor((int) v[EYES], (int) v[MOUTH]), rd(-6 + hx0), top - 12 + rd(hy0), DRACO_PAL);
        if (back) {
            // from behind, the long hair covers her back
            hairBack(2 + hx0, top - 3 + hy0, v[HAIR] * 0.5, v[LIFT], t, phase, 12, 5.4);
        } else {
            // a lock of hair falls in front of the far shoulder
            int hx = rd(-4 + hx0), hy = top - 2 + rd(hy0);
            p(hx, hy, HAIR_C[2]); p(hx, hy + 1, HAIR_C[1]); p(hx - 1, hy + 2, HAIR_C[1]);
        }

        // near arm and wand, outlined so the arm reads against the robe
        double[] h = hand(q);
        arm(nsx, nsy, v[NEX] + lean + bx, v[NEY] + up, h[0], h[1], 0, true);
        double an = Math.toRadians(v[WAND]);
        for (int i = 1; i <= rd(v[WLEN]); i++) {
            int c = i <= 2 ? WAND_C[1] : i == rd(v[WLEN]) ? WAND_C[4] : WAND_C[2 + (i % 3 == 0 ? 1 : 0)];
            p(rd(h[0] + 0.5 + Math.cos(an) * i), rd(h[1] + 0.5 + Math.sin(an) * i), c);
        }
        handPx(h[0], h[1], 0);
    }

    /** How far a shoulder lifts (0-2 px) when the hand on that side is raised above it. */
    private static double shrug(double handY, int top) {
        double over = (top + 1) - handY;
        return Math.max(0, Math.min(2, (over - 2) / 4));
    }

    private static void arm(double sx, double sy, double ex, double ey, double hx, double hy, int shift, boolean near) {
        if (near) { sep(sx, sy, ex, ey, 3); sep(ex, ey, hx, hy, 2); }
        int lit = near ? 1 : shift;
        limb(sx, sy, ex, ey, 3, ROBE, lit);
        limb(ex, ey, hx, hy, 2, ROBE, lit);
        // green cuff where the sleeve ends
        double dx = hx - ex, dy = hy - ey, l = Math.max(1, Math.hypot(dx, dy));
        p(rd(hx - dx / l), rd(hy - dy / l), LINING[2 + shift]);
        if (!near) handPx(hx, hy, shift);
    }

    private static void handPx(double hx, double hy, int shift) {
        int x = rd(hx), y = rd(hy);
        p(x, y, shade(SKIN, 3 + shift)); p(x + 1, y, shade(SKIN, 2 + shift));
        p(x, y + 1, shade(SKIN, 2 + shift)); p(x + 1, y + 1, shade(SKIN, 1 + shift));
    }

    private static void leg(double hx, double hy, double kx, double ky, double fx, double fy, int shift, boolean near) {
        if (near) { sep(hx, hy, kx, ky, 2); sep(kx, ky, fx, fy, 2); }
        limb(hx, hy, kx, ky, 2, TIGHTS, shift + 1);
        limb(kx, ky, fx, fy, 2, TIGHTS, shift + 1);
        // a knee highlight where the leg bends
        if (Math.abs(kx - (hx + fx) / 2) > 1.5 || Math.abs(ky - (hy + fy) / 2) > 1.5) p(rd(kx), rd(ky), shade(TIGHTS, 3 + shift));
        int x = rd(fx), y = rd(fy);
        // a Mary-Jane shoe: toe forward, a glint on the toe cap, a dark sole
        p(x - 1, y, shade(SHOE, 2 + shift)); p(x, y, shade(SHOE, 3 + shift)); p(x + 1, y, shade(SHOE, 3 + shift)); p(x + 2, y, shade(SHOE, 4 + shift));
        p(x - 1, y + 1, shade(SHOE, shift)); p(x, y + 1, shade(SHOE, 1 + shift)); p(x + 1, y + 1, shade(SHOE, 1 + shift)); p(x + 2, y + 1, shade(SHOE, 1 + shift));
    }

    private static void hairBack(double ax, double ay, double swing, double lift, double t, int phase, int len, double width) {
        for (int i = 0; i <= len; i++) {
            double s = i / (double) len;
            double y = ay + i * (1 + Math.min(0, lift) * 0.12) + Math.max(0, lift) * s;
            double x = ax - swing * s * s * 1.2 + Math.min(0, lift) * s * 1.2 + Math.sin(t * 2.2 + i * 0.5) * 0.35 * s;
            int w = (int) Math.round(width - 2.4 * s);
            int X = rd(x), Y = rd(y);
            for (int k = 0; k < w; k++) {
                int c = k == w - 1 ? HAIR_C[1] : HAIR_C[2];
                if ((k == 1 || k == w - 3) && (i + phase / 2 + k) % 4 == 1) c = HAIR_C[3];   // strands of shine that drift down
                if (i == len) c = HAIR_C[1];
                p(X - k, Y, c);
            }
        }
    }

    /** Tucked into a ball mid-roll: robe wrapped round, hair whipping in a spiral. */
    private static void paintBall(double pr, boolean ends) {
        double rx = ends ? 8 : 7, ry = ends ? 6 : 7, cy = -ry - 1;
        double th = pr * Math.PI * 2;
        // hair streaming round the ball
        for (int k = 1; k <= 9; k++) {
            double an = th + Math.PI - k * 0.32;
            double rr = rx + 1.5 - k * 0.08;
            p(rd(Math.cos(an) * rr), rd(cy + Math.sin(an) * rr * ry / rx), k < 4 ? HAIR_C[3] : k < 7 ? HAIR_C[2] : HAIR_C[1]);
            if (k < 6) p(rd(Math.cos(an) * (rr - 1)), rd(cy + Math.sin(an) * (rr - 1) * ry / rx), HAIR_C[2]);
        }
        ball(0, cy, rx, ry, ROBE, 0);
        // the green lining flashes as the robe turns
        for (int k = 0; k < 6; k++) {
            double an = th + 2.2 + k * 0.18;
            p(rd(Math.cos(an) * (rx - 0.8)), rd(cy + Math.sin(an) * (ry - 0.8)), LINING[2 + (k == 2 ? 1 : 0)]);
        }
        // tucked legs opposite her head
        double la = th + Math.PI;
        limb(Math.cos(la) * 1.5, cy + Math.sin(la) * 1.5, Math.cos(la) * (rx - 1.5), cy + Math.sin(la) * (ry - 1.5), 2, TIGHTS, 1);
        p(rd(Math.cos(la) * rx), rd(cy + Math.sin(la) * ry), SHOE[3]);
        // her head tucked in
        double hx = Math.cos(th) * 3.2, hy = cy + Math.sin(th) * 3.2;
        ball(hx, hy, 2.6, 2.6, HAIR_C, 0);
        p(rd(hx + Math.cos(th + 1.2) * 1.6), rd(hy + Math.sin(th + 1.2) * 1.6), SKIN[3]);
    }

    /** Mid-Apparition: she twists into a column of black smoke. */
    private static void apparition(Graphics2D g, int ox, int oy, int s, double t, Color tint) {
        double k = Math.min(1, (s - 3) / 4.0) * Math.min(1, (18 - s) / 4.0);
        for (int i = 0; i < 9; i++) {
            double an = t * 11 + i * 0.85, hgt = i * 3.2;
            double r = (6 - i * 0.45) * (0.5 + 0.5 * k);
            int x = ox + rd(Math.cos(an) * r), y = oy - 3 - rd(hgt);
            int sz = Math.max(2, 7 - i / 2);
            Color c = tint != null ? Sprites.alpha(tint, 150) : new Color(22, 16, 32, 210 - i * 14);
            g.setColor(c); g.fillOval(x - sz / 2, y - sz / 2, sz, sz);
            if (tint == null && i % 3 == 0) { g.setColor(new Color(150, 130, 200, 120)); g.fillRect(x - 1, y - sz / 2, 2, 1); }
        }
        if (tint == null && (s == 3 || s == 4 || s == 17 || s == 18)) {
            // the crack of Apparition
            g.setColor(new Color(235, 225, 255, 220));
            for (int i = 0; i < 6; i++) {
                double an = i * Math.PI / 3 + 0.3;
                g.drawLine(ox, oy - 14, ox + rd(Math.cos(an) * 9), oy - 14 + rd(Math.sin(an) * 9));
            }
        }
    }

    // ------------------------------------------------------------------ Voldemort

    static final int[] V_SKIN = ramp(0x55605E, 0x8D9893, 0xBCC4BA, 0xDCE0D4, 0xF2F3EA);
    static final int[] V_ROBE = ramp(0x050408, 0x0E0B12, 0x18131E, 0x271F31, 0x3D3150);
    static final int[] BONE = ramp(0x6E6450, 0x9C8F72, 0xC9BC9A, 0xE6DCC2, 0xFAF4E4);
    private static final int[] V_PAL = new int[128];
    static {
        int[] P = V_PAL;
        P['H'] = V_SKIN[4]; P['K'] = V_SKIN[3]; P['k'] = V_SKIN[2]; P['q'] = V_SKIN[1]; P['Q'] = V_SKIN[0];
        P['R'] = 0xFFFF3030; P['r'] = 0xFF9A1018; P['n'] = 0xFF3A3E3E; P['L'] = 0xFF6A5A60; P['b'] = V_SKIN[1];
    }
    /** The Dark Lord's head: bald, snake-flat nose slits, red eyes; facing right. */
    private static final String[] V_HEAD = {
        "..kKKk...",
        ".kKHHKk..",
        "qkKHKKkq.",
        "qkKKKKkkq",
        "qkKKkkkkq",
        "qkkbkkbbq",
        "qkkrkkRrq",
        "qqkkkkkkq",
        ".qQkkkQnq",
        ".qqkkkkq.",
        "..qkLLq..",
        "...qqq...",
    };
    private static final String[] V_HEAD_HIT = {
        "..kKKk...",
        ".kKHHKk..",
        "qkKHKKkq.",
        "qkKKKKkkq",
        "qkKKkkbbq",
        "qkkbkkkkq",
        "qkkbkkbbq",
        "qqkkkkkkq",
        ".qQkkLQnq",
        ".qqkLLLq.",
        "..qkLLq..",
        "...qqq...",
    };

    static void voldemort(Graphics2D g, World.Boss b, double t, boolean casting, Color tint, int face) {
        double rk = b.hitT > 0 ? Math.min(1, b.hitT / 0.25) : 0;
        int kx = rd(b.hitDx * 4 * rk), ky = rd(b.hitDy * 2 * rk);
        // he floats: a slow rise, a hold, a sink
        double bob = b.z > 0 ? 0 : (Math.sin(t * 2.2) > 0.3 ? 2 : Math.sin(t * 2.2) > -0.4 ? 1 : 0);
        int ox = rd(b.x) + kx, oy = rd(b.y - 8 - b.z - bob) + ky;
        boolean windup = casting && t - b.castTime < 0.45;
        double wu = windup ? Math.min(1, (t - b.castTime) / 0.45) : 0;
        // a shoulder throw turns him heels over head; afterwards he lies flat for a moment
        boolean down = b.downT > 0, thrown = !down && (b.z > 0.5 || Math.abs(b.rot % 360) > 0.5);
        boolean hit = (rk > 0.3 || thrown || down), stun = b.stun > 0 && !hit;
        boolean rage = b.phase == 2;

        double ex, ey, hx, hy, wand, fex, fey, fhx, fhy, lean = 0, hdx = 0, hdy = 0, billow = 0, sweep = 0;
        if (thrown) {
            // flung through the air: limbs flailing, robe streaming
            ex = 8; ey = -40; hx = 12; hy = -44; wand = -30; fex = -8; fey = -39; fhx = -13; fhy = -43; lean = -2; hdx = -1; billow = 1.3;
        } else if (down) {
            // flat on his back: arms thrown out, limp
            ex = 8; ey = -31; hx = 12; hy = -28; wand = 20; fex = -8; fey = -31; fhx = -12; fhy = -28; billow = 0.4;
        } else if (hit) {
            ex = 6; ey = -39; hx = 7; hy = -44; wand = -70; fex = -9; fey = -36; fhx = -12; fhy = -40; lean = -3; hdx = -1; hdy = -1; billow = 0.5;
        } else if (stun) {
            ex = 6; ey = -27; hx = 8; hy = -21; wand = 80; fex = -5; fey = -27; fhx = -6; fhy = -20; lean = 1; hdy = 2;
        } else if (windup || casting) {
            // every spell of his has its own gesture: a wind-up, then the cast
            World.Attack at = b.last == null ? World.Attack.AVADA : b.last;
            double[] g8 = casting && !windup ? castPose(at) : windupPose(at);
            ex = g8[0]; ey = g8[1]; hx = g8[2]; hy = g8[3]; wand = g8[4]; fex = g8[5]; fey = g8[6]; fhx = g8[7]; fhy = g8[8];
            lean = g8[9]; hdy = g8[10]; billow = g8[11] * (windup ? 0.6 + wu * 0.6 : 1); sweep = g8[12];
        } else {
            ex = 7; ey = -28; hx = 10; hy = -25; wand = 15; fex = -6; fey = -28; fhx = -7; fhy = -21;
        }

        begin(face);
        int top = -37;
        // far arm behind the robe
        vArm(-5 + lean, top + 2, fex + lean, fey, fhx + lean, fhy, -1, Double.NaN);
        // the robe: tall and narrow at the shoulders, pooling wide at the hem, with folds that sway
        int hem = -2;
        for (int y = top; y <= hem; y++) {
            double s = (y - top) / (double) (hem - top);
            double sh = lean * (1 - s) - sweep * s * s;
            double xl = -5 - s * 4.5 - billow * s * s * 3 + sh, xr = (y == top ? 4 : 5) + s * 3.5 + billow * s * s * 2 + sh;
            if (y == top) xl += 1;
            int L = rd(xl), R = rd(xr);
            for (int x = L; x <= R; x++) {
                int idx = across(x, L, R);
                for (int k = 0; k < 3; k++) {
                    double fx = xl + (0.28 + k * 0.24) * (xr - xl) + Math.sin(y * 0.22 + t * 1.6 + k * 1.7) * 0.9;
                    if (rd(fx) == x && s > 0.15) idx -= 1;
                    else if (rd(fx) == x - 1 && s > 0.15) idx += 1;
                }
                p(x, y, shade(V_ROBE, idx));
            }
            // the inner robe shows in a narrow V down his chest
            if (s < 0.45) {
                int vx = rd(1 + sh), vw = (int) (s * 6);
                for (int x = vx - vw / 2; x <= vx + vw / 2; x++) p(x, y, V_ROBE[x == vx - vw / 2 ? 3 : 2] & 0xFFFFFFFF);
                if (vw > 0) p(vx + vw / 2 + 1, y, V_ROBE[4]);
            }
        }
        // a silver clasp at the throat
        p(rd(1 + lean), top + 1, SILVER[3]); p(rd(2 + lean), top + 1, SILVER[1]);
        // tattered hem: ragged tendrils that ripple; longer and ember-lit in his rage
        for (int x = rd(-9.5 - billow * 3 - sweep + lean * 0); x <= rd(8.5 + billow * 2 - sweep); x++) {
            if (Math.floorMod(x * 7 + 3, 5) == 0) continue;
            int len = 1 + (int) (1.8 + 1.8 * Math.sin(x * 1.3 + t * 3.2)) + (rage ? 1 : 0);
            for (int j = 1; j <= len; j++) p(x - (j > 2 ? 1 : 0) * (sweep > 0 ? 1 : 0), hem + j, j == len && rage && Math.floorMod(x + (int) (t * 8), 4) == 0 ? 0xFFB02818 : V_ROBE[j > 1 ? 0 : 1]);
        }
        // high collar behind the neck
        for (int y = top - 4; y <= top; y++) for (int x = -5; x <= -1; x++) p(x + rd(lean), y, shade(V_ROBE, x == -5 ? 1 : 2));
        // head
        stamp(hit ? V_HEAD_HIT : V_HEAD, rd(-4 + lean + hdx), top - 12 + rd(hdy), V_PAL);
        // near arm, the yew wand and long white fingers
        vArm(5 + lean, top + 2, ex + lean, ey, hx + lean, hy, 0, wand);

        int rim = RIM;
        double rimAmt = 0.2;
        if (windup || casting) { rim = 0xFF50FF88; rimAmt = 0.14 + 0.12 * (windup ? wu : 1); }
        else if (rage) { rim = 0xFFFF5050; rimAmt = 0.24; }
        if (windup) {
            double an = Math.toRadians(wand);
            int tx = rd(hx + lean + Math.cos(an) * 10), ty = rd(hy + Math.sin(an) * 10);
            int r = 1 + (int) (wu * 3);
            for (int y = -r; y <= r; y++) for (int x = -r; x <= r; x++) if (x * x + y * y <= r * r) p(tx + x, ty + y, alpha(0xFF50FF82, 170));
            p(tx, ty, 0xFFFFFFFF);
        } else if (casting) {
            double an = Math.toRadians(wand);
            int tx = rd(hx + lean + Math.cos(an) * 10), ty = rd(hy + Math.sin(an) * 10);
            int fl = ((int) (t * 24)) % 3;
            rect(tx, ty - 2, 3, 5, alpha(0xFF50FF82, 150)); rect(tx + 1, ty - 2 + fl, 2, 2, 0xE6C8FFD2);
        }
        if (tint != null) { rotate(b.rot, -24); finish(g, ox, oy + rd(16 * Math.abs(Math.sin(Math.toRadians(b.rot)))), tint, 0, rim, 0); return; }
        Sprites.shadow(g, b.x, b.y, 18);
        if (casting) {
            g.setColor(new Color(60, 255, 110, 26 + ((int) (t * 8) % 2) * 14));
            g.fillOval(ox - 16, oy - 46, 32, 50);
        }
        if (rage) {
            // smoke curls off him in his rage
            for (int i = 0; i < 5; i++) {
                double ph = (t * 0.7 + i * 0.2) % 1.0;
                g.setColor(new Color(30, 10, 14, (int) (120 * (1 - ph))));
                int sx = ox + rd(Math.sin(i * 2.3 + t) * 7), sy = oy - rd(ph * 40);
                g.fillOval(sx - 3, sy - 3, 6, 6);
            }
        }
        // turned about his middle; lying down, the body sinks to rest on the floor
        rotate(b.rot, -24);
        int sink = rd(16 * Math.abs(Math.sin(Math.toRadians(b.rot))));
        finish(g, ox, oy + sink, null, b.hitFlash > 0 ? 0.85 : 0, rim, rimAmt);
        // red eyes glow through everything
        if (!hit && Math.abs(b.rot % 360) < 0.5) {
            int ex2 = ox + face * rd(2.5 + lean + hdx) + (face < 0 ? -1 : 0), ey2 = oy + top - 6 + rd(hdy);
            g.setColor(new Color(255, 40, 40, rage ? 110 : 60));
            g.fillOval(ex2 - 2, ey2 - 2, 5, 5);
        }
        if (b.stun > 0) {
            for (int i = 0; i < 3; i++) {
                double an = t * 6 + i * 2.1;
                g.setColor(new Color(0xFFE066));
                g.fillRect(ox + (int) (Math.cos(an) * 7), oy + top - 13 + (int) (Math.sin(an) * 2), 2, 1);
                g.fillRect(ox + (int) (Math.cos(an) * 7), oy + top - 14 + (int) (Math.sin(an) * 2), 1, 3);
            }
        }
    }

    /** {elbow, hand, wand angle, far elbow, far hand, lean, head drop, billow, sweep} as he winds up each spell. */
    private static double[] windupPose(World.Attack a) {
        switch (a) {
            case AVADA:        return new double[] { 6, -45, 3, -51, -150, 2, -33, 7, -33, -2, 0, 1, 0 };     // wand drawn back over the shoulder, free hand aiming
            case CRUCIO:       return new double[] { 7, -35, 9, -39, -70, 4, -33, 8, -36, 0, -1, 0.8, 0 };   // both hands clawing up by his chest
            case FIRE_SERPENT:
            case FIRE_RING:    return new double[] { 6, -27, 8, -20, 120, -8, -40, -11, -45, -1, 0, 1, 0 };   // wand low and back, free arm high
            case MORSMORDRE:   return new double[] { 5, -44, 6, -50, -90, -9, -37, -13, -40, -1, -2, 1.2, 0 }; // wand straight up at the sky
            case SMOKE:        return new double[] { 3, -32, -3, -30, -160, -2, -32, 4, -31, 1, 1, 0.4, 0 };  // arms crossed over his chest
            case GLASS:
            case DEPULSO:      return new double[] { 3, -29, -1, -25, 160, 5, -33, 10, -33, -1, 0, 0.8, 0 };  // wand back at the hip, free palm forward
            default:           return new double[] { 8, -40, 12, -45, -60, -8, -40, -12, -45, -1, -1, 1.2, 0 }; // summoning: both arms raised, palms up
        }
    }

    private static double[] castPose(World.Attack a) {
        switch (a) {
            case AVADA:        return new double[] { 10, -33, 14, -32, -3, -8, -31, -12, -29, 2, 0, 0.6, 2 };    // the killing thrust
            case CRUCIO:       return new double[] { 10, -32, 14, -29, 15, -7, -36, -9, -41, 2, 0, 0.6, 1 };     // a twisting jab, free fist raised
            case FIRE_SERPENT:
            case FIRE_RING:    return new double[] { 9, -38, 13, -42, -45, -7, -30, -10, -25, 1, 0, 1, 2 };     // a sweep up and forward
            case MORSMORDRE:   return new double[] { 6, -44, 8, -50, -80, -9, -36, -14, -38, -2, -2, 1.2, 0 };   // the Mark called down from the sky
            case SMOKE:        return new double[] { 9, -36, 14, -38, -20, -9, -36, -14, -38, 0, 0, 1.5, 0 };    // arms flung wide
            case GLASS:
            case DEPULSO:      return new double[] { 9, -33, 14, -33, 0, 6, -32, 11, -31, 3, 0, 0.8, 2 };       // a two-handed shove
            default:           return new double[] { 9, -33, 14, -28, 20, 3, -33, 8, -28, 3, 0, 1, 1 };         // a commanding sweep down at her
        }
    }

    /** A bell-sleeved arm ending in a skeletal hand; NaN wand angle means an empty, clawing hand. */
    private static void vArm(double sx, double sy, double ex, double ey, double hx, double hy, int shift, double wand) {
        // the near arm is outlined against his robe and a shade lighter, so it reads as an arm
        if (shift >= 0) { sep(sx, sy, ex, ey, 3); sep(ex, ey, hx, hy, 3); }
        limb(sx, sy, ex, ey, 3, V_ROBE, shift + (shift >= 0 ? 2 : 1));
        limb(ex, ey, hx, hy, 3, V_ROBE, shift + (shift >= 0 ? 2 : 1));
        double dx = hx - ex, dy = hy - ey, l = Math.max(1, Math.hypot(dx, dy)), ux = dx / l, uy = dy / l;
        // the sleeve flares at the wrist
        p(rd(hx - ux - uy * 2), rd(hy - uy + ux * 2), V_ROBE[2 + shift]);
        p(rd(hx - ux + uy * 2), rd(hy - uy - ux * 2), V_ROBE[1 + shift]);
        int x = rd(hx + ux), y = rd(hy + uy);
        p(x, y, shade(V_SKIN, 3 + shift)); p(x + 1, y, shade(V_SKIN, 2 + shift)); p(x, y + 1, shade(V_SKIN, 2 + shift));
        if (Double.isNaN(wand)) {
            // long fingers
            p(rd(x + ux * 2), rd(y + uy * 2), shade(V_SKIN, 2 + shift)); p(rd(x + ux * 3 + 1), rd(y + uy * 3 + 1), shade(V_SKIN, 1 + shift));
            p(rd(x + ux * 2 + 1), rd(y + uy * 2 + 1), shade(V_SKIN, 1 + shift));
            return;
        }
        double an = Math.toRadians(wand);
        for (int i = 1; i <= 10; i++) {
            int c = i <= 3 ? (i == 2 ? BONE[1] : BONE[2]) : i == 10 ? BONE[4] : BONE[3];
            p(rd(x + 0.5 + Math.cos(an) * i), rd(y + 0.5 + Math.sin(an) * i), c);
        }
        // fingers wrapped round the knobbly handle
        p(rd(x + Math.cos(an + 1.2) * 1.5), rd(y + Math.sin(an + 1.2) * 1.5), V_SKIN[3]);
    }

    // ------------------------------------------------------------------ Death Eaters

    static final int[] DE_ROBE = ramp(0x040406, 0x0C0B10, 0x16141C, 0x24212E, 0x383448);

    static void deathEater(Graphics2D g, World.Obj o, double t, int face) {
        int x = rd(o.x), y = rd(o.y);
        double rk = o.a > 0 ? Math.min(1, o.a / 0.2) : 0;
        // they drift a little as they float, robes swaying
        double bob = ((int) (t * 3 + o.idx * 0.7)) % 3 == 0 ? 1 : 0;
        int ox = x + rd(o.vx * 3 * rk), oy = y - rd(bob);
        boolean wind = o.stage == 1;
        begin(face);
        int top = -27;
        double lean = -2 * rk, sway = Math.sin(t * 2.4 + o.idx) * 1.2;
        // far arm
        limb(-3 + lean, top + 2, -5 + lean, top + 8, 3, DE_ROBE, 0);
        p(rd(-5 + lean), top + 9, V_SKIN[1]);
        for (int yy = top; yy <= -1; yy++) {
            double s = (yy - top) / (double) (-1 - top);
            double sh = lean * (1 - s) + sway * s * s;
            int L = rd(-3.5 - s * 4 + sh), R = rd(3.5 + s * 3.5 + sh);
            for (int xx = L; xx <= R; xx++) p(xx, yy, shade(DE_ROBE, across(xx, L, R) + (Math.floorMod(xx - rd(sh), 4) == 2 && s > 0.3 ? -1 : 0)));
        }
        for (int xx = -7; xx <= 6; xx++) {
            int len = (int) (1.5 + 1.5 * Math.sin(xx * 1.7 + t * 4 + o.idx));
            for (int j = 0; j < len; j++) if (Math.floorMod(xx, 3) != 1) p(xx + rd(sway), j, DE_ROBE[0]);
        }
        // the hood, and inside it the silver mask
        ball(lean - 0.5, top - 4.5, 5, 5.5, DE_ROBE, 1);
        p(rd(lean - 2), top - 10, DE_ROBE[2]); p(rd(lean - 3), top - 10, DE_ROBE[1]);
        stamp(DE_MASK, rd(lean - 1), top - 8, DE_PAL);
        // wand arm: raised, glowing green, when about to fire
        double ex = wind ? 6 : 6, ey = wind ? top - 1 : top + 6, hx = wind ? 9 : 9, hy = wind ? top - 4 : top + 9;
        limb(3 + lean, top + 2, ex + lean, ey, 3, DE_ROBE, 1);
        limb(ex + lean, ey, hx + lean, hy, 2, DE_ROBE, 1);
        int hxI = rd(hx + lean), hyI = rd(hy);
        p(hxI, hyI, V_SKIN[2]); p(hxI + 1, hyI, V_SKIN[1]);
        double an = wind ? -0.8 : 0.1;
        for (int i = 1; i <= 6; i++) p(rd(hxI + 0.5 + Math.cos(an) * i), rd(hyI + 0.5 + Math.sin(an) * i), i == 6 ? WAND_C[3] : WAND_C[i < 3 ? 0 : 1]);
        if (wind) {
            int tx = rd(hxI + Math.cos(an) * 7), ty = rd(hyI + Math.sin(an) * 7);
            for (int yy = -2; yy <= 2; yy++) for (int xx = -2; xx <= 2; xx++) if (xx * xx + yy * yy <= 4) p(tx + xx, ty + yy, alpha(0xFF5AFF82, 190));
            p(tx, ty, 0xFFFFFFFF);
        }
        Sprites.shadow(g, o.x, o.y, 12);
        finish(g, ox, oy, null, rk > 0.6 ? 0.85 : 0, wind ? 0xFF5AFF82 : RIM, wind ? 0.22 : 0.2);
    }

    private static final int[] DE_PAL = new int[128];
    static { DE_PAL['M'] = SILVER[3]; DE_PAL['m'] = SILVER[1]; DE_PAL['W'] = SILVER[4]; DE_PAL['E'] = 0xFF06060A; DE_PAL['L'] = 0xFF22222A; }
    /** The silver mask, filigreed, with dark eye holes. */
    private static final String[] DE_MASK = {
        ".mWM.",
        "mMWMM",
        "mEMmE",
        "mMmMM",
        ".mMLM",
        "..mm.",
    };

    // ------------------------------------------------------------------ Inferi

    static final int[] DEAD = ramp(0x343C38, 0x55615A, 0x7E8A82, 0xA4AFA6, 0xC8D2C6);
    static final int[] RAGS = ramp(0x161512, 0x25231F, 0x37342D, 0x4C4840, 0x645F54);
    private static final int[] INF_PAL = new int[128];
    static { INF_PAL['K'] = DEAD[3]; INF_PAL['k'] = DEAD[2]; INF_PAL['q'] = DEAD[1]; INF_PAL['Q'] = DEAD[0]; INF_PAL['E'] = 0xFF0C1410; INF_PAL['G'] = 0xFFDCFFE6; INF_PAL['n'] = DEAD[0]; }
    private static final String[] INF_HEAD = {
        ".qkk.",
        "qkKKk",
        "qkKkk",
        "qEkGk",
        "qkkkq",
        ".QnQ.",
    };

    static void inferius(Graphics2D g, World.Obj o, double t) {
        int x = rd(o.x), y = rd(o.y);
        int face = o.vx >= 0 ? 1 : -1;
        boolean wind = o.stage == 1, grab = o.stage == 2;
        // a lurching shamble: a long sag onto the good leg, then a quick drag of the bad one
        double ph = (t * 1.6 + o.idx * 0.37) % 1.0;
        boolean drag = ph > 0.7;
        int dip = drag ? 0 : 1;
        begin(face);
        double lean = grab ? 3 : wind ? -1 : 1;
        int hip = -9 + dip;
        // legs: the near one steps, the far one drags its foot
        limb(1, hip, 2, hip + 4, 1, DEAD, 0); limb(2, hip + 4, drag ? 2 : 3, -1, 1, DEAD, 0);
        p(drag ? 2 : 3, 0, DEAD[1]); p(drag ? 3 : 4, 0, DEAD[1]);
        limb(-1, hip, -2, hip + 4, 1, DEAD, -1); limb(-2, hip + 4, drag ? -2 : -4, -1, 1, DEAD, -1);
        p(drag ? -3 : -5, 0, DEAD[0]);
        // torso: rags over the hips, ribs showing above, hunched forward
        int sx = rd(2 + lean), sy = hip - 8;
        for (int yy = sy; yy <= hip + 1; yy++) {
            double s = (yy - sy) / (double) (hip + 1 - sy);
            double sh = (1 - s) * (lean + 1.5);
            int L = rd(-2.5 + sh), R = rd(2 + sh);
            for (int xx = L; xx <= R; xx++) {
                int c;
                if (s > 0.5) c = shade(RAGS, across(xx, L, R));
                else c = shade(DEAD, across(xx, L, R) + ((yy - sy) % 2 == 1 && xx > L ? -1 : 0));
                p(xx, yy, c);
            }
        }
        // ragged hem
        for (int xx = -3; xx <= 3; xx++) if (Math.floorMod(xx * 5 + o.idx, 3) != 0) p(xx, hip + 2, RAGS[1]);
        // far arm, then the head drooping forward, then the near arm
        double ex, ey, hx, hy;
        if (wind) { ex = sx + 1; ey = sy - 4; hx = sx + 2; hy = sy - 9; }
        else if (grab) { ex = sx + 5; ey = sy + 1; hx = sx + 10; hy = sy + 2; }
        else { ex = sx + 4; ey = sy + 1; hx = sx + 8; hy = sy + 2 + (drag ? 1 : 0); }
        limb(sx - 1, sy + 1, ex - 1, ey, 1, DEAD, -1); limb(ex - 1, ey, hx - 1, hy - 1, 1, DEAD, -1);
        claw(hx - 1, hy - 1, wind, -1);
        stamp(INF_HEAD, sx + (wind ? -1 : 1), sy - 6 + (wind ? -1 : 0), INF_PAL);
        limb(sx + 1, sy + 1, ex, ey + 1, 2, DEAD, 0); limb(ex, ey + 1, hx, hy, 1, DEAD, 0);
        claw(hx, hy, wind, 0);
        Sprites.shadow(g, o.x, o.y, 10);
        finish(g, x, y, null, 0, wind ? 0xFF7CFF9A : 0xFF9CC8D8, wind ? 0.24 : 0.22);
        // the eyes give off a pale glow
        g.setColor(new Color(200, 255, 220, wind ? 140 : 70));
        int gx = x + face * (sx + (wind ? -1 : 1) + 3) + (face < 0 ? -1 : 0), gy = y + sy - 3 + (wind ? -1 : 0);
        g.fillRect(gx - 1, gy - 1, 3, 3);
    }

    private static void claw(double hx, double hy, boolean up, int shift) {
        int x = rd(hx), y = rd(hy);
        if (up) { p(x, y - 1, shade(DEAD, 2 + shift)); p(x + 1, y - 2, shade(DEAD, 1 + shift)); p(x - 1, y - 2, shade(DEAD, 1 + shift)); }
        else { p(x + 1, y, shade(DEAD, 2 + shift)); p(x + 2, y + 1, shade(DEAD, 1 + shift)); p(x + 1, y + 1, shade(DEAD, 1 + shift)); }
    }

    // ------------------------------------------------------------------ snakes

    static final int[] NAGINI_C = ramp(0x1C2414, 0x313D26, 0x4A5838, 0x68784C, 0x8E9E6C);
    static final int[] BELLY = ramp(0x544E2C, 0x847C4A, 0xAEA66C, 0xD0C890, 0xECE6B8);
    static final int[] BLACK_SNAKE = ramp(0x050507, 0x0E0E14, 0x1A1A24, 0x2C2C3C, 0x484860);
    static final int[] BLACK_BELLY = ramp(0x101014, 0x1E1E26, 0x30303C, 0x464656, 0x606074);

    /**
     * A snake along a path of points from head to tail: a round body tapering to the tail, scaled with diamond
     * markings and a pale belly, then a wedge-shaped head with slit eyes and a flickering forked tongue.
     */
    static void snake(Graphics2D g, double[] xs, double[] ys, double thick, int[] body, int[] belly, int eye, double t, boolean tongue) {
        int n = xs.length;
        double mx = (xs[0] + xs[n - 1]) / 2, my = (ys[0] + ys[n - 1]) / 2;
        begin(1);
        for (int i = n - 1; i >= 1; i--) {
            double s = i / (double) (n - 1);
            double r = thick * (0.3 + 0.7 * Math.sin(Math.min(1, (1 - s) * 1.25) * Math.PI / 2)) * (s < 0.1 ? 0.82 : 1);
            double cx = xs[i] - mx, cy = ys[i] - my - 2;
            boolean mark = i % 7 < 2;
            for (int y = (int) Math.floor(cy - r); y <= Math.ceil(cy + r); y++)
                for (int x = (int) Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
                    double dx = (x + 0.5 - cx) / r, dy = (y + 0.5 - cy) / r;
                    if (dx * dx + dy * dy > 1) continue;
                    int c;
                    if (dy > 0.45) c = shade(belly, dy > 0.8 ? 1 : 2);
                    else {
                        int idx = dy < -0.55 ? 3 : dy < 0 ? 2 : 1;
                        if (dx < -0.5) idx -= 1;
                        if (mark && Math.abs(dx) < 0.45 && dy < 0.3) idx = 0;
                        if (!mark && i % 7 == 4 && Math.abs(dx) < 0.3 && dy < -0.2) idx = 4;
                        c = shade(body, idx);
                    }
                    p(x, y, c);
                }
        }
        // the head, a wedge pointing the way it moves
        double ux = xs[0] - xs[Math.min(2, n - 1)], uy = ys[0] - ys[Math.min(2, n - 1)], ul = Math.max(1e-6, Math.hypot(ux, uy));
        ux /= ul; uy /= ul;
        double nx = -uy, ny = ux, a = thick * 1.55, b = thick * 1.15;
        double hx = xs[0] - mx, hy = ys[0] - my - 2;
        for (int y = (int) Math.floor(hy - a - 1); y <= Math.ceil(hy + a + 1); y++)
            for (int x = (int) Math.floor(hx - a - 1); x <= Math.ceil(hx + a + 1); x++) {
                double rx = x + 0.5 - hx, ry = y + 0.5 - hy;
                double along = (rx * ux + ry * uy) / a, across = (rx * nx + ry * ny) / (b * (1 - Math.max(0, along) * 0.45));
                if (along * along + across * across > 1) continue;
                double dy = ry / b;
                int idx = dy < -0.4 ? 3 : dy < 0.2 ? 2 : 1;
                if (along > 0.55) idx += 1;
                p(x, y, dy > 0.6 ? shade(belly, 2) : shade(body, idx));
            }
        // slit eyes on either side of the head, nostrils at the snout
        for (int side = -1; side <= 1; side += 2) {
            int ex = rd(hx + ux * a * 0.25 + nx * b * 0.62 * side), ey = rd(hy + uy * a * 0.25 + ny * b * 0.62 * side) - 1;
            p(ex, ey, eye); p(ex, ey + 1, mix(eye, 0xFF000000, 0.35));
        }
        p(rd(hx + ux * a * 0.85), rd(hy + uy * a * 0.85) - 1, body[0]);
        if (tongue && ((int) (t * 7)) % 3 == 0) {
            int tx = rd(hx + ux * (a + 1)), ty = rd(hy + uy * (a + 1));
            line(tx, ty, tx + ux * 3, ty + uy * 3, 0xFFD02838);
            p(rd(tx + ux * 3 + nx), rd(ty + uy * 3 + ny), 0xFFD02838);
            p(rd(tx + ux * 3 - nx), rd(ty + uy * 3 - ny), 0xFFD02838);
        }
        finish(g, rd(mx), rd(my), null, 0, RIM, 0.18);
    }

    static void nagini(Graphics2D g, World.Obj o, double t) {
        double tail = Math.max(0, o.c - 60), nx = -o.vy, ny = o.vx;
        int n = (int) (o.c - tail) + 1;
        if (n < 3) return;
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            double s = o.c - i;
            // she ripples from side to side, the wave travelling down her body
            double wv = Math.sin(s * 0.16 + t * 14) * 2.8 * Math.min(1, i / 8.0);
            xs[i] = o.x + o.vx * s + nx * wv; ys[i] = o.y + o.vy * s + ny * wv;
        }
        Sprites.shadow(g, xs[0], ys[0] + 1, 10);
        snake(g, xs, ys, 3.2, NAGINI_C, BELLY, 0xFFFFD23A, t, true);
    }

    /** Malfoy's black snake: slithering to its spot, then coiled and waiting. */
    static void blackSnake(Graphics2D g, World.Obj o, double t) {
        double sp = Math.hypot(o.vx, o.vy);
        int n = 14;
        double[] xs = new double[n], ys = new double[n];
        if (sp < 1e-3) {
            // coiled, head raised and swaying
            for (int i = 0; i < n; i++) {
                double an = i * 0.75 + 0.6, r = 1 + i * 0.32;
                xs[i] = o.x + Math.cos(an) * r + (i == 0 ? Math.sin(t * 3) * 0.8 : 0); ys[i] = o.y + Math.sin(an) * r * 0.6 - (i < 3 ? 2 - i * 0.6 : 0);
            }
        } else {
            double ux = o.vx / sp, uy = o.vy / sp;
            for (int i = 0; i < n; i++) {
                double wv = Math.sin(t * 16 - i * 0.7) * 1.4 * Math.min(1, i / 3.0);
                xs[i] = o.x - ux * i - uy * wv; ys[i] = o.y - uy * i + ux * wv;
            }
        }
        snake(g, xs, ys, 1.5, BLACK_SNAKE, BLACK_BELLY, 0xFF7CFF9A, t, sp >= 1e-3);
    }
}
