import java.util.ArrayList;
import java.util.Random;

/** Random plan generator shared by the tests; tracks charges so it releases only what it stored. */
class Plans {
    static final ActionType[] POOL = { ActionType.RUN, ActionType.RUN, ActionType.RUN, ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP,
        ActionType.APPARATE, ActionType.WAIT, ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.STUPEFY, ActionType.SERPENSORTIA,
        ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.PROTEGO,
        ActionType.KICK, ActionType.FLURRY, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW, ActionType.THROW };
    static ArrayList<World.PlanItem> random(Random r, double room, World.Player from) {
        ArrayList<World.PlanItem> plan = new ArrayList<>();
        boolean[] stored = from.stored.clone();
        int roomSteps = (int) Math.floor(room * 60 + 1e-6), used = 0;
        int target = Math.min(roomSteps, 60 + r.nextInt(Math.max(1, roomSteps - 59)));
        while (used < target) {
            ActionType a = POOL[r.nextInt(POOL.length)];
            double an = r.nextDouble() * Math.PI * 2;
            World.PlanItem it;
            switch (a) {
                case RUN: it = World.PlanItem.move(a, Math.cos(an), Math.sin(an), 12 + r.nextInt(50), r.nextInt(4) == 0); break;
                case ROLL: it = World.PlanItem.move(a, Math.cos(an), Math.sin(an), a.steps); break;
                case DASH: it = new World.PlanItem(a, r.nextBoolean() ? 1 : -1, 0, a.steps, false, false, 0, 0); break;
                case APPARATE: it = World.PlanItem.apparate(40 + r.nextInt(50), an); break;
                default:
                    if (a.isSpell() && stored[a.spellIndex()]) { it = World.PlanItem.release(a); stored[a.spellIndex()] = false; }
                    else { it = World.PlanItem.of(a); if (a.isSpell()) stored[a.spellIndex()] = true; }
            }
            if (used + it.steps > roomSteps) break;
            plan.add(it); used += it.steps;
        }
        if (plan.isEmpty()) plan.add(World.PlanItem.of(ActionType.WAIT));
        return plan;
    }
}
