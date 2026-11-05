import java.util.ArrayList;
import java.util.Arrays;

/** Light attack without cooldown, and spells aimed by hand. */
public class AimTest {
    public static void main(String[] args) throws Exception {
        // the light attack: no chant, no cooldown, 12 mana a shot; it runs dry on mana alone
        {
            World w = MechTest.quiet(21);
            w.p.x = 300; w.p.y = 380; w.b.x = 420; w.b.y = 380;
            java.util.ArrayList<World.PlanItem> plan = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) plan.add(World.PlanItem.aimed(ActionType.DENSAUGEO, false, 0, 0));
            w.commit(plan);
            int bolts = 0;
            for (int i = 0; i < 160; i++) { w.step(); for (World.Ev e : w.ev) if (e.type == World.EvType.RELEASE) bolts++; }
            MechTest.check("5 light attacks back to back, no cooldown", bolts == 5 && w.fizzles == 0 && w.b.hp == World.BOSS_MAX_HP - 20,
                           "bolts=" + bolts + " fizzles=" + w.fizzles + " bossHp=" + w.b.hp);
            World w3 = MechTest.quiet(21);
            w3.p.mana = 24;
            java.util.ArrayList<World.PlanItem> burst = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) burst.add(World.PlanItem.aimed(ActionType.DENSAUGEO, false, 0, 0));
            w3.commit(burst);
            for (int i = 0; i < 80; i++) w3.step();
            MechTest.check("light attack runs dry on mana", w3.fizzles == 1 && "魔力不足".equals(w3.lastFizzle), "fizzles=" + w3.fizzles + " " + w3.lastFizzle);
        }
        // aimed away from Voldemort: flies straight left, never curves back to him
        {
            World w = MechTest.quiet(22);
            w.p.x = 400; w.p.y = 380; w.b.x = 470; w.b.y = 380;
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.DENSAUGEO, false, -1, 0)));
            double minX = 1e9;
            for (int i = 0; i < 80; i++) { w.step(); for (World.Obj o : w.objs) if (o.kind == World.Kind.P_DENS) minX = Math.min(minX, o.x); }
            MechTest.check("aimed left flies left and misses", minX < 250 && w.b.hp == World.BOSS_MAX_HP, "minX=" + minX + " bossHp=" + w.b.hp);
        }
        // aimed at a Death Eater although Voldemort is closer: the Death Eater is hit
        {
            World w = MechTest.quiet(23);
            w.p.x = 240; w.p.y = 380; w.b.x = 300; w.b.y = 380;
            World.Obj de = MeleeTest.enemy(w, World.Kind.MINION, 240, 260);
            de.b = 30;
            de.a = 5;   // held in a flinch so it stands still: a hand-aimed spell does not chase
            w.p.stored[2] = true;
            w.commit(Arrays.asList(World.PlanItem.aimed(ActionType.STUPEFY, true, 0, -1)));
            for (int i = 0; i < 60; i++) w.step();
            MechTest.check("aimed Stupefy hits the Death Eater, not nearer Voldemort", de.b == 18 && w.b.hp == World.BOSS_MAX_HP,
                           "deHp=" + de.b + " bossHp=" + w.b.hp);
        }
        System.out.println(MechTest.fails == 0 ? "AIM OK" : MechTest.fails + " FAILED");
    }
}
