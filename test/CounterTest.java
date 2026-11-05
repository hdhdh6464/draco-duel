import java.util.Arrays;

/** Each spell cancels its own kind of Voldemort's ranged attacks, and only that kind. */
public class CounterTest {
    public static void main(String[] args) throws Exception {
        // Expelliarmus meets a charging killing curse: both are gone, Malfoy is untouched
        {
            World w = MechTest.quiet(51);
            w.p.x = 300; w.p.y = 380; w.b.x = 560; w.b.y = 380;
            MechTest.spawn(w, World.Attack.AVADA);
            w.p.stored[1] = true;
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.EXPELLIARMUS, true, 1, 0)));
            for (int i = 0; i < 120; i++) w.step();
            MechTest.check("Expelliarmus cancels Avada Kedavra", w.cancels == 1 && w.p.hp == 100, "cancels=" + w.cancels + " hp=" + w.p.hp);
        }
        // Stupefy cuts through a fire serpent's head
        {
            World w = MechTest.quiet(52);
            w.p.x = 300; w.p.y = 380; w.b.x = 520; w.b.y = 380;
            MechTest.spawn(w, World.Attack.FIRE_SERPENT);
            for (int i = 0; i < 60; i++) w.step();
            w.p.stored[2] = true;
            w.commit(Arrays.asList(World.PlanItem.release(ActionType.STUPEFY)));
            int serpents = 0;
            for (int i = 0; i < 90; i++) w.step();
            for (World.Obj o : w.objs) if (o.kind == World.Kind.SERPENT) serpents++;
            MechTest.check("Stupefy disperses the fire serpent", serpents == 0 && w.cancels >= 1, "serpents=" + serpents + " cancels=" + w.cancels);
        }
        // Densaugeo: one bolt only cracks a Death Eater's bolt, the second one cancels it
        for (int shots = 1; shots <= 2; shots++) {
            World w = MechTest.quiet(53);
            w.p.x = 300; w.p.y = 380; w.b.x = 700; w.b.y = 200;
            World.Obj s = new World.Obj();
            s.kind = World.Kind.M_BOLT; s.hostile = true; s.castId = -1; s.active = true; s.life = 4;
            s.x = 400; s.y = 380; s.vx = -60; s.vy = 0;
            w.objs.add(s);
            java.util.ArrayList<World.PlanItem> plan = new java.util.ArrayList<>();
            for (int i = 0; i < shots; i++) plan.add(World.PlanItem.aimed(ActionType.DENSAUGEO, false, 1, 0));
            plan.add(World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps));
            w.commit(plan);
            for (int i = 0; i < 40; i++) w.step();
            MechTest.check(shots == 1 ? "one Densaugeo does not cancel a bolt" : "two Densaugeo cancel a bolt",
                           shots == 1 ? w.cancels == 0 && !s.dead : w.cancels == 1 && s.dead, "cancels=" + w.cancels + " boltDead=" + s.dead);
        }
        // Densaugeo answers every barrage at two for one: a Crucio orb too
        for (int shots = 1; shots <= 2; shots++) {
            World w = MechTest.quiet(54);
            w.p.x = 300; w.p.y = 380; w.b.x = 700; w.b.y = 200;
            World.Obj s = MeleeTest.enemy(w, World.Kind.CRUCIO, 400, 380);
            s.e = 1; s.vx = -60; s.vy = 0; s.c = 60; s.life = 4;
            java.util.ArrayList<World.PlanItem> plan = new java.util.ArrayList<>();
            for (int i = 0; i < shots; i++) plan.add(World.PlanItem.aimed(ActionType.DENSAUGEO, false, 1, 0));
            plan.add(World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps));
            w.commit(plan);
            for (int i = 0; i < 40; i++) w.step();
            MechTest.check(shots == 1 ? "one Densaugeo only cracks a Crucio orb" : "two Densaugeo cancel a Crucio orb",
                           shots == 1 ? w.cancels == 0 && !s.dead : w.cancels == 1 && s.dead, "cancels=" + w.cancels + " orbDead=" + s.dead);
        }
        // the wrong spell does nothing: Densaugeo cannot stop the Killing Curse
        {
            World w = MechTest.quiet(55);
            w.p.x = 300; w.p.y = 380; w.b.x = 520; w.b.y = 380;
            MechTest.spawn(w, World.Attack.AVADA);
            for (int i = 0; i < 30; i++) w.step();
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.DENSAUGEO, false, 1, 0), World.PlanItem.aimed(ActionType.DENSAUGEO, false, 1, 0)));
            for (int i = 0; i < 30; i++) w.step();
            MechTest.check("Densaugeo does not cancel Avada Kedavra", w.cancels == 0, "cancels=" + w.cancels);
        }
        // the summoned snake intercepts Nagini's dash
        {
            int stopped = 0;
            for (int s = 0; s < 10; s++) {
                World w = MechTest.quiet(60 + s);
                w.p.x = 300; w.p.y = 380; w.b.x = 600; w.b.y = 380;
                w.p.stored[3] = true;
                MechTest.spawn(w, World.Attack.NAGINI);
                for (int i = 0; i < 2; i++) w.step();
                w.commit(Arrays.asList(World.PlanItem.release(ActionType.SERPENSORTIA)));
                for (int i = 0; i < 220; i++) w.step();
                if (w.cancels > 0) stopped++;
            }
            MechTest.check("Serpensortia's snake stops Nagini (most dashes)", stopped >= 5, stopped + "/10 dashes stopped");
        }
        // the player sends the snake to a spot on Nagini's lane: it waits there and stops her
        {
            int stopped = 0;
            for (int s = 0; s < 10; s++) {
                World w = MechTest.quiet(80 + s);
                w.p.x = 300; w.p.y = 380; w.b.x = 600; w.b.y = 380;
                w.p.stored[3] = true;
                MechTest.spawn(w, World.Attack.NAGINI);
                World.Obj lane = null;
                for (World.Obj o : w.objs) if (o.kind == World.Kind.NAGINI && (lane == null || o.delay < lane.delay)) lane = o;
                // the point of the first lane nearest Malfoy
                double t = (w.p.x - lane.x) * lane.vx + (w.p.y - lane.y) * lane.vy;
                double px = lane.x + lane.vx * t, py = lane.y + lane.vy * t;
                w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.SERPENSORTIA, true, px - w.p.x, py - w.p.y)));
                for (int i = 0; i < 220; i++) w.step();
                if (w.cancels > 0) stopped++;
            }
            MechTest.check("snake sent to Nagini's lane stops her", stopped >= 8, stopped + "/10");
        }
        // an aimed Stupefy flies straight and does not curve toward Voldemort
        {
            World w = MechTest.quiet(90);
            w.p.x = 300; w.p.y = 380; w.b.x = 300; w.b.y = 250;
            w.p.stored[2] = true;
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.STUPEFY, true, 1, 0)));
            double maxDy = 0;
            for (int i = 0; i < 60; i++) { w.step(); for (World.Obj o : w.objs) if (o.kind == World.Kind.P_STUP) maxDy = Math.max(maxDy, Math.abs(o.y - 380)); }
            MechTest.check("aimed Stupefy flies straight", maxDy < 1 && w.b.hp == World.BOSS_MAX_HP, "drift=" + maxDy);
        }
        // the snake waits in the path of a Fiendfyre ring and swallows it whole
        {
            World w = MechTest.quiet(91);
            w.p.x = 300; w.p.y = 380; w.b.x = 400; w.b.y = 380;
            w.p.stored[3] = true;
            MechTest.spawn(w, World.Attack.FIRE_RING);
            // put the snake between Voldemort and Malfoy, right where the first ring will sweep
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.SERPENSORTIA, true, 50, 0)));
            int rings0 = 0;
            for (World.Obj o : w.objs) if (o.kind == World.Kind.FIRE_RING) rings0++;
            for (int i = 0; i < 90; i++) w.step();
            MechTest.check("Serpensortia's snake swallows a fire ring", w.cancels >= 1, "cancels=" + w.cancels + " rings at start=" + rings0);
        }
        System.out.println(MechTest.fails == 0 ? "COUNTERS OK" : MechTest.fails + " FAILED");
    }
}
