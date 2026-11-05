import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Frames of every character animation, scaled up on the Atrium floor colour: java ArtSheet out.png [scale]. */
public class ArtSheet {
    static final int FW = 56, FH = 84;

    interface Frame { void draw(Graphics2D g, int x, int y); }

    static final List<String> names = new ArrayList<>();
    static final List<Frame> frames = new ArrayList<>();

    static void add(String n, Frame f) { names.add(n); frames.add(f); }

    static void malfoy(String n, ActionType a, int step, boolean alt, double t) {
        add(n, (g, x, y) -> {
            World.Player p = new World.Player();
            p.x = x; p.y = y; p.face = 1; p.mdx = 1; p.mdy = 0;
            if (a != null) {
                int steps = a.charged() && alt ? ActionType.RELEASE_STEPS : a == ActionType.RUN ? 240 : a.steps;
                p.cur = new World.PlanItem(a, 1, 0, steps, alt, false, 0, 0);
                p.step = step;
                if (a == ActionType.THROW && step >= 12) p.grabbed = true;
            }
            Sprites.draco(g, p, t, null);
            if (a == ActionType.PROTEGO && step >= 6) Sprites.shield(g, p, t, null);
        });
    }

    public static void main(String[] args) throws Exception {
        int sc = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        malfoy("idle", null, 0, false, 0.3);
        malfoy("idle exhale", null, 0, false, 2.0);
        malfoy("blink", null, 0, false, 3.75);
        for (int i = 0; i < 6; i++) malfoy("run " + i, ActionType.RUN, i * 4, false, 0.3 + i * 0.07);
        for (int i = 0; i < 4; i++) malfoy("walk " + i, ActionType.RUN, i * 9, true, 0.3);
        for (int st : new int[] { 4, 7, 10, 12, 14, 16, 18, 22, 27, 32, 36 }) malfoy("kick " + st, ActionType.KICK, st, false, 0.3);
        for (int st : new int[] { 4, 8, 11, 14, 17, 20, 23 }) malfoy("flurry " + st, ActionType.FLURRY, st, false, 0.3);
        for (int st : new int[] { 4, 7, 11, 15, 19, 24, 27 }) malfoy("spin " + st, ActionType.SPIN, st, false, 0.3);
        // the shoulder throw, simulated so Voldemort is where the game puts him
        for (int st : new int[] { 8, 13, 16, 19, 22, 25, 28, 31, 34, 37, 78, 84, 96 }) {
            final int target = st;
            add("throw " + st, (g, x, y) -> {
                World w = MechTest.quiet(5);
                w.p.x = 300; w.p.y = 380; w.b.x = 314; w.b.y = 380;
                w.commit(java.util.Arrays.asList(World.PlanItem.of(ActionType.THROW)));
                for (int i = 0; i < target; i++) w.step();
                double dx = x - w.p.x, dy = y - w.p.y;
                World.Player p = w.p.copy(); p.x += dx; p.y += dy;
                World.Boss b = w.b.copy(); b.x += dx; b.y += dy;
                if (b.y > p.y) { Sprites.draco(g, p, 0.3, null); Sprites.voldemort(g, b, 0.3, false, null, p.x >= b.x ? 1 : -1); }
                else { Sprites.voldemort(g, b, 0.3, false, null, p.x >= b.x ? 1 : -1); Sprites.draco(g, p, 0.3, null); }
            });
        }
        for (int st : new int[] { 0, 3, 6, 8, 12 }) malfoy("dens " + st, ActionType.DENSAUGEO, st, false, 0.3);
        malfoy("expel charge", ActionType.EXPELLIARMUS, 30, false, 0.3);
        for (int st : new int[] { 0, 3, 4, 8 }) malfoy("expel " + st, ActionType.EXPELLIARMUS, st, true, 0.3);
        malfoy("stupefy charge", ActionType.STUPEFY, 50, false, 0.3);
        for (int st : new int[] { 0, 2, 4, 8 }) malfoy("stupefy " + st, ActionType.STUPEFY, st, true, 0.3);
        malfoy("snake charge a", ActionType.SERPENSORTIA, 40, false, 0.1);
        malfoy("snake charge b", ActionType.SERPENSORTIA, 40, false, 0.3);
        for (int st : new int[] { 0, 4, 8 }) malfoy("snake " + st, ActionType.SERPENSORTIA, st, true, 0.3);
        malfoy("protego 3", ActionType.PROTEGO, 3, false, 0.3);
        malfoy("protego", ActionType.PROTEGO, 30, false, 0.3);
        for (int s : new int[] { 2, 10, 18 }) malfoy("hurt " + s, ActionType.HURT, s, false, 0.3);
        for (int s : new int[] { 2, 6, 12, 18, 24, 28 }) malfoy("roll " + s, ActionType.ROLL, s, false, 0.3);
        for (int s : new int[] { 1, 6, 14 }) malfoy("dash " + s, ActionType.DASH, s, false, 0.3);
        for (int s : new int[] { 2, 5, 12, 20, 29, 33 }) malfoy("flip " + s, ActionType.BACKFLIP, s, false, 0.3);
        for (int s : new int[] { 1, 8, 19 }) malfoy("apparate " + s, ActionType.APPARATE, s, false, 0.3);

        String[] vn = { "V idle", "V idle b", "V wind-up", "V cast", "V hit", "V stun", "V rage" };
        for (int k = 0; k < vn.length; k++) {
            final int kk = k;
            add(vn[k], (g, x, y) -> {
                World.Boss b = new World.Boss();
                b.x = x; b.y = y + 8; b.castTime = kk == 2 ? 0.0 : -5;
                if (kk == 4) { b.hitT = 0.3; b.hitDx = -1; b.hitDy = 0; }
                if (kk == 5) b.stun = 1;
                if (kk == 6) b.phase = 2;
                double t = kk == 1 ? 1.6 : kk == 2 ? 0.3 : 1.0;
                Sprites.voldemort(g, b, t, kk == 2 || kk == 3, null, 1);
            });
        }
        for (World.Attack at : World.Attack.values()) {
            for (int k = 0; k < 2; k++) {
                final int kk = k;
                add("V " + at.name().toLowerCase() + (k == 0 ? " wind" : " cast"), (g, x, y) -> {
                    World.Boss b = new World.Boss();
                    b.x = x; b.y = y + 8; b.last = at; b.castTime = kk == 0 ? 0.0 : -5;
                    Sprites.voldemort(g, b, kk == 0 ? 0.3 : 1.0, true, null, 1);
                });
            }
        }
        World w = new World(1);
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            add(k == 0 ? "Death Eater" : "DE wind-up", (g, x, y) -> {
                World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, x, y); de.stage = kk;
                w.p.x = x + 50;
                Sprites.obj(g, w, de, 1.0);
            });
        }
        String[] inn = { "Inferius", "Inferius b", "Inf wind-up", "Inf grab" };
        for (int k = 0; k < inn.length; k++) {
            final int kk = k;
            add(inn[k], (g, x, y) -> {
                World.Obj inf = MeleeTest.enemy(w, World.Kind.INFERI, x, y);
                inf.vx = 1; inf.stage = kk >= 2 ? kk - 1 : 0;
                Sprites.obj(g, w, inf, kk == 1 ? 0.5 : 0.1);
            });
        }

        for (int k = 0; k < 3; k++) {
            final int kk = k;
            add(k == 0 ? "Nagini" : k == 1 ? "snake" : "snake coiled", (g, x, y) -> {
                World.Obj o = MeleeTest.enemy(w, kk == 0 ? World.Kind.NAGINI : World.Kind.P_SNAKE, x - 16, y - 10);
                if (kk == 0) { o.vx = 0.8; o.vy = 0.6; o.c = 40; o.a = 99; }
                else if (kk == 1) { o.vx = 30; o.vy = 0; o.x = x + 6; }
                else { o.vx = 0; o.vy = 0; o.x = x; o.y = y - 4; }
                Sprites.obj(g, w, o, 0.4);
            });
        }
        if (args.length > 2) {
            // keep only the frames whose names match
            for (int i = names.size() - 1; i >= 0; i--) if (!names.get(i).matches(args[2])) { names.remove(i); frames.remove(i); }
        }
        int cols = Math.min(12, frames.size()), rows = (frames.size() + cols - 1) / cols;
        int k = 1;
        BufferedImage img = new BufferedImage(FW * cols, FH * rows, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A2416)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < frames.size(); i++) frames.get(i).draw(g, (i % cols) * FW + FW / 2, (i / cols) * FH + FH - 6);
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * sc, img.getHeight() * sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = big.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        bg.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        bg.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 3 * sc)); bg.setColor(new Color(255, 255, 255, 200));
        for (int i = 0; i < names.size(); i++) bg.drawString(names.get(i), (i % cols) * FW * k * sc + 4, (i / cols) * FH * k * sc + 3 * sc + 2);
        bg.dispose();
        ImageIO.write(big, "png", new File(args[0]));
    }
}
