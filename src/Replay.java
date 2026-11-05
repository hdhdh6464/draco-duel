import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded duel. The simulation is deterministic, so a seed plus every player input, stamped
 * with the step it happened before, replays the whole fight exactly; no frames are stored.
 */
final class Replay {
    static final int PLAN = 0, MOVE = 1, ORDER = 2, BOSS = 3;

    static final class Input {
        final int at, type;
        final List<World.PlanItem> queue, plan;
        final double cover, dx, dy;
        final boolean walk;
        final World.PlanItem item;
        /** Two-player duel: whose plan this is. */
        int side;
        /** Second-player Voldemort: the spell chosen (ordinal, -1 none) and whether he apparates. */
        int attack = -1;
        boolean blink;
        Input(int at, int type, List<World.PlanItem> queue, List<World.PlanItem> plan, double cover,
              double dx, double dy, boolean walk, World.PlanItem item) {
            this.at = at; this.type = type; this.queue = queue; this.plan = plan; this.cover = cover;
            this.dx = dx; this.dy = dy; this.walk = walk; this.item = item;
        }
    }

    final long seed;
    final boolean pvp;
    /** Voldemort played by a second player. */
    boolean bossHuman;
    /** Two-player duel: the duel as it stood when the first plans were sent (nothing moves before that). */
    World start;
    final ArrayList<Input> inputs = new ArrayList<>();
    int endStep;
    private double lastDx, lastDy;
    private boolean lastWalk;

    Replay(long seed) { this(seed, false); }
    Replay(long seed, boolean pvp) { this.seed = seed; this.pvp = pvp; }

    /** A bullet-time plan: the queue as edited (undo / clear) and the new plan committed on top. */
    void plan(World w, List<World.PlanItem> draft, double cover) {
        inputs.add(new Input(w.steps, PLAN, new ArrayList<>(w.p.queue), new ArrayList<>(draft), cover, 0, 0, false, null));
    }

    /** Two-player duel: one side's plan for the round, committed just before both play out. */
    void plan(World w, int side, List<World.PlanItem> draft) {
        Input in = new Input(w.steps, PLAN, new ArrayList<>(w.ps[side].queue), new ArrayList<>(draft), 0, 0, 0, false, null);
        in.side = side;
        inputs.add(in);
    }

    /** Free-movement keys; only changes are stored. */
    void move(World w, double dx, double dy, boolean walk) {
        if (dx == lastDx && dy == lastDy && walk == lastWalk) return;
        lastDx = dx; lastDy = dy; lastWalk = walk;
        inputs.add(new Input(w.steps, MOVE, null, null, 0, dx, dy, walk, null));
    }

    /** Second-player Voldemort: what he holds and chooses right now (stored on every change). */
    void boss(World w) {
        Input in = new Input(w.steps, BOSS, null, null, 0, w.bossDx, w.bossDy, false, null);
        in.attack = w.bossOrder == null ? -1 : w.bossOrder.ordinal();
        in.blink = w.bossBlink;
        inputs.add(in);
    }

    /** A click-to-move order given in real time. */
    void order(World w, World.PlanItem it) {
        inputs.add(new Input(w.steps, ORDER, null, null, 0, 0, 0, false, it));
    }

    /** Plays a recording back; {@link #advance} feeds the inputs due before each step. */
    static final class Player {
        final Replay rec;
        World world;
        int next;
        Player(Replay rec) { this.rec = rec; world = fresh(); }

        World fresh() {
            if (rec.start != null) return rec.start.copy();
            if (rec.pvp) return new World(rec.seed, true);
            World w = new World(rec.seed);
            w.bossHuman = rec.bossHuman;
            return w;
        }

        /** Applies the inputs due now and runs one step; returns false once the recording is over. */
        boolean advance() {
            if (world.steps >= rec.endStep || world.over) return false;
            while (next < rec.inputs.size() && rec.inputs.get(next).at <= world.steps) {
                Input in = rec.inputs.get(next++);
                switch (in.type) {
                    case PLAN:
                        if (rec.pvp) world.setMe(in.side);
                        world.p.queue = new ArrayDeque<>(in.queue);
                        world.commit(in.plan, in.cover);
                        break;
                    case MOVE:
                        world.p.freeDx = in.dx; world.p.freeDy = in.dy; world.p.walk = in.walk;
                        break;
                    case BOSS:
                        world.bossDx = in.dx; world.bossDy = in.dy; world.bossBlink = in.blink;
                        world.bossOrder = in.attack < 0 ? null : World.Attack.values()[in.attack];
                        break;
                    default:
                        world.order(in.item);
                        break;
                }
            }
            world.step();
            return true;
        }

        /** Rebuilds the duel from the start up to {@code step} (fast: the simulation is cheap). */
        void seek(int step) {
            world = fresh();
            next = 0;
            while (world.steps < step && advance()) { }
        }
    }
}
