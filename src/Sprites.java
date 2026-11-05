import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

/** All pixel art, drawn with rectangles in world coordinates onto the low-res buffer. */
final class Sprites {
    private Sprites() {}

    // Malfoy (female): platinum hair, grey eyes, Slytherin uniform.
    static final Color HAIR = new Color(0xF3ECCF), HAIR_S = new Color(0xCDBF8E), HAIR_L = new Color(0xFFFBEA);
    static final Color SKIN = new Color(0xF6DCCB), SKIN_S = new Color(0xE0B8A2), EYE = new Color(0x6F8796), LIP = new Color(0xC9737A);
    static final Color EYE_SHUT = new Color(0x8A5A50), BLUSH = new Color(0xF0B8A8), MOUTH = new Color(0x6A2A2A), GREEN_D = new Color(0x165C34);
    static final Color ROBE = new Color(0x1B1B22), ROBE_L = new Color(0x2E2E3A), GREEN = new Color(0x1E7A45), SILVER = new Color(0xC9D1D6);
    static final Color SHIRT = new Color(0xEDEDED), SKIRT = new Color(0x40414C), SKIRT_D = new Color(0x2F3039);
    static final Color TIGHT = new Color(0x111116), SHOE = new Color(0x2A1E1A), WAND = new Color(0x7A5232), WAND_T = new Color(0xA8875E);
    // Voldemort.
    static final Color PALE = new Color(0xE9E6DD), PALE_S = new Color(0xBDBFB3), RED = new Color(0xFF2424);
    static final Color ROBE_V = new Color(0x141017), ROBE_V2 = new Color(0x2A2232), WAND_V = new Color(0xDCD3BC), DARK_V = new Color(0x55504A);
    static final Color GOLD = new Color(0xB89B4A), GOLD_L = new Color(0xE2C46E), GOLD_D = new Color(0x7A6328);

    /** Projectiles fly at chest height above their ground position. */
    static final int AIR = 12;

    /** Rect painter with horizontal mirroring around the sprite's feet and an optional ghost tint. */
    static final class Pix {
        final Graphics2D g; final int ox, oy, face; final Color tint;
        Pix(Graphics2D g, int ox, int oy, int face, Color tint) { this.g = g; this.ox = ox; this.oy = oy; this.face = face; this.tint = tint; }
        void r(int dx, int dy, int w, int h, Color c) {
            int x = face > 0 ? ox + dx : ox - dx - w;
            g.setColor(tint == null ? c : mix(c, tint, 0.65));
            g.fillRect(x, oy + dy, w, h);
        }
    }

    static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                         (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                         (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t), a.getAlpha());
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }

    static Color spellColor(ActionType a) {
        switch (a) {
            case DENSAUGEO: return new Color(0xFFF2A8);
            case EXPELLIARMUS: return new Color(0xFF3B3B);
            case STUPEFY: return new Color(0x8FD0FF);
            case SERPENSORTIA: return new Color(0x7CFF9A);
            case PROTEGO: return new Color(0xA8E4FF);
            default: return Color.WHITE;
        }
    }

    static void shadow(Graphics2D g, double x, double y, int w) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fillOval((int) Math.round(x) - w / 2, (int) Math.round(y) - 2, w, 4);
    }

    // ------------------------------------------------------------------ Malfoy

    // Characters are drawn by Chars: a pixel rig per character, finished with selective outlines and rim light.

    static void draco(Graphics2D g, World.Player p, double t, Color tint) {
        if (tint == null) shadow(g, p.x, p.y, 12);
        Chars.draco(g, p, t, tint);
        storedOrbs(g, p, t, tint);
    }

    /** Charged spells waiting to be released circle Malfoy's head. */
    static void storedOrbs(Graphics2D g, World.Player p, double t, Color tint) {
        int cx = (int) Math.round(p.x), cy = (int) Math.round(p.y - p.z() - 34);
        for (int i = 0; i < p.stored.length; i++) {
            if (!p.stored[i]) continue;
            double an = t * 3 + i * Math.PI / 2;
            Color c = spellColor(ActionType.SPELLS[i]);
            if (tint != null) c = mix(c, tint, 0.5);
            int x = cx + (int) Math.round(Math.cos(an) * 9), y = cy + (int) Math.round(Math.sin(an) * 3);
            g.setColor(alpha(c, 90)); g.fillOval(x - 3, y - 3, 6, 6);
            g.setColor(c); g.fillRect(x - 1, y - 1, 2, 2);
        }
    }

    /** A melee move's hit circle; green when an enemy is inside it. */
    static void meleeCircle(Graphics2D g, double x, double y, double r, boolean hit, int alpha) {
        g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] { 3, 2 }, 0));
        g.setColor(hit ? new Color(120, 255, 140, alpha) : new Color(255, 170, 70, alpha));
        int ir = (int) Math.round(r);
        g.drawOval((int) Math.round(x) - ir, (int) Math.round(y) - ir, ir * 2, ir * 2);
        g.setColor(hit ? new Color(120, 255, 140, alpha / 5) : new Color(255, 170, 70, alpha / 6));
        g.fillOval((int) Math.round(x) - ir, (int) Math.round(y) - ir, ir * 2, ir * 2);
        g.setStroke(new BasicStroke(1));
    }

    /** Small arrow circling Malfoy that always points at Voldemort. */
    static void bossArrow(Graphics2D g, World.Player p, World.Boss b) {
        if (b.hidden) return;
        double cx = p.x, cy = p.y - 12 - p.z(), ang = Math.atan2(b.y - 12 - cy, b.x - cx);
        double tx = cx + Math.cos(ang) * 20, ty = cy + Math.sin(ang) * 20;
        int[] xs = { (int) Math.round(tx + Math.cos(ang) * 4), (int) Math.round(tx + Math.cos(ang + 2.4) * 3), (int) Math.round(tx + Math.cos(ang - 2.4) * 3) };
        int[] ys = { (int) Math.round(ty + Math.sin(ang) * 4), (int) Math.round(ty + Math.sin(ang + 2.4) * 3), (int) Math.round(ty + Math.sin(ang - 2.4) * 3) };
        g.setColor(new Color(200, 140, 255, 220));
        g.fillPolygon(xs, ys, 3);
    }

    /** Protego: a dome of light over all of her, head to feet, with a ring on the floor and a glint running round. */
    static void shield(Graphics2D g, World.Player p, double t, Color tint) {
        int cx = (int) Math.round(p.x), cy = (int) Math.round(p.y - 19);
        int rx = 22, ry = 23;
        Color c = tint == null ? new Color(0xA8E4FF) : tint;
        g.setColor(alpha(c, 50));
        g.fillOval(cx - rx, cy - ry, rx * 2, ry * 2);
        g.setColor(alpha(c, 210));
        g.drawOval(cx - rx, cy - ry, rx * 2, ry * 2);
        g.drawOval(cx - rx, (int) p.y - 6, rx * 2, 12);
        // a sheen across the upper left of the dome
        g.setColor(alpha(Color.WHITE, 120));
        g.drawArc(cx - rx + 4, cy - ry + 4, rx * 2 - 8, ry * 2 - 8, 110, 50);
        double a = t * 5;
        g.setColor(alpha(Color.WHITE, 200));
        g.fillRect(cx + (int) (Math.cos(a) * (rx - 1)), cy + (int) (Math.sin(a) * (ry - 1)), 2, 2);
    }

    // ------------------------------------------------------------------ Voldemort

    static void voldemort(Graphics2D g, World.Boss b, double t, boolean casting, Color tint, int face) {
        Chars.voldemort(g, b, t, casting, tint, face);
    }

    // ------------------------------------------------------------------ hazards and spells

    /** Draw layer for an object: 0 painted on the floor, 1 depth-sorted with characters, 2 in the air above everything. */
    static int layer(World.Obj o) {
        switch (o.kind) {
            case FIRE_TILE: case FIRE_RING: case P_SNAKE: case CONE: return 0;
            case MINION: return o.active ? 1 : 0;
            case MARK: case NAGINI: case SMOKE: case SHARD: return o.active ? (o.kind == World.Kind.NAGINI ? 1 : 2) : 0;
            case INFERI: return 1;
            default: return 2;
        }
    }

    static void obj(Graphics2D g, World w, World.Obj o, double t) {
        switch (o.kind) {
            case AVADA: {
                int x0 = (int) o.x, y0 = (int) o.y - AIR;
                int x1 = (int) (o.x + o.vx * o.c), y1 = (int) (o.y + o.vy * o.c) - AIR;
                if (!o.active) {
                    double pr = Math.max(0, 1 - (o.delay - o.t) / 1.2);
                    g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] { 4, 3 }, (float) (7 - (t * 30) % 7)));
                    g.setColor(new Color(60, 255, 110, (int) (40 + 180 * pr)));
                    g.drawLine(x0, y0, x1, y1);
                    g.setColor(new Color(60, 255, 110, (int) (20 + 60 * pr)));
                    g.drawLine(x0, (int) o.y, x1, y1 + AIR);
                    g.setStroke(new BasicStroke(1));
                    if (o.c < 990) { g.setColor(new Color(200, 255, 200, 200)); g.fillRect(x1 - 1, y1 - 1, 3, 3); }
                } else {
                    int pulse = ((int) (t * 40)) % 2;
                    g.setStroke(new BasicStroke(14 + pulse * 2));
                    g.setColor(new Color(40, 255, 90, 50));
                    g.drawLine(x0, y0, x1, y1);
                    g.setStroke(new BasicStroke(8 + pulse));
                    g.setColor(new Color(40, 255, 90, 170));
                    g.drawLine(x0, y0, x1, y1);
                    g.setStroke(new BasicStroke(2));
                    g.setColor(new Color(220, 255, 230, 240));
                    g.drawLine(x0, y0, x1, y1);
                    g.setStroke(new BasicStroke(1));
                }
                break;
            }
            case CRUCIO: {
                int x = (int) o.x, y = (int) o.y - AIR;
                shadow(g, o.x, o.y, 5);
                if (o.active) {
                    for (int i = 1; i <= 3; i++) {
                        g.setColor(new Color(255, 40, 40, 140 - i * 40));
                        g.fillRect((int) (o.x - o.vx * 0.025 * i) - 1, (int) (o.y - o.vy * 0.025 * i) - AIR - 1, 3, 3);
                    }
                }
                g.setColor(new Color(0xFF3030)); g.fillOval(x - 3, y - 3, 6, 6);
                g.setColor(new Color(0xFFD0D0)); g.fillRect(x - 1, y - 1, 2, 2);
                int k = (int) (t * 20) + o.idx;
                g.setColor(new Color(255, 120, 120, 200));
                g.fillRect(x + (k % 3) * 2 - 3, y + ((k / 3) % 3) * 2 - 4, 1, 1);
                break;
            }
            case SERPENT: {
                if (!o.active) {
                    for (int i = 0; i < 6; i++) {
                        int h = 2 + (int) (3 * Math.abs(Math.sin(i * 1.7 + t * 16)));
                        g.setColor(i % 2 == 0 ? new Color(0xFF8A1E) : new Color(0xE8401C));
                        g.fillRect((int) w.b.x + i * 2 - 6, (int) w.b.y - h, 2, h);
                    }
                    break;
                }
                int x = (int) o.x, y = (int) o.y;
                double ang = Math.atan2(o.vy, o.vx);
                g.setColor(new Color(255, 120, 30, 90)); g.fillOval(x - 10, y - 16, 20, 18);
                g.setColor(new Color(0xE8401C)); g.fillRect(x - 5, y - 12, 10, 10);
                g.setColor(new Color(0xFF8A1E)); g.fillRect(x - 4, y - 11, 8, 7);
                g.setColor(new Color(0xFFE066)); g.fillRect(x - 2, y - 7, 4, 3);
                int ex = x + (int) Math.round(Math.cos(ang) * 3), ey = y - 10 + (int) Math.round(Math.sin(ang) * 2);
                g.setColor(new Color(0x2A0A00)); g.fillRect(ex - 2, ey, 1, 1); g.fillRect(ex + 1, ey, 1, 1);
                g.setColor(new Color(0xFFB347));
                g.fillRect(x + (int) (Math.cos(ang) * 7), y - 8 + (int) (Math.sin(ang) * 4), 2, 2);
                break;
            }
            case FIRE_TILE: {
                double fade = Math.min(1, (o.life - o.t) / 0.6);
                int x = (int) o.x, y = (int) o.y;
                for (int i = -4; i <= 4; i += 2) {
                    int h = (int) ((3 + 5 * Math.abs(Math.sin(i * 1.3 + t * 14 + o.x))) * fade);
                    g.setColor(alpha(new Color(0xE8401C), (int) (220 * fade))); g.fillRect(x + i, y - h, 2, h);
                    g.setColor(alpha(new Color(0xFFB347), (int) (220 * fade))); g.fillRect(x + i, y - h / 2, 2, h / 2);
                }
                break;
            }
            case MARK: {
                int x = (int) o.x, y = (int) o.y, r = (int) o.a;
                if (!o.active) {
                    double pr = Math.max(0, Math.min(1, o.t / o.delay));
                    g.setColor(new Color(60, 255, 120, (int) (30 + 50 * pr)));
                    g.fillOval(x - r, y - r, r * 2, r * 2);
                    g.setColor(new Color(60, 255, 120, (int) (90 + 140 * pr)));
                    g.drawOval(x - r, y - r, r * 2, r * 2);
                    int rr = (int) (r * pr);
                    g.drawOval(x - rr, y - rr, rr * 2, rr * 2);
                } else {
                    g.setColor(new Color(57, 255, 122, 110)); g.fillOval(x - r, y - r, r * 2, r * 2);
                    g.setColor(new Color(57, 255, 122, 200)); g.fillRect(x - r / 2, y - 160, r, 160);
                    g.setColor(new Color(235, 255, 240, 230)); g.fillRect(x - r / 5, y - 160, Math.max(2, r * 2 / 5), 160);
                }
                break;
            }
            case DARK_MARK: {
                double a = Math.min(1, o.t / 0.3) * Math.min(1, Math.max(0, (o.life - o.t) / 0.4));
                darkMark(g, (int) o.x, (int) o.y - 70, (int) (a * 220), t);
                break;
            }
            case NAGINI: {
                double sx = o.x, sy = o.y, ex = o.x + o.vx * o.a, ey = o.y + o.vy * o.a;
                if (!o.active) {
                    double pr = Math.max(0, 1 - (o.delay - o.t) / 1.0);
                    if (pr <= 0) break;
                    g.setStroke(new BasicStroke(14));
                    g.setColor(new Color(120, 160, 60, (int) (25 + 50 * pr)));
                    g.drawLine((int) sx, (int) sy, (int) ex, (int) ey);
                    g.setStroke(new BasicStroke(1));
                    chevrons(g, o, t, new Color(200, 230, 120, (int) (80 + 120 * pr)));
                    break;
                }
                Chars.nagini(g, o, t);
                break;
            }
            case SMOKE: {
                if (!o.active) {
                    double pr = Math.max(0, 1 - (o.delay - o.t) / 1.4);
                    if (pr <= 0) break;
                    g.setStroke(new BasicStroke(22));
                    g.setColor(new Color(60, 20, 90, (int) (25 + 55 * pr)));
                    g.drawLine((int) o.x, (int) o.y, (int) o.a, (int) o.b);
                    g.setStroke(new BasicStroke(1));
                    chevrons(g, o, t, new Color(190, 140, 240, (int) (80 + 120 * pr)));
                    g.setColor(new Color(220, 190, 255, (int) (120 + 120 * pr)));
                    for (int i = 0; i <= o.idx; i++) g.fillRect((int) o.a - o.idx * 2 + i * 4, (int) o.b - 1, 2, 2);
                    break;
                }
                int cx = (int) (o.x + o.vx * o.c), cy = (int) (o.y + o.vy * o.c) - 10;
                for (int i = 0; i < 7; i++) {
                    int r = 8 + (i * 5 + (int) (t * 20)) % 6;
                    int ox = cx - (int) (o.vx * i * 7), oy = cy - (int) (o.vy * i * 7);
                    g.setColor(new Color(14, 11, 20, 220 - i * 25));
                    g.fillOval(ox - r, oy - r, r * 2, r * 2);
                }
                Pix px = new Pix(g, cx, cy + 3, o.vx >= 0 ? 1 : -1, null);
                px.r(-3, -6, 6, 6, PALE); px.r(0, -5, 1, 1, RED); px.r(2, -5, 1, 1, RED);
                break;
            }
            case SHARD: {
                double sp = Math.max(1e-6, Math.hypot(o.vx, o.vy)), ux = o.vx / sp, uy = o.vy / sp;
                if (!o.active) {
                    double pr = Math.max(0, 1 - (o.delay - o.t) / 0.8);
                    if (pr <= 0) break;
                    g.setColor(new Color(200, 240, 255, (int) (30 + 110 * pr)));
                    if (o.e == 1) {
                        g.drawLine((int) o.x, (int) o.y, (int) (o.x + ux * 26 * pr), (int) (o.y + uy * 26 * pr));
                        break;
                    }
                    double bx = w.b.x, by = w.b.y;
                    g.drawLine((int) (bx + ux * 12), (int) (by + uy * 12), (int) (bx + ux * (12 + 26 * pr)), (int) (by + uy * (12 + 26 * pr)));
                    break;
                }
                int x = (int) o.x, y = (int) o.y - 10;
                g.setColor(new Color(0, 0, 0, 60)); g.fillRect((int) o.x, (int) o.y, 2, 1);
                g.setColor(new Color(0xBFEFFF)); g.drawLine((int) (x - ux * 5), (int) (y - uy * 5), x, y);
                g.setColor(Color.WHITE); g.fillRect(x, y, 1, 1);
                break;
            }
            case FIRE_RING: {
                int x = (int) o.x, y = (int) o.y;
                if (!o.active) {
                    double pr = Math.max(0, Math.min(1, o.t / o.delay));
                    int r = (int) (6 + 10 * pr);
                    g.setColor(new Color(255, 120, 30, (int) (80 + 120 * pr)));
                    g.drawOval(x - r, y - r / 2, r * 2, r);
                    break;
                }
                int r = (int) o.a;
                g.setStroke(new BasicStroke(6));
                g.setColor(new Color(232, 64, 28, 200)); g.drawOval(x - r, y - r, r * 2, r * 2);
                g.setStroke(new BasicStroke(2));
                g.setColor(new Color(255, 224, 102, 230)); g.drawOval(x - r, y - r, r * 2, r * 2);
                g.setStroke(new BasicStroke(1));
                for (int i = 0; i < 24; i++) {
                    double an = i * Math.PI / 12 + t * 0.5;
                    int fx = x + (int) (Math.cos(an) * r), fy = y + (int) (Math.sin(an) * r);
                    int h = 3 + (int) (4 * Math.abs(Math.sin(i * 2.1 + t * 15)));
                    g.setColor(new Color(0xFF8A1E)); g.fillRect(fx - 1, fy - h, 2, h);
                }
                break;
            }
            case INFERI: inferius(g, o, t); break;
            case MINION: deathEater(g, o, t, w.p.x >= o.x ? 1 : -1); break;
            case M_BOLT:
                shadow(g, o.x, o.y, 4);
                g.setColor(new Color(90, 220, 120, 120)); g.fillOval((int) o.x - 3, (int) o.y - AIR - 3, 6, 6);
                g.setColor(new Color(0xB8FFC8)); g.fillRect((int) o.x - 1, (int) o.y - AIR - 1, 2, 2);
                break;
            case CONE: {
                int n = 14;
                int[] xs = new int[n + 2], ys = new int[n + 2];
                xs[0] = (int) o.x; ys[0] = (int) o.y;
                for (int i = 0; i <= n; i++) {
                    double an = o.a - o.b + 2 * o.b * i / n;
                    xs[i + 1] = (int) (o.x + Math.cos(an) * o.c); ys[i + 1] = (int) (o.y + Math.sin(an) * o.c);
                }
                if (!o.active) {
                    double pr = Math.max(0, Math.min(1, o.t / o.delay));
                    g.setColor(new Color(180, 200, 255, (int) (25 + 60 * pr)));
                    g.fillPolygon(xs, ys, n + 2);
                    g.setColor(new Color(200, 220, 255, (int) (90 + 140 * pr)));
                    g.drawPolygon(xs, ys, n + 2);
                    int rr = (int) (o.c * pr);
                    g.drawArc((int) o.x - rr, (int) o.y - rr, rr * 2, rr * 2, (int) Math.toDegrees(-o.a - o.b), (int) Math.toDegrees(2 * o.b));
                } else {
                    g.setColor(new Color(220, 235, 255, 170));
                    g.fillPolygon(xs, ys, n + 2);
                    g.setColor(Color.WHITE);
                    g.drawPolygon(xs, ys, n + 2);
                }
                break;
            }
            case P_DENS: bolt(g, o, new Color(0xFFF2A8), 4, 9, t); break;
            case P_EXP: {
                // a scarlet beam-bolt with a long tail
                double sp = Math.max(1e-6, Math.hypot(o.vx, o.vy)), ux = o.vx / sp, uy = o.vy / sp;
                shadow(g, o.x, o.y, 6);
                int hx = (int) o.x, hy = (int) o.y - AIR;
                g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(255, 40, 40, 70));
                g.drawLine((int) (o.x - ux * 18), (int) (o.y - uy * 18) - AIR, hx, hy);
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0xFF3B3B));
                g.drawLine((int) (o.x - ux * 13), (int) (o.y - uy * 13) - AIR, hx, hy);
                g.setStroke(new BasicStroke(1));
                g.setColor(new Color(0xFFD0D0));
                g.drawLine((int) (o.x - ux * 7), (int) (o.y - uy * 7) - AIR, hx, hy);
                glow(g, hx, hy, 9, new Color(255, 60, 60), t);
                g.setColor(Color.WHITE); g.fillRect(hx - 1, hy - 1, 3, 3);
                break;
            }
            case P_STUP: {
                bolt(g, o, new Color(0x8FD0FF), 6, 15, t);
                // crackling arcs of lightning around the stunning orb
                int x = (int) o.x, y = (int) o.y - AIR, seed = (int) (t * 30);
                g.setColor(new Color(220, 240, 255, 220));
                for (int k = 0; k < 3; k++) {
                    double an = (seed * 2.3 + k * 2.1) % (Math.PI * 2);
                    int px = x, py = y;
                    for (int j = 1; j <= 3; j++) {
                        int nx = x + (int) Math.round(Math.cos(an + ((seed + j * 7 + k) % 5 - 2) * 0.3) * (3 + j * 3));
                        int ny = y + (int) Math.round(Math.sin(an + ((seed + j * 3 + k) % 5 - 2) * 0.3) * (3 + j * 3));
                        g.drawLine(px, py, nx, ny);
                        px = nx; py = ny;
                    }
                }
                break;
            }
            case P_REFLECT:
                bolt(g, o, new Color(0xFF3030), 4, 10, t);
                g.setColor(new Color(0xA8E4FF)); g.drawOval((int) o.x - 6, (int) o.y - AIR - 6, 12, 12);
                break;
            case P_SNAKE: Chars.blackSnake(g, o, t); break;
            default: break;
        }
    }

    private static void chevrons(Graphics2D g, World.Obj o, double t, Color c) {
        double len = o.kind == World.Kind.SMOKE ? o.d : o.a;
        double ux = o.vx, uy = o.vy, nx = -uy, ny = ux;
        g.setColor(c);
        double off = (t * 60) % 24;
        for (double s = off; s < len; s += 24) {
            double cx = o.x + ux * s, cy = o.y + uy * s;
            g.drawLine((int) cx, (int) cy, (int) (cx - ux * 4 + nx * 4), (int) (cy - uy * 4 + ny * 4));
            g.drawLine((int) cx, (int) cy, (int) (cx - ux * 4 - nx * 4), (int) (cy - uy * 4 - ny * 4));
        }
    }

    private static void deathEater(Graphics2D g, World.Obj o, double t, int face) {
        int x = (int) o.x, y = (int) o.y;
        if (!o.active) {
            double pr = Math.max(0, Math.min(1, o.t / o.delay));
            for (int i = 0; i < 4; i++) {
                double an = t * 8 + i * 1.6;
                g.setColor(new Color(18, 12, 26, (int) (60 + 140 * pr)));
                g.fillOval(x + (int) (Math.cos(an) * 5) - 4, y - 8 - (int) (pr * 10) + (int) (Math.sin(an) * 3) - 4, 8, 8);
            }
            return;
        }
        Chars.deathEater(g, o, t, face);
    }

    private static void inferius(Graphics2D g, World.Obj o, double t) {
        int x = (int) o.x, y = (int) o.y;
        Color flesh = new Color(0x9AA39A);
        if (!o.active) {
            double pr = Math.max(0, Math.min(1, o.t / o.delay));
            g.setColor(new Color(20, 30, 20, 160)); g.fillOval(x - 6, y - 2, 12, 5);
            g.setColor(new Color(120, 255, 140, (int) (60 + 100 * pr))); g.drawOval(x - 6, y - 2, 12, 5);
            int h = (int) (pr * 6);
            g.setColor(flesh); g.fillRect(x - 3, y - h, 1, h); g.fillRect(x + 2, y - h + 1, 1, Math.max(0, h - 1));
            g.fillRect(x - 4, y - h, 2, 1); g.fillRect(x + 2, y - h, 2, 1);
            return;
        }
        Chars.inferius(g, o, t);
    }

    /** A glowing orb with a fading comet tail. */
    private static void bolt(Graphics2D g, World.Obj o, Color c, int size, int halo, double t) {
        shadow(g, o.x, o.y, size + 2);
        int x = (int) o.x, y = (int) o.y - AIR;
        for (int i = 6; i >= 1; i--) {
            int s = Math.max(1, size - i / 2);
            g.setColor(alpha(c, 170 - i * 24));
            g.fillOval((int) (o.x - o.vx * 0.01 * i) - s / 2, (int) (o.y - o.vy * 0.01 * i) - AIR - s / 2, s, s);
        }
        glow(g, x, y, halo, c, t);
        g.setColor(c); g.fillOval(x - size / 2, y - size / 2, size, size);
        g.setColor(Color.WHITE); g.fillRect(x - size / 4, y - size / 4, Math.max(1, size / 2), Math.max(1, size / 2));
        // sparkles orbiting the core
        for (int k = 0; k < 3; k++) {
            double an = t * 14 + k * 2.09;
            g.setColor(alpha(Color.WHITE, 220));
            g.fillRect(x + (int) Math.round(Math.cos(an) * (size + 1)), y + (int) Math.round(Math.sin(an) * (size + 1)), 1, 1);
        }
    }

    /** A rotating rune circle on the floor while Malfoy charges a spell; it closes as the charge completes. */
    static void magicCircle(Graphics2D g, double px, double py, Color c, double progress, double t) {
        int x = (int) Math.round(px), y = (int) Math.round(py);
        int r = 16, ry = 6;
        g.setColor(alpha(c, 40 + (int) (progress * 50)));
        g.fillOval(x - r, y - ry, r * 2, ry * 2);
        g.setColor(alpha(c, 160));
        g.drawOval(x - r, y - ry, r * 2, ry * 2);
        g.drawOval(x - r + 4, y - ry + 2, r * 2 - 8, ry * 2 - 4);
        // rune ticks spin around; the lit arc grows with the charge
        int lit = (int) Math.round(12 * progress);
        for (int i = 0; i < 12; i++) {
            double an = t * 2.5 + i * Math.PI / 6;
            int tx = x + (int) Math.round(Math.cos(an) * (r - 2)), ty = y + (int) Math.round(Math.sin(an) * (ry - 1));
            g.setColor(i < lit ? alpha(Color.WHITE, 230) : alpha(c, 140));
            g.fillRect(tx, ty, i < lit ? 2 : 1, 1);
        }
        g.setColor(alpha(c, (int) (60 * progress)));
        g.fillRect(x - 1, y - 30, 2, 30);
    }

    /** Soft, flickering halo around a light source. */
    static void glow(Graphics2D g, int x, int y, int r, Color c, double t) {
        int rr = r + (((int) (t * 30)) % 2);
        g.setColor(alpha(c, 45)); g.fillOval(x - rr, y - rr, rr * 2, rr * 2);
        g.setColor(alpha(c, 80)); g.fillOval(x - rr / 2, y - rr / 2, rr, rr);
    }

    static void darkMark(Graphics2D g, int x, int y, int a, double t) {
        if (a <= 0) return;
        Color c = new Color(76, 255, 140, a), d = new Color(10, 40, 20, a);
        g.setColor(new Color(76, 255, 140, a / 5));
        g.fillOval(x - 16, y - 16, 32, 32);
        g.setColor(c);
        g.fillRect(x - 5, y - 8, 10, 8); g.fillRect(x - 6, y - 6, 12, 5); g.fillRect(x - 4, y, 8, 3);
        g.setColor(d);
        g.fillRect(x - 4, y - 5, 3, 3); g.fillRect(x + 1, y - 5, 3, 3); g.fillRect(x - 1, y - 1, 1, 1);
        g.fillRect(x - 3, y + 1, 1, 2); g.fillRect(x - 1, y + 1, 1, 2); g.fillRect(x + 1, y + 1, 1, 2);
        g.setColor(c);
        for (int i = 0; i < 14; i++) {
            int sx = x + (int) (Math.sin(i * 0.6 + t * 3) * 4), sy = y + 3 + i;
            g.fillRect(sx, sy, 2, 1);
        }
        g.fillRect(x + (int) (Math.sin(13 * 0.6 + t * 3) * 4) - 1, y + 17, 4, 2);
    }

    // ------------------------------------------------------------------ the Ministry Atrium

    static final int[] FIREPLACES = { 100, 250, 610, 760 };

    static BufferedImage map() {
        int W = World.MAPW, H = World.MAPH;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Random r = new Random(11);
        // polished dark-wood floor
        Color[] wood = { new Color(0x3A2416), new Color(0x35200F), new Color(0x3F2818), new Color(0x321E12) };
        for (int y = 0; y < H; y += 8) {
            int seam = r.nextInt(120);
            for (int x = -seam; x < W; ) {
                int len = 90 + r.nextInt(80);
                g.setColor(wood[r.nextInt(wood.length)]);
                g.fillRect(x, y, len, 8);
                g.setColor(new Color(255, 220, 170, 12));
                g.fillRect(x, y + 1, len, 2);
                g.setColor(new Color(0x24160D));
                g.fillRect(x, y, 1, 8);
                x += len;
            }
            g.setColor(new Color(0x2A190F)); g.fillRect(0, y, W, 1);
        }
        for (int i = 0; i < 9; i++) {
            g.setColor(new Color(255, 230, 190, 10));
            g.fillRect(0, 60 + i * 40, W, 8);
        }
        // gilded inlay around the fountain
        g.setColor(new Color(0x6E5A2A)); g.setStroke(new BasicStroke(2));
        int fcx = (int) World.OBST[0][0], fcy = (int) World.OBST[0][1];
        g.drawOval(fcx - 70, fcy - 70, 140, 140);
        g.setStroke(new BasicStroke(1));
        g.drawOval(fcx - 76, fcy - 76, 152, 152);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            g.fillRect(fcx + (int) (Math.cos(a) * 73) - 1, fcy + (int) (Math.sin(a) * 73) - 1, 3, 3);
        }
        // north wall: peacock-blue ceiling band, wood panelling, gilded fireplaces
        g.setColor(new Color(0x123A5A)); g.fillRect(0, 0, W, 10);
        g.setColor(GOLD_L);
        for (int x = 6; x < W; x += 16) g.fillRect(x, 4 + (x / 16) % 2 * 2, 2, 2);
        g.setColor(new Color(0x20140F)); g.fillRect(0, 10, W, 44);
        for (int x = 0; x < W; x += 40) { g.setColor(new Color(0x2A1A12)); g.fillRect(x + 3, 14, 34, 36); }
        g.setColor(GOLD); g.fillRect(0, 12, W, 1); g.fillRect(0, 52, W, 2);
        for (int fx : FIREPLACES) {
            g.setColor(GOLD); g.fillRect(fx - 15, 18, 30, 34);
            g.setColor(GOLD_L); g.fillRect(fx - 15, 18, 30, 2);
            g.setColor(new Color(0x0A0A08)); g.fillRect(fx - 11, 24, 22, 28);
        }
        // the Ministry's golden "M" plaque
        int mx = W / 2 - 10;
        g.setColor(GOLD); g.fillRect(mx, 22, 20, 22);
        g.setColor(new Color(0x20140F)); g.fillRect(mx + 2, 24, 16, 18);
        g.setColor(GOLD_L);
        g.fillRect(mx + 4, 27, 2, 12); g.fillRect(mx + 14, 27, 2, 12); g.fillRect(mx + 6, 29, 2, 2); g.fillRect(mx + 12, 29, 2, 2); g.fillRect(mx + 8, 31, 4, 2);
        // shadow at the foot of the wall, side walls and the near edge
        for (int i = 0; i < 8; i++) { g.setColor(new Color(0, 0, 0, 90 - i * 11)); g.fillRect(0, 54 + i, W, 1); }
        g.setColor(new Color(0x150D09)); g.fillRect(0, 10, 12, H); g.fillRect(W - 12, 10, 12, H); g.fillRect(0, H - 6, W, 6);
        g.setColor(GOLD_D); g.fillRect(11, 54, 1, H - 60); g.fillRect(W - 12, 54, 1, H - 60); g.fillRect(12, H - 7, W - 24, 1);
        g.dispose();
        return img;
    }

    /** Green Floo flames and fountain water: animated parts of the static map. */
    static void mapAnim(Graphics2D g, double t) {
        for (int fx : FIREPLACES) {
            for (int i = -9; i <= 8; i += 2) {
                int h = 6 + (int) (10 * Math.abs(Math.sin(i * 0.8 + t * 9 + fx)));
                g.setColor(new Color(0x1E9E4A)); g.fillRect(fx + i, 52 - h, 2, h);
                g.setColor(new Color(0x3BFF6A)); g.fillRect(fx + i, 52 - h / 2, 2, h / 2);
            }
            g.setColor(new Color(60, 255, 110, 30)); g.fillOval(fx - 20, 40, 40, 24);
        }
    }

    static BufferedImage pillarImg() {
        BufferedImage img = new BufferedImage(24, 70, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 90)); g.fillOval(0, 60, 24, 10);
        g.setColor(new Color(0x3A3540)); g.fillOval(1, 59, 22, 10);
        g.setColor(new Color(0x4A4452)); g.fillRect(5, 8, 14, 57);
        g.setColor(new Color(0x5C5566)); g.fillRect(6, 8, 3, 57);
        g.setColor(new Color(0x36313D)); g.fillRect(16, 8, 3, 57);
        g.setColor(GOLD); g.fillRect(3, 4, 18, 6);
        g.setColor(GOLD_L); g.fillRect(3, 4, 18, 1);
        g.setColor(GOLD_D); g.fillRect(3, 9, 18, 1);
        g.setColor(GOLD); g.fillRect(4, 58, 16, 3);
        g.dispose();
        return img;
    }

    static BufferedImage fountainImg() {
        BufferedImage img = new BufferedImage(72, 84, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        int cy = 62;
        g.setColor(new Color(0, 0, 0, 90)); g.fillOval(4, cy - 20, 64, 46);
        g.setColor(GOLD_D); g.fillOval(6, cy - 22, 60, 44);
        g.setColor(GOLD); g.fillOval(7, cy - 23, 58, 42);
        g.setColor(new Color(0x2E5F86)); g.fillOval(11, cy - 20, 50, 34);
        g.setColor(new Color(0x3E7AA6)); g.fillOval(16, cy - 18, 30, 14);
        // the golden statues: wizard (centre), witch, centaur, goblin
        Color s = new Color(0xD8B45A), sd = new Color(0xA8843A);
        g.setColor(sd); g.fillRect(31, 46, 10, 8);
        g.setColor(s);
        g.fillRect(32, 22, 8, 26); g.fillRect(33, 14, 6, 8); g.fillRect(40, 16, 2, 8); g.fillRect(41, 8, 1, 8);
        g.setColor(sd); g.fillRect(32, 22, 2, 26);
        g.setColor(s);
        g.fillRect(20, 32, 7, 18); g.fillRect(21, 26, 5, 6); g.fillRect(18, 28, 2, 6);
        g.fillRect(45, 36, 12, 8); g.fillRect(50, 26, 4, 10); g.fillRect(51, 22, 3, 4); g.fillRect(46, 44, 2, 6); g.fillRect(55, 44, 2, 6);
        g.fillRect(27, 44, 4, 6); g.fillRect(27, 41, 4, 3);
        g.setColor(new Color(0xFFE9A8)); g.fillRect(41, 7, 1, 1); g.fillRect(34, 15, 1, 1);
        g.dispose();
        return img;
    }

    static void fountainWater(Graphics2D g, double t) {
        double cx = World.OBST[0][0], cy = World.OBST[0][1];
        for (int i = 0; i < 10; i++) {
            double ph = (t * 1.6 + i * 0.1) % 1.0, side = i % 2 == 0 ? -1 : 1;
            int x = (int) (cx + side * (6 + ph * 16)), y = (int) (cy - 46 + ph * ph * 40 - ph * 14);
            g.setColor(new Color(170, 220, 255, (int) (200 * (1 - ph))));
            g.fillRect(x, y, 1, 2);
        }
    }

    /** App icon: Malfoy and a golden Time-Turner on a Slytherin-green tile, drawn at 32x32 then scaled up. */
    static BufferedImage icon(int size) {
        BufferedImage base = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        g.setColor(GOLD); g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(new Color(0x10301F)); g.fillRoundRect(1, 1, 30, 30, 7, 7);
        g.setColor(new Color(0x1E5E36)); g.fillOval(4, 4, 24, 24);
        g.setColor(new Color(0xFFD86B)); g.drawOval(19, 3, 9, 9);
        g.setColor(new Color(0xFFE9A8)); g.fillRect(23, 5, 2, 1); g.fillRect(23, 10, 2, 1); g.fillRect(23, 7, 2, 2);
        World.Player p = new World.Player();
        p.x = 13; p.y = 31;
        g.drawImage(Game.figure(32, 32, d -> draco(d, p, 0, null)), 0, 0, null);
        g.dispose();
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        o.drawImage(base, 0, 0, size, size, null);
        o.dispose();
        return out;
    }

    static Composite alphaComp(float a) { return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a); }
}
