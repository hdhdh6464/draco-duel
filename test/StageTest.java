import java.util.Arrays;

/** Second stages: each lands as designed and keeps a way out. */
public class StageTest {
    public static void main(String[] args) throws Exception {
        // Avada follow-up: rolling back onto the old line is safe, sidestepping 34 px into the follow-up is not
        for (int mode = 0; mode < 2; mode++) {
            World w = MechTest.quiet(70);
            w.p.x = 300; w.p.y = 380; w.b.x = 600; w.b.y = 380;
            MechTest.spawn(w, World.Attack.AVADA);
            w.p.invuln = 0;
            // dodge the main beam with a roll along it, then either stay or step aside
            for (int i = 0; i < 54; i++) w.step();
            w.commit(Arrays.asList(World.PlanItem.move(ActionType.ROLL, 1, 0, ActionType.ROLL.steps)));
            for (int i = 0; i < 40; i++) w.step();
            if (mode == 1) w.commit(Arrays.asList(World.PlanItem.move(ActionType.RUN, 0, 1, 27)));
            for (int i = 0; i < 60; i++) w.step();
            MechTest.check(mode == 0 ? "Avada: stay on the old line -> follow-up misses" : "Avada: sidestep 34 px -> follow-up hits",
                           mode == 0 ? w.p.hp == 100 : w.over, "hp=" + w.p.hp);
        }
        // Crucio burst ring
        {
            World w = MechTest.quiet(71);
            w.b.x = 600; w.b.y = 380; w.p.x = 200; w.p.y = 380;
            MechTest.spawn(w, World.Attack.CRUCIO);
            int straight = 0;
            for (World.Obj o : w.objs) if (o.kind == World.Kind.CRUCIO && o.e == 1) straight++;
            MechTest.check("Crucio second stage: ring of straight orbs", straight == 10, "orbs=" + straight);
        }
        // fire serpent bursts into a ring when it burns out, but not when Stupefy disperses it
        for (boolean stun : new boolean[] { false, true }) {
            World w = MechTest.quiet(72);
            w.b.x = 600; w.b.y = 380; w.p.x = 200; w.p.y = 200;
            MechTest.spawn(w, World.Attack.FIRE_SERPENT);
            if (stun) {
                for (World.Obj o : w.objs) if (o.kind == World.Kind.SERPENT) o.delay = 0;
                w.step();
                for (World.Obj o : w.objs) if (o.kind == World.Kind.SERPENT) { o.hit = true; o.dead = true; }
            }
            boolean ring = false;
            for (int i = 0; i < 300; i++) { w.step(); for (World.Obj o : w.objs) if (o.kind == World.Kind.FIRE_RING && o.e == 1) ring = true; }
            MechTest.check(stun ? "dispersed serpent does not burst" : "burnt-out serpent bursts into a fire ring", ring != stun, "ring=" + ring);
        }
        // glass collapses back inward, with a gap
        {
            World w = MechTest.quiet(73);
            w.b.x = 450; w.b.y = 300; w.p.x = 300; w.p.y = 300;
            MechTest.spawn(w, World.Attack.GLASS);
            int inward = 0;
            for (World.Obj o : w.objs) if (o.kind == World.Kind.SHARD && o.e == 1) inward++;
            MechTest.check("Glass second stage: inward ring with a 4-shard gap", inward == 14, "shards=" + inward);
        }
        // Depulso shock ring and Smoke landing burst exist
        {
            World w = MechTest.quiet(74);
            w.b.x = 450; w.b.y = 300; w.p.x = 400; w.p.y = 300;
            MechTest.spawn(w, World.Attack.DEPULSO);
            int full = 0;
            for (World.Obj o : w.objs) if (o.kind == World.Kind.CONE && o.b > 3) full++;
            World s = MechTest.quiet(75);
            s.b.x = 450; s.b.y = 300; s.p.x = 300; s.p.y = 300;
            MechTest.spawn(s, World.Attack.SMOKE);
            int land = 0;
            for (World.Obj o : s.objs) if (o.kind == World.Kind.CONE && o.b > 3) land++;
            MechTest.check("Depulso ring and Smoke landing burst", full == 1 && land == 1, "depulso=" + full + " smoke=" + land);
        }
        System.out.println(MechTest.fails == 0 ? "STAGES OK" : MechTest.fails + " FAILED");
    }
}
