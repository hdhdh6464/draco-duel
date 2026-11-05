import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Melee moves against every kind of enemy, plus a sprite sheet of the run and walk cycles. */
public class MeleeTest {
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        ActionType[] moves = { ActionType.KICK, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW };
        for (ActionType a : moves) {
            // a Death Eater and an Inferius inside the circle, Voldemort far away
            World w = MechTest.quiet(11);
            w.p.x = 300; w.p.y = 380; w.b.x = 700; w.b.y = 380;
            World.Obj de = enemy(w, World.Kind.MINION, 300 + a.meleeRadius() - 6, 380);
            de.b = 10;
            if (a != ActionType.FLURRY) de.a = 5;   // held still: this checks the circle, not a chase
            World.Obj inf = enemy(w, World.Kind.INFERI, 300, 380 - a.meleeRadius() + 6);
            double hp0 = de.b;
            w.commit(Arrays.asList(World.PlanItem.of(a)));
            for (int i = 0; i < 70; i++) w.step();
            boolean deHit = de.dead || de.b < hp0, infHit = inf.dead;
            boolean far = a == ActionType.FLURRY || a == ActionType.KICK; // these leap at the nearest enemy, so they may finish anywhere
            MechTest.check(a.zh + " hits Death Eater" + (far ? "" : " and Inferius"), deHit && (far || infHit),
                           "deathEater hp " + hp0 + "->" + de.b + (de.dead ? " (dead)" : "") + ", inferius dead=" + inf.dead);
            MechTest.check(a.zh + " leaves far Voldemort alone", w.b.hp == World.BOSS_MAX_HP, "bossHp=" + w.b.hp);
        }
        // the flurry lands four punches of 2 on the nearest enemy, shuffling after it as it is knocked back
        {
            World w = MechTest.quiet(12);
            w.p.x = 300; w.p.y = 380; w.b.x = 700; w.b.y = 380;
            World.Obj de = enemy(w, World.Kind.MINION, 320, 380);
            de.b = 30; de.a = 5;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.FLURRY)));
            for (int i = 0; i < 70; i++) w.step();
            MechTest.check("flurry: eight punches of 1 on the nearest Death Eater", de.b == 22 && w.b.hp == World.BOSS_MAX_HP, "deHp=" + de.b + " p.x=" + w.p.x);
        }
        // shoulder throw: his flying body hits a Death Eater on the way over for 30% of the throw
        {
            World w = MechTest.quiet(14);
            w.p.x = 300; w.p.y = 380; w.b.x = 314; w.b.y = 380;
            World.Obj de = enemy(w, World.Kind.MINION, 273, 380);
            de.b = 10; de.a = 5;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
            for (int i = 0; i < 40; i++) w.step();
            MechTest.check("thrown Voldemort crashes into a Death Eater: 30% (3) on each of the first two throws", de.b == 4, "deHp=" + de.b + " bossHp=" + w.b.hp);
        }
        // shoulder throw: a curse that touches his flying body bounces back out as hers
        {
            World w = MechTest.quiet(15);
            w.p.x = 300; w.p.y = 380; w.b.x = 314; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
            for (int i = 0; i < 15; i++) w.step();
            World.Obj orb = enemy(w, World.Kind.CRUCIO, w.b.x - 4, w.b.y);
            orb.vx = 0; orb.vy = 0; orb.c = 0; orb.e = 1;
            for (int i = 0; i < 4; i++) w.step();
            boolean bounced = false;
            for (World.Obj o : w.objs) if (o.kind == World.Kind.P_REFLECT && !o.dead) bounced = true;
            MechTest.check("his barrage bounces off his thrown body", orb.dead && bounced && w.p.hp > 0, "orbDead=" + orb.dead + " reflected=" + bounced + " hp=" + w.p.hp);
        }
        // spin with everything around: all three kinds take it at once
        {
            World w = MechTest.quiet(13);
            w.p.x = 300; w.p.y = 380; w.b.x = 325; w.b.y = 380;
            World.Obj de = enemy(w, World.Kind.MINION, 280, 380); de.b = 30;
            World.Obj inf = enemy(w, World.Kind.INFERI, 300, 355);
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.SPIN)));
            for (int i = 0; i < 40; i++) w.step();
            MechTest.check("spin hits Voldemort, Death Eater and Inferius together",
                           w.b.hp == World.BOSS_MAX_HP - 14 && de.b == 16 && inf.dead, "boss=" + w.b.hp + " de=" + de.b + " inf=" + inf.dead);
        }
        sheet(args[0]);
        System.out.println(fails + MechTest.fails == 0 ? "MELEE OK" : (fails + MechTest.fails) + " FAILED");
    }

    static World.Obj enemy(World w, World.Kind k, double x, double y) {
        World.Obj o = new World.Obj();
        o.kind = k; o.hostile = true; o.castId = -5; o.x = x; o.y = y; o.active = true; o.t = 2; o.delay = 0; o.life = 99;
        o.d = 99; o.idx = 1; o.c = 0;
        w.objs.add(o);
        return o;
    }

    /** Run cycle (top) and walk cycle (bottom), 4 frames each, scaled 8x. */
    static void sheet(String out) throws Exception {
        int fw = 30, fh = 36, sc = 8;
        BufferedImage img = new BufferedImage(fw * 4, fh * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A2416)); g.fillRect(0, 0, fw * 4, fh * 2);
        for (int row = 0; row < 2; row++)
            for (int f = 0; f < 4; f++) {
                World.Player p = new World.Player();
                p.x = f * fw + 15; p.y = row * fh + 33;
                p.walk = row == 1;
                p.freeAnim = f * (row == 0 ? 5 : 9) + 1;
                Sprites.draco(g, p, 0, null);
            }
        g.dispose();
        BufferedImage big = new BufferedImage(img.getWidth() * sc, img.getHeight() * sc, BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = big.createGraphics();
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        b.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
        b.dispose();
        ImageIO.write(big, "png", new File(out));
    }
}
