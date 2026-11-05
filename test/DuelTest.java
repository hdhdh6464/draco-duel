import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Two-player duel rules, without a screen. */
public class DuelTest {
    static int fails = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + "  (" + info + ")");
        if (!ok) fails++;
    }

    /** Both players on one line, facing each other, 300 px apart. */
    static World duel() {
        World w = new World(5, true);
        w.ps[0].x = 250; w.ps[0].y = 240;
        w.ps[1].x = 550; w.ps[1].y = 240;
        w.objs.clear();
        return w;
    }

    static void plan(World w, int side, World.PlanItem... items) {
        int keep = w.me;
        w.setMe(side);
        w.commit(Arrays.asList(items));
        w.setMe(keep);
    }

    /** Charge then release a spell straight along (dx, dy). */
    static World.PlanItem[] cast(ActionType a, double dx, double dy) {
        if (!a.charged()) return new World.PlanItem[] { World.PlanItem.aimed(a, false, dx, dy) };
        return new World.PlanItem[] { World.PlanItem.aimed(a, false, dx, dy), World.PlanItem.aimed(a, true, dx, dy) };
    }

    static World.PlanItem[] cat(World.PlanItem[]... parts) {
        List<World.PlanItem> l = new ArrayList<>();
        for (World.PlanItem[] p : parts) l.addAll(Arrays.asList(p));
        return l.toArray(new World.PlanItem[0]);
    }

    static World.PlanItem waitFor(double secs) { return new World.PlanItem(ActionType.WAIT, 0, 0, (int) Math.round(secs * 60), false, false, 0, 0); }

    static void run(World w, double secs) { for (int i = 0; i < secs * 60 && !w.over; i++) w.step(); }

    public static void main(String[] args) {
        {
            World w = duel();
            plan(w, 0, cast(ActionType.EXPELLIARMUS, 1, 0));
            run(w, 4);
            check("a spell that lands is a knockout", w.over && w.winner == 0 && w.ps[1].ko, "over=" + w.over + " winner=" + w.winner);
        }
        {
            World w = duel();
            plan(w, 0, cast(ActionType.EXPELLIARMUS, 1, 0));
            plan(w, 1, waitFor(0.9), World.PlanItem.of(ActionType.PROTEGO), World.PlanItem.of(ActionType.PROTEGO));
            run(w, 5);
            check("Protego bounces it back onto the caster", w.over && w.winner == 1 && w.ps[0].ko, "over=" + w.over + " winner=" + w.winner);
        }
        {
            World w = duel();
            plan(w, 0, cast(ActionType.EXPELLIARMUS, 1, 0));
            plan(w, 1, cast(ActionType.EXPELLIARMUS, -1, 0));
            run(w, 5);
            check("two Expelliarmus (2 vs 2) both burst", !w.over && w.cancels == 1, "over=" + w.over + " cancels=" + w.cancels);
        }
        {
            World w = duel();
            plan(w, 0, cast(ActionType.DENSAUGEO, 1, 0));
            plan(w, 1, cast(ActionType.STUPEFY, -1, 0));
            int maxCv = 0;
            for (int i = 0; i < 300 && !w.over; i++) {
                w.step();
                for (World.Obj o : w.objs) if (o.kind == World.Kind.P_STUP) maxCv = o.cv;
            }
            check("Stupefy (3) eats a Densaugeo (1) and flies on with 2", w.over && w.winner == 1 && w.cancels == 1 && maxCv == 2,
                  "winner=" + w.winner + " cancels=" + w.cancels + " stupefyCv=" + maxCv);
        }
        {
            World w = duel();
            plan(w, 0, cast(ActionType.EXPELLIARMUS, 1, 0));
            // the bolt takes 0.6 s to read plus ~0.85 s to fly 270 px: roll across its path as it arrives
            plan(w, 1, waitFor(1.25), World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps));
            run(w, 5);
            check("a roll dodges the spell", !w.over, "over=" + w.over + " hp1=" + w.ps[1].hp);
        }
        {
            World w = duel();
            w.ps[1].x = 268;
            plan(w, 0, World.PlanItem.of(ActionType.FLURRY));
            plan(w, 1, waitFor(3));
            run(w, 1.2);
            check("flurry: 8 damage, the plan is lost", w.ps[1].hp == World.PVP_HP - 8 && w.ps[1].queue.isEmpty(), "hp1=" + w.ps[1].hp + " queue=" + w.ps[1].queue.size());
        }
        {
            World w = duel();
            w.ps[1].x = 264;
            plan(w, 0, World.PlanItem.of(ActionType.THROW));
            boolean carried = false;
            double maxLift = 0;
            for (int i = 0; i < 90; i++) { w.step(); carried |= w.ps[1].carried; maxLift = Math.max(maxLift, w.ps[1].lift); }
            check("shoulder throw on a player: lifted, 34 damage, then on the floor", carried && maxLift > 10 && w.ps[1].hp == World.PVP_HP - 34 && w.ps[1].downT > 0,
                  "carried=" + carried + " lift=" + maxLift + " hp1=" + w.ps[1].hp + " downT=" + w.ps[1].downT);
            run(w, 1.5);
            check("…and back on their feet afterwards", !w.ps[1].carried && w.ps[1].downT == 0 && w.ps[1].rot == 0, "downT=" + w.ps[1].downT);
        }
        {
            World w = duel();
            w.ps[1].x = 268;
            plan(w, 0, World.PlanItem.of(ActionType.THROW), World.PlanItem.of(ActionType.FLURRY), World.PlanItem.of(ActionType.SPIN));
            plan(w, 1, waitFor(6));
            run(w, 6);
            check("health runs out in close combat: a knockout", w.over && w.winner == 0 && w.ps[1].hp == 0, "hp1=" + w.ps[1].hp + " over=" + w.over);
        }
        {
            World a = duel();
            plan(a, 0, cat(cast(ActionType.STUPEFY, 1, 0.2), new World.PlanItem[] { World.PlanItem.move(ActionType.RUN, 0, 1, 40) }));
            plan(a, 1, cat(new World.PlanItem[] { World.PlanItem.move(ActionType.ROLL, 0, -1, ActionType.ROLL.steps) }, cast(ActionType.DENSAUGEO, -1, 0)));
            World b = a.copy();
            run(a, 4); run(b, 4);
            boolean same = a.steps == b.steps && a.ps[0].x == b.ps[0].x && a.ps[1].y == b.ps[1].y && a.over == b.over && a.objs.size() == b.objs.size();
            check("a copy plays out exactly the same", same, "steps " + a.steps + "/" + b.steps);
        }
        {
            World w = duel();
            plan(w, 1, World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps));
            w.step();
            check("a roll spends 25 stamina", Math.abs(w.ps[1].sta - (World.MAX_STA - 25)) < 0.5, "sta=" + w.ps[1].sta);
            w.ps[1].sta = 10;
            w.ps[1].cd[ActionType.ROLL.ordinal()] = 0;
            int f = w.fizzles;
            w.ps[1].cur = null;
            plan(w, 1, World.PlanItem.move(ActionType.ROLL, 0, 1, ActionType.ROLL.steps));
            w.step();
            check("…and without enough stamina it fizzles", w.fizzles == f + 1 && w.lastFizzle.equals(Lang.t("體力不足", "Not enough stamina")), "fizzle=" + w.lastFizzle);
        }
        {
            World w = duel();
            plan(w, 0, World.PlanItem.of(ActionType.PROTEGO));
            w.step();
            check("Protego costs 40 mana in a duel", Math.abs(w.ps[0].mana - (World.MAX_MANA - 40)) < 0.5, "mana=" + w.ps[0].mana);
        }
        {
            World w = duel();
            w.ps[1].x = 250; w.ps[1].y = 420;
            plan(w, 0, cat(cast(ActionType.EXPELLIARMUS, 0, -1), new World.PlanItem[] { World.PlanItem.aimed(ActionType.EXPELLIARMUS, false, 0, -1) }));
            int f = w.fizzles;
            run(w, 1.4);
            check("after a release Expelliarmus needs 1 s to recover before the next charge", w.fizzles == f + 1 && w.lastFizzle.equals(Lang.t("冷卻中", "On cooldown")),
                  "fizzle=" + w.lastFizzle + " mana=" + w.ps[0].mana);
        }
        {
            World w = duel();
            plan(w, 0, cast(ActionType.STUPEFY, 1, 0));
            double v = 0;
            for (int i = 0; i < 80; i++) { w.step(); for (World.Obj o : w.objs) if (o.kind == World.Kind.P_STUP) v = Math.hypot(o.vx, o.vy); }
            check("a bolt flies at the speed on its card (Stupefy 2.4 per 0.01 s)", Math.abs(v * 0.01 - ActionType.STUPEFY.speed() * 0.01) < 1e-6 && Math.abs(v - 240) < 1e-6, "v=" + v);
        }
        System.out.println(fails == 0 ? "DUEL OK" : fails + " FAILED");
    }
}
