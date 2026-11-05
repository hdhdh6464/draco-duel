import java.util.HashSet;

/** Malfoy's starting spot varies with the seed, is always valid, and is reproducible. */
public class SpawnTest {
    public static void main(String[] args) {
        HashSet<String> spots = new HashSet<>();
        int bad = 0;
        for (int s = 0; s < 200; s++) {
            World w = new World(s);
            spots.add((int) w.p.x + "," + (int) w.p.y);
            boolean ok = !World.inObstacle(w.p.x, w.p.y, 14) && w.p.x >= World.MIN_X + 40 && w.p.x <= World.MAX_X - 40
                         && w.p.y >= World.MIN_Y + 30 && w.p.y <= World.MAX_Y - 30 && Math.hypot(w.p.x - w.b.x, w.p.y - w.b.y) >= 220;
            if (!ok) bad++;
            World again = new World(s);
            if (again.p.x != w.p.x || again.p.y != w.p.y) bad++;
        }
        MechTest.check("200 seeds give varied starts", spots.size() > 190, spots.size() + " distinct spots");
        MechTest.check("every start is valid and reproducible", bad == 0, "bad=" + bad);
        System.out.println(MechTest.fails == 0 ? "SPAWN OK" : MechTest.fails + " FAILED");
    }
}
