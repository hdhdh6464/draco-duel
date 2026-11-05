import java.util.Arrays;

/** Summon caps, enemy wind-ups opening bullet time, the Inferius grab, jump, and the triple slam. */
public class EnemyTest {
    public static void main(String[] args) throws Exception {
        // caps: summoning twice never makes more than two of a kind
        {
            World w = MechTest.quiet(31);
            w.p.x = 250; w.p.y = 380; w.b.x = 600; w.b.y = 380;
            MechTest.spawn(w, World.Attack.SUMMON); MechTest.spawn(w, World.Attack.SUMMON); MechTest.spawn(w, World.Attack.SUMMON);
            MechTest.spawn(w, World.Attack.INFERI); MechTest.spawn(w, World.Attack.INFERI); MechTest.spawn(w, World.Attack.INFERI); MechTest.spawn(w, World.Attack.INFERI);
            for (int i = 0; i < 80; i++) w.step();
            MechTest.check("at most 3 Death Eaters and 3 Inferi, side by side", w.minionsAlive() == 3 && w.inferiAlive() == 3,
                           "deathEaters=" + w.minionsAlive() + " inferi=" + w.inferiAlive());
            // Voldemort's picker skips summons that are already capped
            int bad = 0;
            for (int s = 0; s < 60; s++) {
                World v = new World(300 + s);
                v.p.x = 250; v.p.y = 380; v.b.x = 450; v.b.y = 380;
                MechTest.spawn(v, World.Attack.SUMMON); MechTest.spawn(v, World.Attack.INFERI);
                v.b.busy = 0; v.b.cooldown = 1.1; v.b.last = null;
                for (int i = 0; i < 70; i++) v.step();
                if (v.minionsAlive() > 3 || v.inferiAlive() > 3) bad++;
            }
            MechTest.check("Voldemort never exceeds the caps", bad == 0, "violations=" + bad);
        }
        // a Death Eater winding up a shot announces it
        {
            World w = MechTest.quiet(32);
            w.p.x = 250; w.p.y = 380;
            World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, 380, 380); de.b = 10; de.d = 0.8;
            boolean announced = false;
            for (int i = 0; i < 60 && !announced; i++) { w.step(); for (World.Ev e : w.ev) if (e.type == World.EvType.ENEMY_ATTACK) announced = true; }
            MechTest.check("Death Eater wind-up -> ENEMY_ATTACK", announced, "");
        }
        // Inferius: wind-up then grab for 10; a timed jump clears the grab
        for (boolean jump : new boolean[] { false, true }) {
            World w = MechTest.quiet(33);
            w.p.x = 300; w.p.y = 380;
            World.Obj inf = MeleeTest.enemy(w, World.Kind.INFERI, 318, 380); inf.c = 30;
            boolean announced = false;
            for (int i = 0; i < 40; i++) {
                w.step();
                for (World.Ev e : w.ev) if (e.type == World.EvType.ENEMY_ATTACK) {
                    announced = true;
                    if (jump) w.commit(Arrays.asList(World.PlanItem.of(ActionType.BACKFLIP)));
                }
            }
            MechTest.check(jump ? "backflip clears the Inferius grab" : "Inferius winds up and its grab is fatal",
                           announced && w.p.hp == (jump ? 100 : 0), "announced=" + announced + " hp=" + w.p.hp);
        }
        // triple slam: 34 damage in three hits, and she cannot be hurt while holding him
        {
            World w = MechTest.quiet(34);
            w.p.x = 300; w.p.y = 380; w.b.x = 316; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
            int slams = 0;
            boolean shot = false;
            double maxDist = 0;
            for (int i = 0; i < 90; i++) {
                w.step();
                maxDist = Math.max(maxDist, Math.hypot(w.b.x - w.p.x, w.b.y - w.p.y));
                for (World.Ev e : w.ev) if (e.type == World.EvType.THROW) slams++;
                if (i == 30 && !shot) {
                    // a Death Eater bolt straight through her mid-throw
                    World.Obj s = new World.Obj();
                    s.kind = World.Kind.M_BOLT; s.hostile = true; s.castId = -1; s.active = true; s.life = 4;
                    s.x = w.p.x - 30; s.y = w.p.y; s.vx = 130; s.vy = 0;
                    w.objs.add(s);
                    shot = true;
                }
            }
            MechTest.check("he never leaves her side while being thrown", maxDist < 30, "max distance=" + maxDist);
            MechTest.check("three slams for 34, invulnerable while holding him", slams == 3 && w.b.hp == World.BOSS_MAX_HP - 34 && w.p.hp == 100,
                           "slams=" + slams + " bossHp=" + w.b.hp + " hp=" + w.p.hp);
            World far = MechTest.quiet(34);
            far.p.x = 300; far.p.y = 380; far.b.x = 330; far.b.y = 380;
            far.commit(Arrays.asList(World.PlanItem.of(ActionType.THROW)));
            for (int i = 0; i < 90; i++) far.step();
            MechTest.check("smaller grab range: 30 px (old range caught it) is out of reach", far.b.hp == World.BOSS_MAX_HP, "bossHp=" + far.b.hp);
        }
        // every dodge has invincibility frames
        for (ActionType d : new ActionType[] { ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP, ActionType.APPARATE })
            MechTest.check(d.zh + " has i-frames", d.iframes() != null, "");
        // in the real game, a Death Eater's wind-up stops time
        {
            Game g = new Game(1.0);
            g.timer.stop();
            g.newGame();
            g.world.b.cooldown = 999; g.world.b.busy = 0; g.world.objs.clear();
            World.Obj de = MeleeTest.enemy(g.world, World.Kind.MINION, g.world.p.x + 120, g.world.p.y); de.b = 10; de.d = 1.0;
            g.execute();
            for (int i = 0; i < 120 && g.state == Game.State.RUN; i++) g.runFrame(1 / 60.0);
            MechTest.check("Death Eater attack does NOT open bullet time (only Voldemort / plan end do)", !g.planReason.contains("食死人"),
                           "state=" + g.state + " reason=" + g.planReason);
        }
        System.out.println(MechTest.fails == 0 ? "ENEMIES OK" : MechTest.fails + " FAILED");
        System.exit(0);
    }
}
