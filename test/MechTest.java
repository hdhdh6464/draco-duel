import java.util.Arrays;

/** Checks individual mechanics of the duel. */
public class MechTest {
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        // 1. Every dodge with i-frames survives a killing curse fired through it; standing still does not.
        ActionType[] dodges = { ActionType.ROLL, ActionType.DASH, ActionType.APPARATE, null };
        for (ActionType d : dodges) {
            World w = quiet(1);
            w.b.x = 600; w.b.y = 380; w.p.x = 300; w.p.y = 380;
            spawn(w, World.Attack.AVADA);
            int fire = (int) Math.round(1.1 * 60);
            int lead = d == ActionType.DASH ? 4 : d == ActionType.APPARATE ? 4 : 12;
            for (int i = 0; i < fire - lead; i++) w.step();
            if (d == ActionType.ROLL) w.commit(Arrays.asList(World.PlanItem.move(d, 1, 0, d.steps)));
            if (d == ActionType.DASH) w.commit(Arrays.asList(new World.PlanItem(d, 1, 0, d.steps, false, false, 0, 0)));
            if (d == ActionType.APPARATE) w.commit(Arrays.asList(World.PlanItem.apparate(10, 0)));
            for (int i = 0; i < 30; i++) w.step();
            String n = d == null ? "stand still" : d.zh;
            check(n + " vs Avada", d == null ? w.over && !w.won : w.p.hp == 100, "hp=" + w.p.hp);
        }
        // 2. Backflip clears a fire ring as it passes, given the right timing: count the frames that work.
        {
            int ok = 0;
            StringBuilder good = new StringBuilder();
            for (int c = 60; c <= 100; c++) {
                World w = quiet(2);
                w.b.x = 600; w.b.y = 380; w.p.x = 500; w.p.y = 380;
                spawn(w, World.Attack.FIRE_RING);
                for (int i = 0; i < c; i++) w.step();
                w.commit(Arrays.asList(World.PlanItem.of(ActionType.BACKFLIP)));
                // only the first ring: the second one leaves at 1.5 s and needs its own dodge
                for (int i = c; i < 130; i++) w.step();
                if (w.p.hp == 100) { ok++; good.append(c).append(' '); }
            }
            check("backflip over fire ring has a timing window", ok >= 3, ok + " frames work: " + good);
        }
        // 3. Spells charge, store, and only fire on the second order; a release with nothing stored fizzles.
        {
            World w = quiet(3);
            w.p.x = 300; w.p.y = 380; w.b.x = 420; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.release(ActionType.STUPEFY)));
            for (int i = 0; i < 20; i++) w.step();
            check("release without charge fizzles", w.fizzles == 1 && "尚未儲存".equals(w.lastFizzle), "fizzles=" + w.fizzles + " " + w.lastFizzle);
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.STUPEFY)));
            for (int i = 0; i < 60; i++) w.step();
            check("charge stores", w.p.stored[ActionType.STUPEFY.spellIndex()] && w.b.hp == World.BOSS_MAX_HP, "stored=" + w.p.stored[2] + " bossHp=" + w.b.hp);
            w.commit(Arrays.asList(World.PlanItem.release(ActionType.STUPEFY)));
            for (int i = 0; i < 60; i++) w.step();
            check("release fires", !w.p.stored[2] && w.b.hp == World.BOSS_MAX_HP - 12, "bossHp=" + w.b.hp);
            check("charge costs mana, release is free", w.p.mana < World.MAX_MANA, "mana=" + w.p.mana);
        }
        // 4. Spells cancel Crucio orbs head-on.
        {
            World w = quiet(4);
            w.p.x = 300; w.p.y = 380; w.b.x = 520; w.b.y = 380;
            w.p.stored[2] = true;
            spawn(w, World.Attack.CRUCIO);
            for (int i = 0; i < 50; i++) w.step();
            w.commit(Arrays.asList(World.PlanItem.release(ActionType.STUPEFY)));
            for (int i = 0; i < 70; i++) w.step();
            check("Stupefy cancels Crucio orbs", w.cancels >= 1, "cancels=" + w.cancels);
        }
        // 5. Cooldowns: a second throw right away fizzles.
        {
            World w = quiet(5);
            w.p.x = 300; w.p.y = 380; w.b.x = 318; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW), World.PlanItem.of(ActionType.THROW)));
            for (int i = 0; i < 150; i++) w.step();
            check("triple slam lands (34) then cools down", w.b.hp == World.BOSS_MAX_HP - 34 && "冷卻中".equals(w.lastFizzle), "bossHp=" + w.b.hp + " " + w.lastFizzle);
        }
        // 6. Melee circle: Voldemort inside the circle is hit, outside is a whiff.
        {
            World w = quiet(6);
            w.p.x = 300; w.p.y = 380; w.b.x = 300 + ActionType.SPIN.meleeRadius() + 4; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.SPIN)));
            for (int i = 0; i < 40; i++) w.step();
            check("spin hits inside circle", w.b.hp == World.BOSS_MAX_HP - 14, "bossHp=" + w.b.hp);
            World w2 = quiet(6);
            w2.p.x = 300; w2.p.y = 380; w2.b.x = 300 + ActionType.KICK.meleeRadius() + 60; w2.b.y = 380;
            w2.commit(Arrays.asList(World.PlanItem.of(ActionType.KICK)));
            for (int i = 0; i < 50; i++) w2.step();
            check("jump kick misses beyond its leap", w2.b.hp == World.BOSS_MAX_HP, "bossHp=" + w2.b.hp);
            World w3 = quiet(6);
            w3.p.x = 300; w3.p.y = 380; w3.b.x = 300 + ActionType.KICK.meleeRadius() + 30; w3.b.y = 380;
            w3.commit(Arrays.asList(World.PlanItem.of(ActionType.KICK)));
            for (int i = 0; i < 50; i++) w3.step();
            check("jump kick leaps in and lands 11", w3.b.hp == World.BOSS_MAX_HP - 11, "bossHp=" + w3.b.hp);
        }
        // Mid jump kick she clears barrages and ground fire, but not lightning from the sky.
        {
            String[] names = { "Crucio orb", "ground fire", "Dark Mark lightning" };
            World.Kind[] kinds = { World.Kind.CRUCIO, World.Kind.FIRE_TILE, World.Kind.MARK };
            for (int k = 0; k < kinds.length; k++) {
                World w = quiet(20 + k);
                w.p.x = 300; w.p.y = 380; w.b.x = 330; w.b.y = 380;
                w.commit(Arrays.asList(World.PlanItem.of(ActionType.KICK)));
                for (int i = 0; i < 16; i++) w.step();
                World.Obj o = MeleeTest.enemy(w, kinds[k], w.p.x, w.p.y);
                o.a = 10; o.vx = 0; o.vy = 0; o.c = 0;
                w.step(); w.step();
                boolean hit = w.p.hp <= 0;
                check("mid jump kick vs " + names[k] + (k == 2 ? " -> hit" : " -> clears it"), k == 2 ? hit : !hit,
                      "hp=" + w.p.hp + " airborne=" + w.p.airborne());
            }
        }
        // Protego bounces glass shards and Death Eater bolts back as her own shots.
        for (World.Kind k : new World.Kind[] { World.Kind.SHARD, World.Kind.M_BOLT }) {
            World w = quiet(40);
            w.p.x = 300; w.p.y = 380; w.b.x = 600; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.PROTEGO)));
            for (int i = 0; i < 10; i++) w.step();
            World.Obj o = MeleeTest.enemy(w, k, 330, 380);
            o.vx = -120; o.vy = 0;
            boolean bounced = false;
            for (int i = 0; i < 30; i++) { w.step(); for (World.Obj r : w.objs) if (r.kind == World.Kind.P_REFLECT && !r.dead) bounced = true; }
            check("Protego bounces a " + k.name().toLowerCase() + " back", o.dead && bounced && w.p.hp > 0, "dead=" + o.dead + " reflected=" + bounced + " hp=" + w.p.hp);
        }
        // A spell cast at the very end of a plan lands before the next bullet time.
        {
            World w = quiet(30);
            w.p.x = 300; w.p.y = 380; w.b.x = 480; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.STUPEFY), World.PlanItem.release(ActionType.STUPEFY)));
            int guard = 0;
            while (!w.p.idle() && guard++ < 500) w.step();
            boolean waits = w.resolving();
            while (w.resolving() && guard++ < 700) w.step();
            check("the last spell of a plan lands before the next bullet time", waits && w.b.hp == World.BOSS_MAX_HP - 12,
                  "waited=" + waits + " bossHp=" + w.b.hp + " idle=" + w.idleT);
        }
        // 7. Death Eaters appear, shoot, and die to spells.
        {
            World w = quiet(7);
            w.p.x = 250; w.p.y = 380; w.b.x = 600; w.b.y = 380;
            spawn(w, World.Attack.SUMMON);
            for (int i = 0; i < 70; i++) w.step();
            int alive = w.minionsAlive();
            boolean shot = false;
            for (int i = 0; i < 200 && !shot; i++) { w.step(); for (World.Obj o : w.objs) if (o.kind == World.Kind.M_BOLT) shot = true; }
            check("summon 3 Death Eaters that shoot", alive == 3 && shot, "alive=" + alive + " shot=" + shot);
            w.p.stored[1] = true; w.p.stored[2] = true;
            w.commit(Arrays.asList(World.PlanItem.release(ActionType.STUPEFY), World.PlanItem.release(ActionType.EXPELLIARMUS)));
            for (int i = 0; i < 120; i++) w.step();
            check("spells hit the nearest Death Eater", w.minionsAlive() < 3, "alive=" + w.minionsAlive());
        }
        // 8. Free movement moves Malfoy when idle, at run or walk speed.
        {
            World w = quiet(8);
            w.p.x = 300; w.p.y = 380;
            w.p.freeDx = 1;
            for (int i = 0; i < 60; i++) w.step();
            double run = w.p.x - 300;
            w.p.walk = true;
            double x0 = w.p.x;
            for (int i = 0; i < 60; i++) w.step();
            check("free run 75 / walk 40 px per second", Math.abs(run - 75) < 1 && Math.abs(w.p.x - x0 - 40) < 1, "run=" + run + " walk=" + (w.p.x - x0));
        }
        // 9. Timeline: window = attack + 3 s, then free time before the next cast.
        {
            World w = new World(9);
            w.p.invuln = 1e9;   // only the timing matters here, not whether she survives standing still
            w.step();
            double win = w.bulletWindow(), busy = w.b.busy;
            int n = 0;
            boolean next = false;
            while (!next && n < 2000) { w.step(); n++; for (World.Ev e : w.ev) if (e.type == World.EvType.CAST_START) next = true; }
            double expect = busy;
            check("window = his attack, no gap", Math.abs(win - busy) < 1e-9, "win=" + win);
            check("next wave right when the last ends", Math.abs(n * World.DT - expect) < 0.05, "next after " + n * World.DT + "s, expected " + expect);
        }
        // 10. Crowded for 2 s without being hit, Voldemort apparates away; jabbing him keeps him there
        {
            World w = quiet(10);
            w.p.x = 300; w.p.y = 380; w.b.x = 340; w.b.y = 380;
            for (int i = 0; i < 150; i++) w.step();
            double d = Math.hypot(w.b.x - w.p.x, w.b.y - w.p.y);
            check("crowded and untouched -> he retreats", d > 150, "distance=" + d);
            World w2 = quiet(10);
            w2.p.x = 300; w2.p.y = 380; w2.b.x = 330; w2.b.y = 380;
            java.util.ArrayList<World.PlanItem> jabs = new java.util.ArrayList<>();
            for (ActionType m : new ActionType[] { ActionType.KICK, ActionType.FLURRY, ActionType.SPIN }) { jabs.add(World.PlanItem.of(m)); jabs.add(World.PlanItem.of(ActionType.WAIT)); }
            w2.commit(jabs);
            for (int i = 0; i < 150; i++) w2.step();
            double d2 = Math.hypot(w2.b.x - w2.p.x, w2.b.y - w2.p.y);
            check("kept under attack -> he stays", d2 < 60, "distance=" + d2 + " bossHp=" + w2.b.hp);
        }
        System.out.println(fails == 0 ? "ALL MECHANICS OK" : fails + " FAILED");
    }

    /** A world where Voldemort never casts on his own. */
    static World quiet(long seed) {
        World w = new World(seed);
        w.b.cooldown = 999;
        return w;
    }

    static void spawn(World w, World.Attack a) throws Exception {
        java.lang.reflect.Method m = World.class.getDeclaredMethod("spawn", World.Attack.class, double.class);
        m.setAccessible(true);
        double busy = (Double) m.invoke(w, a, 0.0);
        w.b.busy = busy + 99;
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + "  (" + detail + ")");
        if (!ok) fails++;
    }
}
