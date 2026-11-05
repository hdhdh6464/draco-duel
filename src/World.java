import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole duel as a deterministic fixed-step simulation on a top-down map. Nothing here reads
 * the clock or Math.random, so a copy() stepped forward shows exactly what the real duel will do:
 * that is what powers the ghost preview during bullet time.
 */
final class World {
    static final double DT = 1.0 / 60.0;
    static final int MAPW = 860, MAPH = 540;
    static final double MIN_X = 18, MAX_X = 842, MIN_Y = 60, MAX_Y = 530;
    static final double RUN_SPEED = 75, ROLL_DIST = 56, BODY_R = 5;
    static final int BOSS_MAX_HP = 320, PLAYER_MAX_HP = 100;
    /** Two-player duel: health only counts for close combat (any spell that lands is a knockout). */
    static final int PVP_HP = 50;
    /** Two-player duel: spell hit radius, spell-on-spell clash radius, and how much farther spells fly. */
    static final double PVP_HIT_R = 14, PVP_CLASH_R = 10, PVP_LIFE = 1.4;
    static final double MAX_MANA = 100, MANA_REGEN = 12;
    /** Two-player duel: stamina for dodges and close combat. */
    static final double MAX_STA = 100, STA_REGEN = 12;
    /** Voldemort's next wave comes this long after the last one ends (no pause); a bullet time covers his wave. */
    static final double POST_CAST = 0.0, MAX_PLAN = 8.0;
    /** If Malfoy stays this close for this long without landing a hit, Voldemort apparates away. */
    static final double CROWD_RANGE = 60, CROWD_TIME = 2.0;
    static final double CLOSE_RANGE = 100, FAR_RANGE = 220;

    /** Static scenery of the Ministry Atrium: {x, y, radius}. The fountain first, then the pillars. */
    static final double[][] OBST = {
        { 430, 300, 26 },
        { 180, 170, 9 }, { 680, 170, 9 }, { 180, 440, 9 }, { 680, 440, 9 },
        { 430, 150, 9 }, { 430, 460, 9 }, { 300, 305, 9 }, { 560, 305, 9 },
    };

    enum Attack {
        AVADA("索命咒", "Avada Kedavra", "Avada Kedavra"),
        CRUCIO("酷刑咒", "Crucio", "Cruciatus Curse"),
        FIRE_SERPENT("火焰巨蛇", "Serpens Ignis", "Fire Serpent"),
        MORSMORDRE("黑魔標記", "Morsmordre", "Dark Mark"),
        NAGINI("娜吉妮", "Nagini", "Nagini"),
        SMOKE("黑煙突襲", "Umbra Volatus", "Smoke Rush"),
        GLASS("碎玻璃風暴", "Vitrum Tempestas", "Glass Storm"),
        FIRE_RING("厲火之環", "Fiendfyre", "Fiendfyre Ring"),
        INFERI("陰屍", "Inferi", "Inferi"),
        DEPULSO("震退咒", "Depulso", "Depulso"),
        SUMMON("召喚食死人", "Death Eaters", "Summon Death Eaters");
        final String zh, latin, en;
        Attack(String zh, String latin, String en) { this.zh = zh; this.latin = latin; this.en = en; }
        String label() { return Lang.t(zh, en); }
    }

    enum Kind { AVADA, CRUCIO, SERPENT, FIRE_TILE, MARK, DARK_MARK, NAGINI, SMOKE, SHARD, FIRE_RING, INFERI, CONE, MINION, M_BOLT,
                P_DENS, P_EXP, P_STUP, P_REFLECT, P_SNAKE }

    enum EvType { CAST_START, SCORE, HIT, BLOCK, BOSS_HIT, RELEASE, INTERRUPT, APPARATE, STRIKE,
                  PHASE2, HEAL, FIZZLE, KEY_POSE, STUN, MELEE, THROW, STORED, CANCEL, IMPACT, RETREAT,
                  /** a summoned enemy starts winding up an attack: bullet time, just like Voldemort's spells */
                  ENEMY_ATTACK, WIN, LOSE }
    /** No more than this many of any one kind of summoned enemy at a time. */
    static final int MAX_PER_KIND = 3;
    /** Voldemort's wind-ups and waves run at this fraction of their base length. */
    static final double CAST_PACE_P1 = 1.0, CAST_PACE_P2 = 1.0;

    static final class Ev {
        final EvType type; final double x, y; final String text; final int amount;
        Ev(EvType type, double x, double y, String text, int amount) {
            this.type = type; this.x = x; this.y = y; this.text = text; this.amount = amount;
        }
    }

    /**
     * One planned action: a type, a direction for moves, and its length in steps. Immutable.
     * {@code alt} means "walk" for a move and "release the stored charge" for a spell.
     */
    static final class PlanItem {
        final ActionType type; final double dx, dy; final int steps; final boolean alt, last; final double segLen;
        /** Hit count when committed: a segment only earns its bonus if Malfoy was not hit since. */
        final int epoch;
        PlanItem(ActionType type, double dx, double dy, int steps, boolean alt, boolean last, double segLen, int epoch) {
            this.type = type; this.dx = dx; this.dy = dy; this.steps = steps; this.alt = alt;
            this.last = last; this.segLen = segLen; this.epoch = epoch;
        }
        static PlanItem of(ActionType t) { return new PlanItem(t, 0, 0, t.steps, false, false, 0, 0); }
        static PlanItem release(ActionType spell) { return aimed(spell, true, 0, 0); }
        /** A spell order: the light attack, a charge, or (release) a stored spell; dx,dy = aim, or 0,0 to auto-target. */
        static PlanItem aimed(ActionType spell, boolean release, double dx, double dy) {
            double len = Math.hypot(dx, dy);
            if (len <= 1e-9) { dx = 0; dy = 0; }
            else if (spell == ActionType.SERPENSORTIA) { double k = Math.min(1, 400 / len); dx *= k; dy *= k; }
            else { dx /= len; dy /= len; }
            return new PlanItem(spell, dx, dy, release ? ActionType.RELEASE_STEPS : spell.steps, release, false, 0, 0);
        }
        /** Apparition stores its distance in dx and its heading (radians) in dy. */
        static PlanItem apparate(double dist, double angle) { return new PlanItem(ActionType.APPARATE, dist, angle, ActionType.APPARATE.steps, false, false, 0, 0); }
        static PlanItem move(ActionType t, double dx, double dy, int steps) { return move(t, dx, dy, steps, false); }
        static PlanItem move(ActionType t, double dx, double dy, int steps, boolean walk) {
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) { dx = 1; dy = 0; len = 1; }
            return new PlanItem(t, dx / len, dy / len, steps, walk, false, 0, 0);
        }
        double dur() { return steps * DT; }
        boolean isRelease() { return alt && type.charged(); }
        boolean aimed() { return type.isSpell() && (dx != 0 || dy != 0); }
    }

    static final class Player implements Cloneable {
        double x = 150, y = 300, hp = PLAYER_MAX_HP, mana = MAX_MANA, invuln, hurtFlash;
        int face = 1;
        PlanItem cur; int step; boolean fizzled, missed;
        /** Direction of the current move, fixed when it starts. */
        double mdx, mdy;
        ArrayDeque<PlanItem> queue = new ArrayDeque<>();
        int combo, same, hitEpoch; ActionType lastType;
        double[] cd = new double[ActionType.values().length];
        boolean[] stored = new boolean[ActionType.SPELLS.length];
        // real-time control between Voldemort's spells
        double freeDx, freeDy; boolean walk; int freeAnim;
        // shoulder throw: Voldemort is carried from (gx0,gy0) over her head to (gx1,gy1)
        boolean grabbed; double gx0, gy0, gx1, gy1;
        // two-player duel: which side this is, and being thrown (carried, lifted, turned) or lying on the floor
        int side; boolean carried, ko; double lift, rot, downT, sta = MAX_STA;

        Player copy() {
            try {
                Player o = (Player) clone();
                o.queue = new ArrayDeque<>(queue); o.cd = cd.clone(); o.stored = stored.clone();
                return o;
            } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
        ActionType act() { return cur == null ? null : cur.type; }
        double progress() { return cur == null ? 0 : step / (double) cur.steps; }
        boolean is(ActionType a) { return act() == a && !fizzled; }
        boolean idle() { return cur == null && queue.isEmpty(); }
        /** A plan committed in bullet time is still playing out (real-time click-moves do not count). */
        boolean planActive() {
            if (cur != null && cur.segLen > 0) return true;
            for (PlanItem it : queue) if (it.segLen > 0) return true;
            return false;
        }
        boolean charging() { return cur != null && !fizzled && cur.type.charged() && !cur.alt; }
        double z() {
            if (carried || lift > 0) return lift;
            if (is(ActionType.KICK)) return step < 6 || step > 34 ? 0 : 28 * Math.sin(Math.PI * (step - 6) / 28.0);
            if (!is(ActionType.BACKFLIP)) return 0;
            double p = progress();
            return 20 * 4 * p * (1 - p);
        }
        boolean airborne() { int[] a = cur == null || fizzled ? null : cur.type.airtime(); return a != null && step >= a[0] && step <= a[1]; }
        boolean dodgeInvuln() {
            int[] a = cur == null || fizzled ? null : cur.type.iframes();
            if (a == null || step < a[0] || step > a[1]) return false;
            return cur.type != ActionType.THROW || grabbed;
        }
        boolean vanished() { return is(ActionType.APPARATE) && step >= 3 && step <= 18; }
        boolean shield() { return is(ActionType.PROTEGO) && step >= 6; }
        double wandX() { return x + 9 * face; }
    }

    static final class Boss implements Cloneable {
        double x = 710, y = 300, z, hp = BOSS_MAX_HP;
        /** Hit reaction: seconds left and the direction he is knocked back in (visual only). */
        double hitT, hitDx, hitDy;
        /** Shoulder throw (visual only): his tilt in degrees, and how long he stays down on the floor at which angle. */
        double rot, downT, downAng;
        /** How long Malfoy has been crowding him without landing a blow. */
        double closeT;
        int phase = 1; double cooldown = DT / 2, busy, stun, hitFlash, castTime = -99;
        boolean hidden; int castId; Attack last; String castName = "";
        /** Second-player Voldemort: seconds until he can apparate again. */
        double blinkCd;
        Boss copy() {
            try { return (Boss) clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
    }

    /** Every hazard and projectile. Generic slots a..e are documented per kind where they are set. */
    static final class Obj implements Cloneable {
        Kind kind; int castId; boolean hostile;
        double x, y, vx, vy, t, delay, life = 99, a, b, c, d, e;
        int idx, stage;
        /** Densaugeo hits taken: it takes two of them to cancel one projectile. */
        int chips;
        /** Which of Malfoy's shoulder throws last crashed Voldemort's body into this servant (1-based; 0 = none). */
        int thrownBy;
        boolean active, dead, hit, grazed, perfect;
        /** Two-player duel: who cast it, and how much clashing it can take before it bursts. */
        int owner, cv;
        Obj copy() {
            try { return (Obj) clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
    }

    double time; int steps;
    /**
     * Voldemort played by a second player: he only acts in his turn (Malfoy's plan has played out and her spells
     * have landed), floating about, apparating and casting the spell chosen; he never gets bullet time.
     * bossDx/bossDy = held direction, bossOrder = the spell chosen (cast as soon as it is allowed), bossBlink = apparate.
     */
    boolean bossHuman;
    /** Tutorial: hits stagger Malfoy and show in the preview as usual, but she never falls. */
    boolean practice;
    double bossDx, bossDy;
    Attack bossOrder;
    boolean bossBlink;
    static final double BOSS_FLOAT = 70, BLINK_DIST = 150, BLINK_CD = 3.0;
    Player p = new Player();
    Boss b = new Boss();
    ArrayList<Obj> objs = new ArrayList<>();
    Rng rng;
    int score, fizzles, maxCombo, hitsTaken, interrupts, cancels;
    double longestPlan;
    boolean over, won;
    /** Why the most recent order fizzled, for the planner's message. */
    String lastFizzle = "";
    ArrayList<Ev> ev = new ArrayList<>();

    /** Two-player duel: both players; p is the one acting (or being shown), q the other. */
    boolean pvp;
    Player[] ps;
    Player q;
    /** The side the screen follows, and the winner (-1 = none yet or a draw). */
    int me, winner = -1;

    World(long seed) {
        rng = new Rng(seed);
        placeStart();
    }

    /** A two-player duel in the Atrium: no Voldemort, two wizards on opposite sides of the fountain. */
    World(long seed, boolean twoPlayer) {
        this(seed);
        if (!twoPlayer) return;
        pvp = true;
        Player a = new Player(), c = new Player();
        a.side = 0; c.side = 1;
        a.hp = c.hp = PVP_HP;
        double y = MIN_Y + 60 + rng.nextDouble() * (MAX_Y - MIN_Y - 120);
        a.x = 190; a.y = y; a.face = 1;
        c.x = MAPW - 190; c.y = MAPH - y; c.face = -1;
        ps = new Player[] { a, c };
        b.hidden = true; b.x = -9999; b.y = -9999;
        setMe(0);
    }

    /** Points p (and the screen) at one side and q at the other. */
    void setMe(int side) { me = side; p = ps[side]; q = ps[1 - side]; }

    /**
     * Malfoy starts somewhere new each duel: clear of the scenery and the walls, and far enough from
     * Voldemort that the opening is not already a brawl. Seeded, so a replay starts in the same spot.
     */
    private void placeStart() {
        for (int tries = 0; tries < 200; tries++) {
            double x = MIN_X + 40 + rng.nextDouble() * (MAX_X - MIN_X - 80);
            double y = MIN_Y + 30 + rng.nextDouble() * (MAX_Y - MIN_Y - 60);
            if (inObstacle(x, y, 14) || Math.hypot(x - b.x, y - b.y) < 220) continue;
            p.x = x; p.y = y;
            p.face = b.x >= x ? 1 : -1;
            return;
        }
    }

    World copy() {
        World w = new World(0);
        w.time = time; w.steps = steps; w.p = p.copy(); w.b = b.copy();
        if (pvp) {
            w.pvp = true;
            w.ps = new Player[] { ps[0].copy(), ps[1].copy() };
            w.winner = winner;
            w.setMe(me);
        }
        w.objs.ensureCapacity(objs.size());
        for (Obj o : objs) w.objs.add(o.copy());
        w.rng = new Rng(rng);
        w.score = score; w.fizzles = fizzles; w.maxCombo = maxCombo; w.hitsTaken = hitsTaken; w.cancels = cancels;
        w.interrupts = interrupts; w.longestPlan = longestPlan; w.over = over; w.won = won; w.lastFizzle = lastFizzle;
        w.idleT = idleT;
        w.practice = practice;
        w.bossHuman = bossHuman; w.bossDx = bossDx; w.bossDy = bossDy; w.bossOrder = bossOrder; w.bossBlink = bossBlink;
        return w;
    }

    // ---------------------------------------------------------------- planning

    double remainingPlan() {
        int s = p.cur == null ? 0 : p.cur.steps - p.step;
        for (PlanItem it : p.queue) s += it.steps;
        return s * DT;
    }

    void commit(List<PlanItem> plan) { commit(plan, 0); }

    /** Queues a plan; leaving any of the first {@code mustCover} seconds (his attack) unplanned costs points. */
    void commit(List<PlanItem> plan, double mustCover) {
        int stepsTotal = 0;
        for (PlanItem it : plan) stepsTotal += it.steps;
        double len = stepsTotal * DT;
        double gap = mustCover - (remainingPlan() + len);
        for (int i = 0; i < plan.size(); i++) {
            PlanItem it = plan.get(i);
            p.queue.add(new PlanItem(it.type, it.dx, it.dy, it.steps, it.alt, i == plan.size() - 1, len, p.hitEpoch));
        }
        longestPlan = Math.max(longestPlan, len);
        if (gap > 0.5) addScore(-(int) Math.round(gap * 20), String.format(Lang.t("留白 %.1fs", "Idle %.1fs"), gap), p.x, p.y - 34);
    }

    /** A real-time order during free movement: runs at once if Malfoy is not already doing something. */
    boolean order(PlanItem it) {
        if (!p.idle() || over) return false;
        p.queue.add(new PlanItem(it.type, it.dx, it.dy, it.steps, it.alt, false, 0, p.hitEpoch));
        return true;
    }

    /** The faster Voldemort falls, the bigger the bonus: 60 points for every second under four minutes. */
    static final double PAR_TIME = 240;
    static int timeBonus(double seconds) { return (int) Math.round(Math.max(0, PAR_TIME - seconds) * 60); }

    /** Longest plan a cast that just started allows: the rest of his attack plus the gap before the next wave. */
    double bulletWindow() { return Math.min(MAX_PLAN, Math.max(0, b.busy) + Math.max(0, b.stun) + POST_CAST); }

    // ---------------------------------------------------------------- simulation

    /** Longest a plan's spells may keep flying after her last action before the next bullet time. */
    static final double RESOLVE_MAX = 1.0;
    /** Seconds she has stood idle since her last action. */
    double idleT;

    /**
     * True while the plan is done but spells she cast are still in flight: they get to land (for up to a second)
     * before the next bullet time, so a plan's results are seen through. Her waiting snake does not count.
     */
    boolean resolving() {
        if (!p.idle() || idleT >= RESOLVE_MAX) return false;
        for (Obj o : objs)
            if (!o.dead && (o.kind == Kind.P_DENS || o.kind == Kind.P_EXP || o.kind == Kind.P_STUP || o.kind == Kind.P_REFLECT)) return true;
        return false;
    }

    void step() {
        if (pvp) { stepPvp(); return; }
        ev.clear();
        if (over) return;
        steps++;
        time = steps * DT;
        idleT = p.idle() ? idleT + DT : 0;
        updatePlayer();
        updateBoss();
        for (int i = 0; i < objs.size(); i++) {
            Obj o = objs.get(i);
            if (!o.dead) updateObj(o);
        }
        objs.removeIf(o -> o.dead);
        if (b.hitFlash > 0) b.hitFlash -= DT;
        if (b.hitT > 0) b.hitT -= DT;
        if (b.downT > 0) {
            b.downT -= DT;
            // the last third of the time on the floor he rolls back up onto his feet
            double k = Math.max(0, Math.min(1, b.downT / 0.3));
            b.rot = b.downT <= 0 ? 0 : b.downAng == 270 ? 360 - 90 * k : 90 * k;
        }
        if (p.hurtFlash > 0) p.hurtFlash -= DT;
    }

    private void updatePlayer() {
        p.mana = Math.min(MAX_MANA, p.mana + MANA_REGEN * DT);
        if (pvp) p.sta = Math.min(MAX_STA, p.sta + STA_REGEN * DT);
        if (p.invuln > 0) p.invuln -= DT;
        for (int i = 0; i < p.cd.length; i++) if (p.cd[i] > 0) p.cd[i] = Math.max(0, p.cd[i] - DT);
        if (p.carried) return;
        if (p.downT > 0) {
            p.downT -= DT;
            if (p.downT <= 0) { p.downT = 0; p.rot = 0; }
            return;
        }
        if (p.cur == null && !p.queue.isEmpty()) start(p.queue.poll());
        if (p.cur == null) { freeMove(); return; }
        PlanItem it = p.cur;
        ActionType a = it.type;
        p.step++;
        if (!p.fizzled) {
            switch (a) {
                case RUN: {
                    double sp = it.alt ? ActionType.WALK_SPEED : RUN_SPEED;
                    movePlayer(p.mdx * sp * DT, p.mdy * sp * DT);
                    faceDir(p.mdx);
                    break;
                }
                case ROLL: case DASH: case BACKFLIP: case HURT: {
                    double dist = a == ActionType.ROLL ? ROLL_DIST : a == ActionType.DASH ? 36 : a == ActionType.BACKFLIP ? 26 : 14;
                    double d = dist * (ease(p.step / (double) it.steps) - ease((p.step - 1) / (double) it.steps));
                    movePlayer(p.mdx * d, p.mdy * d);
                    if (a == ActionType.ROLL || a == ActionType.DASH) faceDir(p.mdx);
                    if (a == ActionType.BACKFLIP) faceBoss();
                    break;
                }
                case APPARATE:
                    if (p.step == 3) emit(EvType.APPARATE, p.x, p.y - 12, null, 0);
                    if (p.step == 10) { movePlayer(p.mdx * it.dx, p.mdy * it.dx); faceBoss(); }
                    if (p.step == 18) emit(EvType.APPARATE, p.x, p.y - 12, null, 0);
                    break;
                case DENSAUGEO: case EXPELLIARMUS: case STUPEFY: case SERPENSORTIA:
                    if (it.aimed()) faceDir(it.dx); else faceBoss();
                    if (!a.charged()) { if (p.step == ActionType.LIGHT_FIRE) release(a); break; }
                    if (it.alt && p.step == ActionType.RELEASE_FIRE) release(a);
                    if (!it.alt && p.step == it.steps) {
                        p.stored[a.spellIndex()] = true;
                        emit(EvType.STORED, p.x, p.y - 30, a.label() + Lang.t(" 已儲存", " stored"), a.ordinal());
                    }
                    break;
                case KICK: {
                    // leap at the nearest enemy, tumble half a turn and axe-kick down on its head
                    double[] tg = nearestEnemy();
                    if (tg != null) p.face = tg[0] >= p.x ? 1 : -1;
                    if (tg != null && p.step >= 6 && p.step <= 30) {
                        double dx = tg[0] - p.x, dy = tg[1] - p.y, d = Math.hypot(dx, dy);
                        if (d > 8) { double sp = Math.min(44.0 / 24, d - 8); movePlayer(dx / d * sp, dy / d * sp); }
                    }
                    if (p.step == a.hitStep()) circleHit(a, 11, b.phase == 1, 0.4, 0);
                    break;
                }
                case FLURRY: {
                    // a crouched storm of punches, shuffling in on the nearest enemy (eight hits of 1)
                    double[] tg = nearestEnemy();
                    if (tg != null) p.face = tg[0] >= p.x ? 1 : -1;
                    double dx = tg == null ? 0 : tg[0] - p.x, dy = tg == null ? 0 : tg[1] - p.y, d = Math.hypot(dx, dy);
                    if (p.step >= 4 && p.step <= 50 && tg != null && d > 16) {
                        double sp = Math.min(0.9, d - 16);   // keeps up with a target knocked back by each punch
                        movePlayer(dx / d * sp, dy / d * sp);
                    }
                    for (int hs : ActionType.FLURRY_HITS) if (p.step == hs) melee(a, 1, false, 0);
                    break;
                }
                case SPIN:
                    if (p.step == a.hitStep()) spinKick();
                    break;
                case THROW:
                    faceBoss();
                    if (p.step == a.hitStep()) grab();
                    if (p.grabbed && p.step > a.hitStep()) {
                        // three shoulder throws: he is loaded onto her back, flipped over her shoulder and slammed down
                        int k = p.step - a.hitStep() - 1, n = k / ActionType.SLAM_STEPS, at = k % ActionType.SLAM_STEPS;
                        if (at == 0) slamTarget(n);
                        double f = (at + 1) / (double) ActionType.SLAM_STEPS;
                        double tx = p.gx0 + (p.gx1 - p.gx0) * f, ty = p.gy0 + (p.gy1 - p.gy0) * f, tz = 20 * Math.sin(Math.PI * f);
                        // the thrown body turns heels over head: three-quarters of a turn the first time, then half a turn each time after
                        int dir = p.gx1 >= p.gx0 ? 1 : -1;
                        double e = f * f * (3 - 2 * f);
                        double rot = n == 0 ? dir * 270 * e : (dir > 0 ? 90 : 270) + dir * 180 * e;
                        if (pvp) {
                            q.x = tx; q.y = ty; q.lift = tz; q.rot = rot;
                        } else {
                            b.x = tx; b.y = ty; b.z = tz; b.rot = rot;
                            thrownBody(n);
                            b.stun = Math.max(b.stun, 0.3);
                        }
                        if (at == ActionType.SLAM_STEPS - 1) slam(n);
                    }
                    break;
                default:
                    faceBoss();
                    break;
            }
        }
        if (p.step == a.keyStep(it.steps, it.alt)) emit(EvType.KEY_POSE, p.x, p.y, a.zh, p.side);
        if (p.step >= it.steps) finish();
    }

    /** Real-time movement from held keys while no order is running. */
    private void freeMove() {
        double len = Math.hypot(p.freeDx, p.freeDy);
        if (len < 1e-9) { p.freeAnim = 0; return; }
        double sp = (p.walk ? ActionType.WALK_SPEED : RUN_SPEED) * DT / len;
        movePlayer(p.freeDx * sp, p.freeDy * sp);
        faceDir(p.freeDx);
        p.freeAnim++;
    }

    private void faceDir(double dx) { if (Math.abs(dx) > 0.2) p.face = dx > 0 ? 1 : -1; }
    private void faceBoss() { p.face = fx() >= p.x ? 1 : -1; }

    // The foe: Voldemort, or in a two-player duel the other player.
    double fx() { return pvp ? q.x : b.x; }
    double fy() { return pvp ? q.y : b.y; }
    /** The foe can be seen (not vanished or turned to smoke). */
    boolean foeSeen() { return pvp ? !q.vanished() : !b.hidden; }
    /** The foe is on the ground and can be struck in close combat. */
    boolean foeOpen() { return pvp ? !q.vanished() && q.z() == 0 && !q.carried : !b.hidden && b.z == 0; }
    private void setFoe(double x, double y) { if (pvp) { q.x = x; q.y = y; } else { b.x = x; b.y = y; } }

    /** Is the foe inside a melee circle of this radius around the player? */
    private boolean inReach(double radius) {
        return foeOpen() && Math.hypot(fx() - p.x, fy() - p.y) <= radius + 6;
    }

    /**
     * Hits every enemy inside the move's circle around Malfoy: Voldemort, Death Eaters and Inferi.
     * Returns how many were hit; a move that hits nothing is a whiff.
     */
    private int circleHit(ActionType a, int dmg, boolean interrupt, double stun, double knock) {
        double r = a.meleeRadius();
        int n = 0;
        for (Obj m : objs) {
            if (!m.active || m.dead) continue;
            double d = Math.hypot(m.x - p.x, m.y - p.y);
            if (m.kind == Kind.MINION && d <= r + 5) {
                if (knock > 0) push(m, knock);
                damageMinion(m, dmg);
                n++;
            } else if (m.kind == Kind.INFERI && d <= r + 4) {
                m.hit = true;
                kill(m);
                addScore(20, Lang.t("擊碎陰屍", "Inferius smashed"), m.x, m.y - 20);
                emit(EvType.BOSS_HIT, m.x, m.y - 8, null, 0);
                n++;
            }
        }
        if (inReach(r)) {
            if (knock > 0) {
                double dx = fx() - p.x, dy = fy() - p.y, d = Math.max(1e-6, Math.hypot(dx, dy));
                double[] k = { fx() + dx / d * knock, fy() + dy / d * knock };
                resolve(k, 14);
                setFoe(k[0], k[1]);
            }
            emit(EvType.MELEE, (p.x + fx()) / 2, (p.y + fy()) / 2 - 14, a.zh, dmg);
            if (pvp) hurtMelee(dmg, true); else damageBoss(dmg, interrupt, stun);
            n++;
        } else if (n > 0) {
            emit(EvType.MELEE, p.x + p.face * 14, p.y - 14, a.zh, dmg);
        }
        if (n == 0) whiff();
        return n;
    }

    private void push(Obj m, double dist) {
        double dx = m.x - p.x, dy = m.y - p.y, d = Math.max(1e-6, Math.hypot(dx, dy));
        double[] q = { m.x + dx / d * dist, m.y + dy / d * dist };
        resolve(q, 6);
        m.x = q[0]; m.y = q[1];
    }

    private void melee(ActionType a, int dmg, boolean interrupt, double stun) { circleHit(a, dmg, interrupt, stun, 0); }

    private void whiff() {
        p.missed = true;
        emit(EvType.FIZZLE, p.x + p.face * 12, p.y - 14, Lang.t("落空", "Fizzled"), 0);
        addScore(0, Lang.t("落空", "Fizzled"), p.x, p.y - 36);
    }

    private void spinKick() { circleHit(ActionType.SPIN, 14, b.phase == 1, 0, 40); }

    /** Nearest enemy of any kind, for the lunge to charge at; null if none. */
    private double[] nearestEnemy() {
        double[] best = !foeSeen() ? null : new double[] { fx(), fy() };
        double bd = !foeSeen() ? 1e18 : Math.hypot(fx() - p.x, fy() - p.y);
        for (Obj m : objs) {
            if ((m.kind != Kind.MINION && m.kind != Kind.INFERI) || !m.active || m.dead) continue;
            double d = Math.hypot(m.x - p.x, m.y - p.y);
            if (d < bd) { bd = d; best = new double[] { m.x, m.y }; }
        }
        return best;
    }

    private void grab() {
        double r = ActionType.THROW.meleeRadius();
        int small = 0;
        for (Obj m : objs) {
            if (!m.active || m.dead) continue;
            double d = Math.hypot(m.x - p.x, m.y - p.y);
            if (m.kind == Kind.MINION && d <= r + 5) {
                push(m, -2 * d - 10);
                damageMinion(m, 24);
                small++;
            } else if (m.kind == Kind.INFERI && d <= r + 4) {
                m.hit = true;
                kill(m);
                addScore(20, Lang.t("擊碎陰屍", "Inferius smashed"), m.x, m.y - 20);
                small++;
            }
        }
        if (small > 0) emit(EvType.THROW, p.x - p.face * 20, p.y, null, 24);
        if (!inReach(r)) { if (small == 0) whiff(); return; }
        p.grabbed = true;
        if (pvp) {
            // the other player is picked up: whatever they were doing is over
            q.carried = true; q.cur = null; q.queue.clear(); q.hitEpoch++;
        } else {
            b.stun = Math.max(b.stun, 1.2);
            if (interruptCast()) addScore(100, Lang.t("打斷施法!", "Interrupted!"), b.x, b.y - 48);
        }
        emit(EvType.MELEE, fx(), fy() - 18, Lang.t("抓住了！", "Grabbed!"), 0);
    }

    /** Where slam {@code n} lands: behind her, in front, behind again. */
    private void slamTarget(int n) {
        p.gx0 = fx(); p.gy0 = fy();
        int side = n % 2 == 0 ? -1 : 1;
        double[] q = { p.x + side * p.face * 24, p.y };
        resolve(q, 14);
        p.gx1 = q[0]; p.gy1 = q[1];
    }

    private void slam(int n) {
        boolean last = n == ActionType.SLAMS - 1;
        int dmg = last ? 14 : 10;
        if (pvp) {
            q.lift = 0;
            emit(EvType.THROW, q.x, q.y, last ? "final" : null, dmg);
            if (last) {
                p.grabbed = false;
                q.carried = false;
                // flat on the floor for a moment, then back up
                q.rot = p.gx1 >= p.gx0 ? 270 : 90;
                q.downT = 0.9;
            }
            hurtMelee(dmg, false);
            return;
        }
        b.z = 0;
        emit(EvType.THROW, b.x, b.y, last ? "final" : null, dmg);
        damageBoss(dmg, false, last ? 1.2 : 0);
        if (last) {
            p.grabbed = false;
            // he is left flat on his back for a moment, then gets up
            b.downAng = p.gx1 >= p.gx0 ? 270 : 90;
            b.downT = 0.9;
            b.rot = b.downAng;
        }
    }

    /** Share of a shoulder throw's damage dealt to a servant that his flying body crashes into. */
    static final double BODY_SHARE = 0.3;

    /**
     * While he is being thrown his body is a weapon: every Death Eater or Inferius it crashes into takes 30% of
     * that throw's damage (once per throw), and his own curses, shards and bolts bounce off him and fly back out.
     */
    private void thrownBody(int n) {
        int dmg = (int) Math.ceil((n == ActionType.SLAMS - 1 ? 14 : 10) * BODY_SHARE);
        for (int i = 0, count = objs.size(); i < count; i++) {
            Obj m = objs.get(i);
            if (m.dead || !m.active) continue;
            double d = Math.hypot(m.x - b.x, m.y - b.y);
            if ((m.kind == Kind.MINION || m.kind == Kind.INFERI) && d < 16 && m.thrownBy != n + 1) {
                m.thrownBy = n + 1;
                emit(EvType.CANCEL, m.x, m.y, "BODY", dmg);
                addScore(30, Lang.t("砸中敵人!", "Body blow!"), m.x, m.y - 30);
                if (m.kind == Kind.INFERI) {
                    m.hit = true;
                    kill(m);
                    addScore(20, Lang.t("擊碎陰屍", "Inferius smashed"), m.x, m.y - 20);
                } else damageMinion(m, dmg);
            } else if (m.hostile && (m.kind == Kind.CRUCIO || m.kind == Kind.SHARD || m.kind == Kind.M_BOLT) && d < 14) {
                // the barrage glances off him and flies straight back out, now hers
                double ux = (m.x - b.x) / Math.max(1e-6, d), uy = (m.y - b.y) / Math.max(1e-6, d);
                if (d < 1e-6) { ux = -p.face; uy = 0; }
                Obj r = new Obj();
                r.kind = Kind.P_REFLECT; r.x = b.x + ux * 16; r.y = b.y + uy * 16; r.a = 220; r.b = 10; r.life = 2; r.d = 1; r.e = 1;
                r.vx = ux * r.a; r.vy = uy * r.a;
                objs.add(r);
                m.hit = true;
                kill(m);
                cancels++;
                addScore(40, Lang.t("彈幕反彈!", "Bounced back!"), m.x, m.y - 24);
                emit(EvType.CANCEL, m.x, m.y, "BOUNCE", 0);
            }
        }
    }

    /** Protego throws a curse orb, shard or bolt back: it becomes hers and homes on the nearest enemy. */
    private void shieldBounce(Obj o, int dmg, int score) {
        Obj r = new Obj();
        r.kind = Kind.P_REFLECT; r.x = o.x; r.y = o.y; r.a = 220; r.b = dmg; r.life = 3;
        aim(r, fx(), fy(), r.a);
        objs.add(r);
        o.hit = true;
        kill(o);
        addScore(score, Lang.t("反彈!", "Reflected!"), p.x, p.y - 40);
        emit(EvType.BLOCK, o.x, o.y, null, 0);
    }

    private void releaseGrab() {
        if (!p.grabbed) return;
        p.grabbed = false;
        if (pvp) {
            q.carried = false; q.lift = 0; q.rot = 0;
            double[] k = { q.x, q.y };
            resolve(k, BODY_R);
            q.x = k[0]; q.y = k[1];
            return;
        }
        b.z = 0;
        b.rot = 0;
        double[] q = { b.x, b.y };
        resolve(q, 14);
        b.x = q[0]; b.y = q[1];
    }

    private void movePlayer(double dx, double dy) {
        double[] q = { p.x + dx, p.y + dy };
        resolve(q, BODY_R);
        p.x = q[0]; p.y = q[1];
    }

    private void fizzle(String why) {
        p.fizzled = true;
        fizzles++;
        lastFizzle = why;
        emit(EvType.FIZZLE, p.wandX(), p.y, why, 0);
        addScore(-20, why, p.x, p.y - 34);
    }

    private void start(PlanItem it) {
        p.cur = it; p.step = 0; p.fizzled = false; p.missed = false;
        ActionType a = it.type;
        // the direction of a move is settled now, relative to where Voldemort is at this moment
        double bx = fx() - p.x, by = fy() - p.y, bl = Math.max(1e-6, Math.hypot(bx, by));
        switch (a) {
            case DASH: { double side = it.dx >= 0 ? 1 : -1; p.mdx = -by / bl * side; p.mdy = bx / bl * side; break; }
            case BACKFLIP: p.mdx = -bx / bl; p.mdy = -by / bl; break;
            case APPARATE: p.mdx = Math.cos(it.dy); p.mdy = Math.sin(it.dy); break;
            default: p.mdx = it.dx; p.mdy = it.dy; break;
        }
        if (!it.isRelease() && p.cd[a.ordinal()] > 1e-9) { fizzle(Lang.t("冷卻中", "On cooldown")); return; }
        if (it.isRelease() && !p.stored[a.spellIndex()]) { fizzle(Lang.t("尚未儲存", "Not stored yet")); return; }
        int cost = it.isRelease() ? 0 : a.mana(pvp);
        if (cost > p.mana + 1e-9) { fizzle(Lang.t("魔力不足", "Not enough mana")); return; }
        int sta = a.stamina(pvp);
        if (sta > p.sta + 1e-9) { fizzle(Lang.t("體力不足", "Not enough stamina")); return; }
        p.mana -= cost;
        p.sta -= sta;
        double cool = a.cooldown(pvp);
        if (cool > 0 && (!a.charged() || it.isRelease())) p.cd[a.ordinal()] = cool;
        if (it.isRelease()) p.stored[a.spellIndex()] = false;
    }

    private void finish() {
        PlanItem it = p.cur;
        ActionType a = it.type;
        p.cur = null;
        if (a == ActionType.THROW) releaseGrab();
        if (a == ActionType.HURT) return;
        if (p.fizzled || p.missed) { p.lastType = null; return; }
        boolean flow = p.lastType != null && p.lastType.cat != a.cat;
        p.same = a == p.lastType ? p.same + 1 : 0;
        p.combo++;
        maxCombo = Math.max(maxCombo, p.combo);
        double mult = 1 + Math.min(p.combo, 20) * 0.1;
        double lenFactor = a == ActionType.RUN ? Math.max(0.5, it.dur() / a.dur) : 1.0;
        // runs repeat naturally, so only non-run repeats are penalized
        boolean dull = p.same >= 2 && a != ActionType.RUN;
        int pts = (int) Math.round(a.score * lenFactor * mult * (flow ? 1.3 : 1.0) * (dull ? 0.4 : 1.0));
        addScore(pts, flow ? Lang.t("連貫", "Flow") : (dull ? Lang.t("重複", "Repeat") : null), p.x, p.y - 30);
        p.lastType = a;
        if (it.last && it.epoch == p.hitEpoch) {
            int bonus = (int) Math.round(15 * it.segLen * it.segLen);
            addScore(bonus, String.format(Lang.t("完整演出 %.1fs", "Full plan %.1fs"), it.segLen), p.x, p.y - 42);
        }
    }

    private void release(ActionType a) {
        Obj o = new Obj();
        o.x = p.wandX(); o.y = p.y; o.life = 2.5; o.d = a == ActionType.STUPEFY ? 3 : 1;
        PlanItem it = p.cur;
        switch (a) {
            case DENSAUGEO:    o.kind = Kind.P_DENS; o.b = 4;  break;
            case EXPELLIARMUS: o.kind = Kind.P_EXP;  o.b = 8;  break;
            case STUPEFY:      o.kind = Kind.P_STUP; o.b = 12; break;
            case SERPENSORTIA: o.kind = Kind.P_SNAKE; o.b = 18; o.life = 6; break;
            default: return;
        }
        o.a = a.speed();
        // aimed by hand it flies straight (e = 1); otherwise it homes in on the nearest enemy,
        // or flies straight ahead when none is visible (Voldemort turned to smoke)
        if (it != null && it.aimed()) {
            // leave from just in front of her along the aim line, so the spell goes exactly where it was pointed;
            // the snake is sent to a spot (c, d) where it lies in wait
            o.e = 1;
            if (o.kind != Kind.P_SNAKE) { o.x = p.x + it.dx * 9; o.y = p.y + it.dy * 9; }
            else { o.c = p.x + it.dx; o.d = p.y + it.dy; }
        }
        double[] tg = it == null || !it.aimed() ? target(o.x, o.y)
                    : o.kind == Kind.P_SNAKE ? new double[] { o.c, o.d } : new double[] { o.x + it.dx * 100, o.y + it.dy * 100 };
        if (tg == null) tg = new double[] { o.x + p.face * 100, o.y };
        aim(o, tg[0], tg[1], o.a);
        if (pvp) {
            o.owner = p.side;
            o.cv = cancelValue(a);
            o.life *= PVP_LIFE;
        }
        objs.add(o);
        emit(EvType.RELEASE, o.x, o.y, a.latin, a.ordinal());
    }

    /**
     * Shortest time from pressing this spell's key now to its bolt reaching the foe: the charge and release
     * (or the light attack's wind-up), then a straight flight from the wand to the edge of the foe's hit circle.
     * -1 when the foe cannot be seen.
     */
    double fastestHit(ActionType a) {
        if (!a.isSpell() || !foeSeen()) return -1;
        int lead = !a.charged() ? ActionType.LIGHT_FIRE
                 : (p.stored[a.spellIndex()] ? 0 : a.steps) + ActionType.RELEASE_FIRE;
        boolean snake = a == ActionType.SERPENSORTIA;
        double r = pvp ? PVP_HIT_R + (snake ? 2 : 0) : snake ? 10 : 9;
        double d = Math.hypot(fx() - p.wandX(), (pvp ? q.y - q.z() : b.y) - p.y);
        return lead * DT + Math.max(0, d - r) / a.speed();
    }

    // ---------------------------------------------------------------- Voldemort

    private void updateBoss() {
        if (b.blinkCd > 0) b.blinkCd -= DT;
        if (b.stun > 0) { b.stun -= DT; return; }
        if (bossHuman) { updateHumanBoss(); return; }
        // crowded at close range and not being hit: he breaks away
        boolean crowded = !b.hidden && b.z == 0 && Math.hypot(p.x - b.x, p.y - b.y) < CROWD_RANGE;
        b.closeT = crowded ? b.closeT + DT : 0;
        if (b.closeT >= CROWD_TIME) retreat();
        if (b.busy > 0) {
            b.busy -= DT;
            if (b.busy <= 0) endAttack();
            return;
        }
        b.cooldown -= DT;
        // he never casts into the middle of a committed plan, nor while her last spells are still landing:
        // what the preview showed is exactly what happens
        if (b.cooldown <= 0 && !p.planActive() && !resolving()) startCast();
    }

    /** True when a second-player Voldemort may act: not casting or stunned, and Malfoy's plan has played out. */
    boolean bossTurn() { return !over && b.busy <= 0 && b.stun <= 0 && !p.planActive() && !resolving(); }

    /** Whether a second-player Voldemort can cast this spell right now: it must suit the range, as his own picks do. */
    boolean canCast(Attack a) { return castBlock(a) == null; }

    /** Why a spell cannot be cast now, or null. */
    String castBlock(Attack a) {
        int band = rangeBand(Math.hypot(p.x - b.x, p.y - b.y));
        Attack[] pool = band == 0 ? CLOSE_POOL : band == 1 ? MID_POOL : FAR_POOL;
        boolean in = false;
        for (Attack x : pool) if (x == a) in = true;
        if (!in) return Lang.t("距離不符", "wrong range");
        if (a == b.last) return Lang.t("不能連用", "used last");
        if (a == Attack.SUMMON && minionsAlive() >= MAX_PER_KIND) return Lang.t("已達上限", "limit");
        if (a == Attack.INFERI && inferiAlive() >= MAX_PER_KIND) return Lang.t("已達上限", "limit");
        return null;
    }

    /** The bands (0 close, 1 mid, 2 far) in which a spell can be cast. */
    static boolean[] bandsOf(Attack a) {
        boolean[] r = new boolean[3];
        Attack[][] pools = { CLOSE_POOL, MID_POOL, FAR_POOL };
        for (int i = 0; i < 3; i++) for (Attack x : pools[i]) if (x == a) r[i] = true;
        return r;
    }

    private void updateHumanBoss() {
        if (b.busy > 0) {
            b.busy -= DT;
            if (b.busy <= 0) endAttack();
            return;
        }
        if (!bossTurn()) return;
        double len = Math.hypot(bossDx, bossDy);
        if (bossBlink) {
            bossBlink = false;
            if (b.blinkCd <= 0 && !b.hidden) {
                // that way, or straight away from Malfoy with no direction held
                double ux = len > 0 ? bossDx / len : b.x - p.x, uy = len > 0 ? bossDy / len : b.y - p.y;
                double ul = Math.max(1e-6, Math.hypot(ux, uy));
                double[] q = { clamp(b.x + ux / ul * BLINK_DIST, MIN_X + 20, MAX_X - 20), clamp(b.y + uy / ul * BLINK_DIST, MIN_Y + 10, MAX_Y - 10) };
                resolve(q, 14);
                emit(EvType.APPARATE, b.x, b.y, null, 0);
                b.x = q[0]; b.y = q[1];
                emit(EvType.APPARATE, b.x, b.y, null, 0);
                b.blinkCd = BLINK_CD;
            }
        } else if (len > 0 && !b.hidden) {
            double[] q = { clamp(b.x + bossDx / len * BOSS_FLOAT * DT, MIN_X + 20, MAX_X - 20), clamp(b.y + bossDy / len * BOSS_FLOAT * DT, MIN_Y + 10, MAX_Y - 10) };
            resolve(q, 14);
            b.x = q[0]; b.y = q[1];
        }
        if (bossOrder != null && canCast(bossOrder)) {
            Attack a = bossOrder;
            bossOrder = null;
            startCast(a);
        }
    }

    /** Apparates 180-240 px away from Malfoy, roughly away from where she stands. */
    private void retreat() {
        b.closeT = 0;
        double away = Math.atan2(b.y - p.y, b.x - p.x);
        for (int tries = 0; tries < 16; tries++) {
            double ang = away + (rng.nextDouble() - 0.5) * (tries < 8 ? 1.6 : 6.28), dist = 180 + rng.nextInt(60);
            double nx = p.x + Math.cos(ang) * dist, ny = p.y + Math.sin(ang) * dist;
            if (nx < MIN_X + 30 || nx > MAX_X - 30 || ny < MIN_Y + 20 || ny > MAX_Y - 20 || inObstacle(nx, ny, 16)) continue;
            emit(EvType.APPARATE, b.x, b.y, null, 0);
            b.x = nx; b.y = ny;
            emit(EvType.APPARATE, b.x, b.y, null, 0);
            emit(EvType.RETREAT, b.x, b.y - 30, Lang.t("佛地魔拉開距離", "Voldemort backs off"), 0);
            return;
        }
    }

    private void endAttack() {
        boolean smokeLeft = false;
        for (Obj o : objs) if (o.kind == Kind.SMOKE && !o.dead) smokeLeft = true;
        if (!smokeLeft) b.hidden = false;
        b.cooldown = POST_CAST;
        if (bossHuman) return; // he moves himself
        if (!b.hidden && rng.nextDouble() < 0.45) {
            for (int tries = 0; tries < 12; tries++) {
                double ang = rng.nextDouble() * Math.PI * 2, dist = 150 + rng.nextInt(100);
                double nx = p.x + Math.cos(ang) * dist, ny = p.y + Math.sin(ang) * dist;
                if (nx < MIN_X + 30 || nx > MAX_X - 30 || ny < MIN_Y + 20 || ny > MAX_Y - 20 || inObstacle(nx, ny, 16)) continue;
                emit(EvType.APPARATE, b.x, b.y, null, 0);
                b.x = nx; b.y = ny;
                emit(EvType.APPARATE, b.x, b.y, null, 0);
                break;
            }
        }
    }

    // Voldemort picks from what suits the distance; each pattern keeps a way out at that range.
    static final Attack[] CLOSE_POOL = { Attack.DEPULSO, Attack.FIRE_RING, Attack.GLASS, Attack.SMOKE };
    static final Attack[] MID_POOL = { Attack.CRUCIO, Attack.FIRE_SERPENT, Attack.NAGINI, Attack.MORSMORDRE, Attack.INFERI, Attack.GLASS, Attack.SUMMON };
    static final Attack[] FAR_POOL = { Attack.AVADA, Attack.MORSMORDRE, Attack.NAGINI, Attack.INFERI, Attack.SMOKE, Attack.CRUCIO, Attack.SUMMON };

    static int rangeBand(double d) { return d < CLOSE_RANGE ? 0 : d < FAR_RANGE ? 1 : 2; }
    static String bandName(int band) { return Lang.en ? new String[] { "close", "mid", "far" }[band] : new String[] { "近距離", "中距離", "遠距離" }[band]; }

    private void startCast() {
        int band = rangeBand(Math.hypot(p.x - b.x, p.y - b.y));
        Attack[] pool = band == 0 ? CLOSE_POOL : band == 1 ? MID_POOL : FAR_POOL;
        Attack a;
        do { a = pool[rng.nextInt(pool.length)]; }
        while (a == b.last || (a == Attack.SUMMON && minionsAlive() >= MAX_PER_KIND) || (a == Attack.INFERI && inferiAlive() >= MAX_PER_KIND));
        startCast(a);
    }

    private void startCast(Attack a) {
        int band = rangeBand(Math.hypot(p.x - b.x, p.y - b.y));
        b.last = a;
        b.castId++;
        b.castTime = time;
        int first = objs.size();
        double busy = spawn(a, 0);
        String name = a.label();
        // Phase 2 layers a lighter pattern on top, but never on the area-denial ones and never at point-blank range.
        boolean heavy = a == Attack.MORSMORDRE || a == Attack.SMOKE || a == Attack.INFERI;
        if (b.phase == 2 && band > 0 && !heavy && rng.nextDouble() < 0.4) {
            Attack a2 = band == 2 && a != Attack.AVADA ? Attack.AVADA : (a != Attack.CRUCIO ? Attack.CRUCIO : Attack.GLASS);
            busy = Math.max(busy, spawn(a2, 0.7));
            name += Lang.t(" ＋ ", " + ") + a2.label();
        }
        // faster casting: wind-ups and the wave itself are compressed (more so in phase 2)
        double f = b.phase == 2 ? CAST_PACE_P2 : CAST_PACE_P1;
        for (int i = first; i < objs.size(); i++) {
            Obj o = objs.get(i);
            double after = o.life - o.delay;
            o.delay *= f;
            if (o.life < 90) o.life = o.delay + after;
        }
        b.busy = busy * f;
        b.castName = name;
        emit(EvType.CAST_START, b.x, b.y, name, band);
    }

    /** Spawns one attack pattern, starting {@code ex} seconds from now; returns how long Voldemort is busy. */
    private double spawn(Attack a, double ex) {
        boolean p2 = b.phase == 2;
        double toP = Math.atan2(p.y - b.y, p.x - b.x);
        switch (a) {
            case AVADA: {
                // Beams lock their aim at cast time and stop at the first pillar: hide or step off the line.
                if (!p2) { beam(toP, ex + 1.1); avadaFollowUp(toP, ex + 1.85); return ex + 2.4; }
                double[] off = { 0, -0.28, 0.28, -0.56, 0.56 };
                double[] charge = { 0.9, 1.1, 1.1, 1.3, 1.3 };
                for (int i = 0; i < off.length; i++) beam(toP + off[i], ex + charge[i]);
                // a sixth beam reads where Malfoy is heading right now
                double lx = p.x, ly = p.y;
                if (p.cur != null && (p.cur.type == ActionType.RUN || p.cur.type == ActionType.ROLL)) { lx += p.cur.dx * 60; ly += p.cur.dy * 60; }
                beam(Math.atan2(ly - b.y, lx - b.x), ex + 1.7);
                avadaFollowUp(toP, ex + 2.45);
                return ex + 2.9;
            }
            case CRUCIO: {
                // a = launch angle, c = speed. Orbs circle Voldemort, then home in.
                int n = p2 ? 8 : 5;
                for (int i = 0; i < n; i++) {
                    Obj o = hostile(Kind.CRUCIO, ex + (p2 ? 0.5 + i * 0.18 : 0.6 + i * 0.25));
                    o.idx = i; o.x = b.x; o.y = b.y;
                    o.a = p2 ? toP + i * Math.PI * 2 / n : toP + (i - (n - 1) / 2.0) * 0.35;
                    o.c = p2 ? 140 : 120;
                    o.life = o.delay + 3.6;
                }
                double burst = ex + (p2 ? 0.5 + n * 0.18 : 0.6 + n * 0.25) + 0.6;
                int m = p2 ? 16 : 10;
                for (int i = 0; i < m; i++) {
                    Obj o = hostile(Kind.CRUCIO, burst);
                    o.idx = i; o.x = b.x; o.y = b.y; o.e = 1;
                    o.a = toP + (i + 0.5) * Math.PI * 2 / m; o.c = 110;
                    o.life = o.delay + 4;
                }
                return burst + 0.6;
            }
            case FIRE_SERPENT: {
                // a = heading, c = speed, d = timer for dropping burning tiles.
                int n = p2 ? 2 : 1;
                for (int i = 0; i < n; i++) {
                    Obj o = hostile(Kind.SERPENT, ex + 0.8 + i * 0.4);
                    o.x = b.x; o.y = b.y;
                    o.a = toP + (n == 1 ? 0 : (i == 0 ? -0.9 : 0.9));
                    o.c = p2 ? 125 : 110;
                    o.life = o.delay + 3.4;
                }
                return ex + 0.8 + n * 0.4 + 2.4;
            }
            case MORSMORDRE: {
                Obj sky = hostile(Kind.DARK_MARK, ex);
                sky.x = b.x; sky.y = b.y; sky.life = ex + (p2 ? 3.0 : 2.4);
                double px = p.x, py = p.y;
                if (!p2) {
                    strike(px, py, 18, ex + 1.0);
                    for (int k = 0; k < 6; k++) { double an = k * Math.PI / 3; strike(px + Math.cos(an) * 46, py + Math.sin(an) * 46, 14, ex + 1.4); }
                    for (int k = 0; k < 6; k++) { double an = k * Math.PI / 3 + Math.PI / 6; strike(px + Math.cos(an) * 84, py + Math.sin(an) * 84, 15, ex + 1.8); }
                    for (int k = 0; k < 8; k++) { double an = k * Math.PI / 4; strike(px + Math.cos(an) * 120, py + Math.sin(an) * 120, 15, ex + 2.2); }
                    return ex + 2.7;
                }
                // a checkerboard whose safe squares swap, then a heavy strike where Malfoy stood
                for (int i = -3; i <= 3; i++)
                    for (int j = -3; j <= 3; j++) {
                        double cx = px + i * 34, cy = py + j * 34;
                        if (cx < MIN_X - 10 || cx > MAX_X + 10 || cy < MIN_Y - 10 || cy > MAX_Y + 10) continue;
                        strike(cx, cy, 16, ex + (((i + j) & 1) == 0 ? 1.0 : 1.6));
                    }
                strike(px, py, 34, ex + 2.3);
                for (int k = 0; k < 10; k++) { double an = k * Math.PI / 5; strike(px + Math.cos(an) * 70, py + Math.sin(an) * 70, 15, ex + 2.9); }
                return ex + 3.4;
            }
            case NAGINI: {
                // Straight dashes across the hall through points near Malfoy. a = path length, c = distance travelled, d = speed.
                int n = p2 ? 4 : 2;
                for (int i = 0; i < n; i++) {
                    double ang = rng.nextDouble() * Math.PI * 2;
                    double tx = p.x + rng.nextInt(61) - 30, ty = p.y + rng.nextInt(61) - 30;
                    Obj o = hostile(Kind.NAGINI, ex + 1.0 + i * 0.85);
                    o.vx = Math.cos(ang); o.vy = Math.sin(ang);
                    o.x = tx - o.vx * 430; o.y = ty - o.vy * 430;
                    o.a = 860; o.d = 580;
                    o.life = o.delay + 1.6;
                }
                return ex + 1.0 + n * 0.85 + 0.6;
            }
            case SMOKE: {
                // Voldemort himself dashes as black smoke in a chain; he ends up where the last dash stops.
                // x,y = start, a,b = end, c = distance travelled, d = length, e = 1 on the final dash.
                int n = p2 ? 3 : 2;
                double sx = b.x, sy = b.y, t = ex + 1.0;
                for (int i = 0; i < n; i++) {
                    double tx = p.x + rng.nextInt(41) - 20, ty = p.y + rng.nextInt(41) - 20;
                    double ang = Math.atan2(ty - sy, tx - sx);
                    double len = Math.hypot(tx - sx, ty - sy) + 90;
                    double ex2 = clamp(sx + Math.cos(ang) * len, MIN_X + 20, MAX_X - 20), ey2 = clamp(sy + Math.sin(ang) * len, MIN_Y + 10, MAX_Y - 10);
                    double[] q = { ex2, ey2 };
                    resolve(q, 14);
                    Obj o = hostile(Kind.SMOKE, t);
                    o.x = sx; o.y = sy; o.a = q[0]; o.b = q[1];
                    o.d = Math.max(1, Math.hypot(q[0] - sx, q[1] - sy));
                    o.vx = (q[0] - sx) / o.d; o.vy = (q[1] - sy) / o.d;
                    o.e = i == n - 1 ? 1 : 0; o.idx = i;
                    o.life = o.delay + o.d / 430 + 0.05;
                    t = o.life + 0.25;
                    sx = q[0]; sy = q[1];
                }
                Obj burst = hostile(Kind.CONE, t - 0.15);
                burst.x = sx; burst.y = sy; burst.a = 0; burst.b = Math.PI; burst.c = 60;
                burst.life = burst.delay + 0.2;
                return t + 0.5;
            }
            case GLASS: {
                // Rings of shards burst outward with a gap to slip through; phase 2 adds a double spiral.
                int rings = p2 ? 5 : 3, n = p2 ? 28 : 22;
                double sp = p2 ? 150 : 130;
                for (int r = 0; r < rings; r++) {
                    int gap = rng.nextInt(n);
                    for (int k = 0; k < n; k++) {
                        if (Math.floorMod(k - gap, n) < 4) continue;
                        double an = k * Math.PI * 2 / n + r * 0.13;
                        shard(an, sp, ex + (p2 ? 0.7 + r * 0.45 : 0.8 + r * 0.55), k);
                    }
                }
                if (p2) {
                    for (int arm = 0; arm < 2; arm++)
                        for (int j = 0; j < 18; j++) shard(toP + arm * Math.PI + j * 0.35, 175, ex + 0.7 + j * 0.09, j);
                }
                double back = ex + (p2 ? 0.7 + rings * 0.45 : 0.8 + rings * 0.55) + 0.5;
                int m = p2 ? 26 : 18, gap = rng.nextInt(m);
                for (int k = 0; k < m; k++) {
                    if (Math.floorMod(k - gap, m) < 4) continue;
                    double an = k * Math.PI * 2 / m;
                    Obj o = hostile(Kind.SHARD, back);
                    o.e = 1; o.idx = k;
                    o.x = b.x + Math.cos(an) * 210; o.y = b.y + Math.sin(an) * 210;
                    o.vx = -Math.cos(an) * 120; o.vy = -Math.sin(an) * 120;
                    o.life = back + 1.9;
                }
                return back + 1.6;
            }
            case FIRE_RING: {
                // Expanding rings of fire along the floor; jump as they pass, or let a pillar shelter you. a = radius, c = speed.
                double[] sp = p2 ? new double[] { 135, 180, 225 } : new double[] { 145, 145 };
                double[] d = p2 ? new double[] { 0.8, 1.0, 1.2 } : new double[] { 0.8, 1.5 };
                for (int i = 0; i < sp.length; i++) {
                    Obj o = hostile(Kind.FIRE_RING, ex + d[i]);
                    o.x = b.x; o.y = b.y; o.c = sp[i];
                }
                return ex + (p2 ? 2.4 : 1.8);
            }
            case INFERI: {
                // Corpses claw out of the floor around Malfoy and shamble after her. c = speed.
                // corpses climb out on either side of Malfoy, topping up to two at most
                int n = Math.max(0, MAX_PER_KIND - inferiAlive());
                double base = rng.nextDouble() * Math.PI * 2;
                for (int i = 0; i < n; i++) {
                    double an = base + i * Math.PI * 2 / Math.max(1, n), r = 80 + rng.nextInt(30);
                    double[] q = { clamp(p.x + Math.cos(an) * r, MIN_X + 6, MAX_X - 6), clamp(p.y + Math.sin(an) * r, MIN_Y + 6, MAX_Y - 6) };
                    resolve(q, 6);
                    Obj o = hostile(Kind.INFERI, ex + 1.0);
                    o.x = q[0]; o.y = q[1]; o.c = p2 ? 55 : 45; o.idx = i;
                    o.life = o.delay + 6.5;
                }
                return ex + 1.6;
            }
            case SUMMON: {
                // Death Eaters apparate around Voldemort, topping up to two at most; b = hp, d = first shot delay.
                int n = Math.max(0, MAX_PER_KIND - minionsAlive());
                for (int i = 0; i < n; i++) {
                    double an = toP + Math.PI + (i - (n - 1) / 2.0) * 0.9, r = 40 + rng.nextInt(20);
                    double[] q = { clamp(b.x + Math.cos(an) * r, MIN_X + 8, MAX_X - 8), clamp(b.y + Math.sin(an) * r, MIN_Y + 8, MAX_Y - 8) };
                    resolve(q, 8);
                    Obj o = hostile(Kind.MINION, ex + 1.0);
                    o.x = q[0]; o.y = q[1]; o.b = 10; o.d = 1.2 + i * 0.6; o.idx = i % 2 == 0 ? 1 : -1;
                    o.life = o.delay + 18;
                }
                return ex + 1.5;
            }
            case DEPULSO: {
                // Point-blank cones of force; a = angle, b = half-width, c = reach. Step out of the wedge, roll through, or get behind him.
                double[] off = p2 ? new double[] { 0, -0.95, 0.95 } : new double[] { 0 };
                double[] d = p2 ? new double[] { 0.7, 1.2, 1.2 } : new double[] { 0.8 };
                for (int i = 0; i < off.length; i++) {
                    Obj o = hostile(Kind.CONE, ex + d[i]);
                    o.x = b.x; o.y = b.y; o.a = toP + off[i]; o.b = 0.55; o.c = 110;
                    o.life = o.delay + 0.2;
                }
                Obj w2 = hostile(Kind.CONE, ex + (p2 ? 1.8 : 1.4));
                w2.x = b.x; w2.y = b.y; w2.a = 0; w2.b = Math.PI; w2.c = 75;
                w2.life = w2.delay + 0.2;
                return ex + (p2 ? 2.2 : 1.8);
            }
            default: return ex + 1;
        }
    }

    /** Two more killing curses aimed where a sidestep from her current spot would land. */
    private void avadaFollowUp(double toP, double delay) {
        double nx = -Math.sin(toP), ny = Math.cos(toP);
        for (int s = -1; s <= 1; s += 2) {
            double tx = p.x + nx * 34 * s, ty = p.y + ny * 34 * s;
            beam(Math.atan2(ty - b.y, tx - b.x), delay);
        }
    }

    private void beam(double ang, double delay) {
        Obj o = hostile(Kind.AVADA, delay);
        o.x = b.x; o.y = b.y; o.vx = Math.cos(ang); o.vy = Math.sin(ang);
        o.c = rayLen(o.x, o.y, o.vx, o.vy, 1000);
        o.life = delay + 0.25;
    }

    private void strike(double x, double y, double r, double delay) {
        Obj o = hostile(Kind.MARK, delay);
        o.x = x; o.y = y; o.a = r; o.life = delay + 0.25;
    }

    private void shard(double ang, double speed, double delay, int idx) {
        Obj o = hostile(Kind.SHARD, delay);
        o.x = b.x; o.y = b.y; o.vx = Math.cos(ang) * speed; o.vy = Math.sin(ang) * speed;
        o.idx = idx; o.life = delay + 5;
    }

    private Obj hostile(Kind k, double delay) {
        Obj o = new Obj();
        o.kind = k; o.hostile = true; o.castId = b.castId; o.delay = delay;
        objs.add(o);
        return o;
    }

    /** Cancels every part of the current cast that has not gone off yet. */
    private boolean interruptCast() {
        boolean any = false;
        for (Obj o : objs) {
            if (!o.hostile || o.dead || o.castId != b.castId) continue;
            if (o.kind == Kind.DARK_MARK) { o.dead = true; continue; }
            if (!o.active) { o.dead = true; any = true; }
        }
        if (any) {
            interrupts++;
            b.busy = Math.min(b.busy, 1.1);
            emit(EvType.INTERRUPT, b.x, b.y - 16, Lang.t("打斷施法！", "Interrupted!"), 0);
        }
        return any;
    }

    private void damageBoss(int dmg, boolean interrupt, double stun) {
        b.hp -= dmg;
        // a light blow (the flurry's) flashes him only briefly, so the rush of fists stays readable on him
        b.hitFlash = dmg <= 1 ? 0.04 : 0.15;
        b.closeT = 0;
        double hx = b.x - p.x, hy = b.y - p.y, hl = Math.max(1e-6, Math.hypot(hx, hy));
        b.hitT = dmg >= 12 ? 0.4 : 0.25; b.hitDx = hx / hl; b.hitDy = hy / hl;
        emit(EvType.BOSS_HIT, b.x, b.y - 16, null, dmg);
        addScore(dmg * 3, null, b.x, b.y - 38);
        if (interrupt && interruptCast()) addScore(100, Lang.t("打斷施法!", "Interrupted!"), b.x, b.y - 48);
        if (stun > 0) {
            b.stun = Math.max(b.stun, stun);
            emit(EvType.STUN, b.x, b.y - 36, Lang.t("昏迷", "Stunned"), 0);
        }
        if (b.phase == 1 && b.hp <= BOSS_MAX_HP / 2) {
            b.phase = 2;
            addScore(200, Lang.t("進入第二階段", "Phase 2"), b.x, b.y - 58);
            emit(EvType.PHASE2, b.x, b.y - 16, null, 0);
        }
        if (b.hp <= 0) {
            b.hp = 0;
            over = true; won = true;
            addScore(500, Lang.t("勝利", "Victory"), b.x, b.y - 58);
            int fast = timeBonus(time);
            if (fast > 0) addScore(fast, Lang.t("速攻獎勵", "Speed bonus"), b.x, b.y - 72);
            emit(EvType.WIN, b.x, b.y - 16, null, 0);
        }
    }

    // ---------------------------------------------------------------- hazards

    private static final int HIT = 0, DODGED = 1, IMMUNE = 2;

    private int hurtPlayer(int dmg, String by, double sx, double sy) {
        if (over || p.invuln > 0) return IMMUNE;
        if (p.dodgeInvuln()) return DODGED;
        // a single blow is fatal (except in the tutorial)
        if (!practice) p.hp = 0;
        p.invuln = 0.9;
        p.hurtFlash = 0.3;
        p.combo = 0;
        p.lastType = null;
        p.hitEpoch++;
        releaseGrab();
        // the current action is lost but the rest of the plan carries on after the stagger
        double kx = p.x - sx, ky = p.y - sy;
        p.cur = PlanItem.move(ActionType.HURT, kx, ky, ActionType.HURT.steps);
        p.step = 0; p.fizzled = false; p.missed = false;
        p.mdx = p.cur.dx; p.mdy = p.cur.dy;
        hitsTaken++;
        emit(EvType.HIT, p.x, p.y - 13, by, dmg);
        addScore(-120, Lang.t("被" + by + "擊倒", "Felled by " + by), p.x, p.y - 40);
        if (p.hp <= 0 && !practice) {
            p.hp = 0;
            over = true; won = false;
            emit(EvType.LOSE, p.x, p.y - 13, null, 0);
        }
        return HIT;
    }

    /** Resolves a hazard at distance {@code dist} with reach {@code r}; ground hazards miss while airborne. */
    private void contact(Obj o, double dist, double r, int dmg, String by, boolean ground, double sx, double sy) {
        if (o.hit) return;
        if (dist < r + BODY_R) {
            // airborne she clears ground attacks; mid jump kick she also clears barrages, but not lightning from above
            if (p.airborne() && (ground || (p.is(ActionType.KICK) && o.kind != Kind.MARK))) { o.grazed = true; return; }
            int res = hurtPlayer(dmg, by, sx, sy);
            if (res == HIT) o.hit = true; else if (res == DODGED) { o.grazed = true; o.perfect = true; }
        } else if (dist < r + BODY_R + 8) {
            o.grazed = true;
        }
    }

    private void updateObj(Obj o) {
        o.t += DT;
        boolean was = o.active;
        o.active = o.t >= o.delay;
        if (o.active && !was) onActivate(o);
        if (o.t >= o.life) { kill(o); return; }
        switch (o.kind) {
            case AVADA: {
                if (!o.active) break;
                double d = segDist(p.x, p.y, o.x, o.y, o.x + o.vx * o.c, o.y + o.vy * o.c);
                contact(o, d, 3, 40, Lang.t("索命咒", "Avada Kedavra"), false, o.x, o.y);
                break;
            }
            case CRUCIO: {
                if (!o.active) {
                    double ang = time * 6 + o.idx * 0.8;
                    o.x = b.x + Math.cos(ang) * 10;
                    o.y = b.y + Math.sin(ang) * 6;
                    break;
                }
                if (o.e == 0) steer(o, p.x, p.y, 1.9, o.c);
                o.x += o.vx * DT; o.y += o.vy * DT;
                if (p.shield() && Math.hypot(o.x - p.x, o.y - p.y) < 20) { shieldBounce(o, 15, 60); break; }
                contact(o, Math.hypot(o.x - p.x, o.y - p.y), 3, 12, Lang.t("酷刑咒", "Cruciatus Curse"), false, o.x, o.y);
                if (o.hit || inObstacle(o.x, o.y, 0) || outOfMap(o.x, o.y, 10)) kill(o);
                break;
            }
            case SERPENT: {
                if (!o.active) break;
                steer(o, p.x, p.y, 1.5, o.c);
                double[] q = { o.x + o.vx * DT, o.y + o.vy * DT };
                resolve(q, 6);
                o.x = q[0]; o.y = q[1];
                o.d -= DT;
                if (o.d <= 0) {
                    o.d = 0.07;
                    Obj f = hostile(Kind.FIRE_TILE, 0);
                    f.castId = -1;
                    f.x = o.x; f.y = o.y; f.life = 2.4; f.active = true;
                }
                contact(o, Math.hypot(o.x - p.x, o.y - p.y), 8, 18, Lang.t("火焰巨蛇", "Fire Serpent"), true, o.x, o.y);
                break;
            }
            case FIRE_TILE:
                contact(o, Math.hypot(o.x - p.x, o.y - p.y), 5, 12, Lang.t("火焰", "Flames"), true, o.x, o.y);
                o.grazed = false; o.perfect = false;
                break;
            case CONE: {
                if (!o.active || o.hit || losBlocked(o.x, o.y, p.x, p.y)) break;
                double dx = p.x - o.x, dy = p.y - o.y, d = Math.hypot(dx, dy);
                double diff = Math.abs(Math.atan2(Math.sin(Math.atan2(dy, dx) - o.a), Math.cos(Math.atan2(dy, dx) - o.a)));
                // how far outside the wedge Malfoy stands, in pixels
                double out = Math.max(d - o.c, d * Math.sin(Math.max(0, diff - o.b)));
                if (diff > Math.PI / 2 + o.b) out = Math.max(out, d);
                contact(o, Math.max(0, out), 0, 16, Lang.t("震退咒", "Depulso"), false, o.x, o.y);
                break;
            }
            case MARK: {
                if (!o.active || o.hit) break;
                double d = Math.hypot(o.x - p.x, o.y - p.y);
                if (p.shield() && d < o.a + BODY_R) {
                    o.hit = true;
                    addScore(30, Lang.t("擋下", "Blocked"), p.x, p.y - 40);
                    emit(EvType.BLOCK, p.x, p.y - 20, null, 0);
                } else {
                    contact(o, d, o.a, 15, Lang.t("黑魔標記", "Dark Mark"), false, o.x, o.y);
                }
                break;
            }
            case NAGINI: {
                if (!o.active) break;
                o.c += o.d * DT;
                double hx = o.x + o.vx * o.c, hy = o.y + o.vy * o.c, tail = Math.max(0, o.c - 60);
                double d = segDist(p.x, p.y, o.x + o.vx * tail, o.y + o.vy * tail, hx, hy);
                contact(o, d, 7, 18, Lang.t("娜吉妮", "Nagini"), true, hx, hy);
                if (tail > o.a) kill(o);
                break;
            }
            case SMOKE: {
                if (!o.active) break;
                o.c = Math.min(o.d, o.c + 430 * DT);
                double hx = o.x + o.vx * o.c, hy = o.y + o.vy * o.c;
                b.x = hx; b.y = hy;
                contact(o, Math.hypot(hx - p.x, hy - p.y), 11, 15, Lang.t("黑煙突襲", "Smoke Rush"), false, hx, hy);
                if (o.c >= o.d) kill(o);
                break;
            }
            case SHARD: {
                if (!o.active) break;
                o.x += o.vx * DT; o.y += o.vy * DT;
                if (p.shield() && Math.hypot(o.x - p.x, o.y - p.y) < 20) { shieldBounce(o, 8, 20); break; }
                contact(o, Math.hypot(o.x - p.x, o.y - p.y), 2, 8, Lang.t("玻璃碎片", "Glass shard"), false, o.x, o.y);
                if (o.hit || inObstacle(o.x, o.y, 0) || outOfMap(o.x, o.y, 10)) kill(o);
                break;
            }
            case FIRE_RING: {
                if (!o.active) break;
                o.a += o.c * DT;
                double d = Math.hypot(p.x - o.x, p.y - o.y);
                if (Math.abs(d - o.a) < 4 + BODY_R + 8 && !losBlocked(o.x, o.y, p.x, p.y))
                    contact(o, Math.abs(d - o.a), 4, 16, Lang.t("厲火之環", "Fiendfyre Ring"), true, o.x, o.y);
                if (o.a > 1100 || (o.d > 0 && o.a > o.d)) kill(o);
                break;
            }
            case INFERI: {
                // stage 0 shambles toward Malfoy; at arm's length stage 1 raises its arms (0.45 s wind-up),
                // then grabs; stage 2 recovers for 0.7 s. d = stage timer.
                if (!o.active) break;
                double dx = p.x - o.x, dy = p.y - o.y, len = Math.max(1e-6, Math.hypot(dx, dy));
                if (p.shield() && len < 22) {
                    o.hit = true;
                    kill(o);
                    addScore(20, Lang.t("擊退陰屍", "Inferius repelled"), p.x, p.y - 40);
                    emit(EvType.BLOCK, o.x, o.y, null, 0);
                    break;
                }
                if (o.stage == 0) {
                    double[] q = { o.x + dx / len * o.c * DT, o.y + dy / len * o.c * DT };
                    resolve(q, 5);
                    o.x = q[0]; o.y = q[1];
                    if (len < 20) { o.stage = 1; o.d = 0.45; emit(EvType.ENEMY_ATTACK, o.x, o.y - 18, Lang.t("陰屍即將撲抓", "Inferius about to lunge"), 0); }
                } else if (o.stage == 1) {
                    o.d -= DT;
                    if (o.d <= 0) {
                        o.stage = 2; o.d = 0.7;
                        o.hit = false;
                        contact(o, len, 17, 10, Lang.t("陰屍", "Inferius"), true, o.x, o.y);
                        if (o.hit) emit(EvType.MELEE, (o.x + p.x) / 2, (o.y + p.y) / 2 - 10, null, 0);
                        if (o.grazed && !o.hit) { o.grazed = false; o.perfect = false; graze(15); }
                    }
                } else {
                    o.d -= DT;
                    if (o.d <= 0) o.stage = 0;
                }
                break;
            }
            case MINION: {
                // Death Eater: keeps ~130 px from Malfoy, circles her, and fires a green bolt every 2.4 s.
                // b = hp, d = time to next shot, idx = circling direction, stage 1 = winding up, a = flinch timer.
                if (!o.active) break;
                if (o.a > 0) { o.a -= DT; break; }
                double dx = p.x - o.x, dy = p.y - o.y, d = Math.max(1e-6, Math.hypot(dx, dy)), ux = dx / d, uy = dy / d;
                double radial = clamp((d - 130) / 40, -1, 1);
                double[] q = { o.x + (ux * radial - uy * o.idx * 0.7) * 52 * DT, o.y + (uy * radial + ux * o.idx * 0.7) * 52 * DT };
                resolve(q, 6);
                o.x = q[0]; o.y = q[1];
                o.d -= DT;
                int prevStage = o.stage;
                o.stage = o.d <= 0.5 ? 1 : 0;
                if (o.stage == 1 && prevStage == 0) emit(EvType.ENEMY_ATTACK, o.x, o.y - 20, Lang.t("食死人即將發射綠光", "Death Eater about to fire"), 0);
                if (o.d <= 0) {
                    o.d = 2.0;
                    Obj s = hostile(Kind.M_BOLT, 0);
                    s.castId = -1; s.active = true;
                    s.x = o.x; s.y = o.y; s.life = 4;
                    aim(s, p.x, p.y, 170);
                }
                break;
            }
            case M_BOLT: {
                o.x += o.vx * DT; o.y += o.vy * DT;
                if (p.shield() && Math.hypot(o.x - p.x, o.y - p.y) < 20) { shieldBounce(o, 8, 30); break; }
                contact(o, Math.hypot(o.x - p.x, o.y - p.y), 2, 8, Lang.t("食死人", "Death Eater"), false, o.x, o.y);
                if (o.hit || inObstacle(o.x, o.y, 0) || outOfMap(o.x, o.y, 10)) kill(o);
                break;
            }
            case P_DENS: case P_EXP: case P_STUP: case P_REFLECT: case P_SNAKE: {
                boolean snake = o.kind == Kind.P_SNAKE;
                double[] tg = snake ? (o.e == 1 ? new double[] { o.c, o.d } : snakeTarget(o)) : target(o.x, o.y);
                if (tg != null && (o.e == 0 || snake)) steer(o, tg[0], tg[1], snake ? 5.0 : 3.0, o.a);
                if (snake) {
                    // once at its ambush point the snake coils and waits instead of circling
                    boolean lurk = tg != null && Math.hypot(tg[0] - o.x, tg[1] - o.y) < 6;
                    double[] q = lurk ? new double[] { o.x, o.y } : new double[] { o.x + o.vx * DT, o.y + o.vy * DT };
                    resolve(q, 3);
                    o.x = q[0]; o.y = q[1];
                    for (Obj z : objs) {
                        if (z.dead || z.kind != Kind.FIRE_RING || !z.active) continue;
                        if (Math.abs(Math.hypot(o.x - z.x, o.y - z.y) - z.a) > 8) continue;
                        z.hit = true;
                        kill(z);
                        cancels++;
                        addScore(40, Lang.t("黑蛇吞下火環!", "Snake swallowed the ring!"), o.x, o.y - 24);
                        emit(EvType.CANCEL, o.x, o.y, "FIRE_RING", 0);
                        kill(o);
                        break;
                    }
                    if (o.dead) break;
                    for (Obj z : objs) {
                        if (z.dead || z.kind != Kind.NAGINI || !z.active) continue;
                        double tail = Math.max(0, z.c - 60);
                        double d = segDist(o.x, o.y, z.x + z.vx * tail, z.y + z.vy * tail, z.x + z.vx * z.c, z.y + z.vy * z.c);
                        if (d > 14) continue;
                        z.hit = true;
                        kill(z);
                        cancels++;
                        addScore(40, Lang.t("攔下娜吉妮!", "Nagini stopped!"), o.x, o.y - 24);
                        emit(EvType.CANCEL, o.x, o.y, "NAGINI", 0);
                        kill(o);
                        break;
                    }
                    if (o.dead) break;
                } else {
                    o.x += o.vx * DT; o.y += o.vy * DT;
                    if (inObstacle(o.x, o.y, 0)) { kill(o); emit(EvType.BLOCK, o.x, o.y, null, 0); break; }
                    // each spell answers its own kind of threat and the two cancel out (Stupefy punches through three)
                    for (Obj z : objs) {
                        if (z.dead || !z.hostile || !counters(o.kind, z.kind)) continue;
                        boolean beam = z.kind == Kind.AVADA;
                        if (!beam && !z.active) continue;
                        double d = beam ? segDist(o.x, o.y, z.x, z.y, z.x + z.vx * z.c, z.y + z.vy * z.c) : Math.hypot(z.x - o.x, z.y - o.y);
                        if (d > (z.kind == Kind.SERPENT ? 10 : 6)) continue;
                        if (o.kind == Kind.P_DENS && ++z.chips < 2) {
                            // the first light attack only cracks it; the bolt is spent and the projectile flies on
                            emit(EvType.CANCEL, z.x, z.y, "CHIP", 0);
                            kill(o);
                            break;
                        }
                        z.hit = true;
                        kill(z);
                        cancels++;
                        if (beam) addScore(40, Lang.t("咒語相撞!", "Spells collide!"), o.x, o.y - 24); else addScore(10, Lang.t("抵銷", "Cancelled"), z.x, z.y - 20);
                        // the event names what was cancelled, so each kind gets its own blast and sound
                        String what = beam ? "AVADA" : o.kind == Kind.P_REFLECT ? "REFLECT" : z.kind.name();
                        emit(EvType.CANCEL, beam ? o.x : z.x, beam ? o.y : z.y, what, 0);
                        if (--o.d <= 0) { kill(o); break; }
                    }
                    if (o.dead) break;
                }
                for (Obj z : objs) {
                    if (z.dead || !z.active) continue;
                    if (z.kind == Kind.INFERI && Math.hypot(z.x - o.x, z.y - o.y) < 7) {
                        kill(z);
                        z.hit = true;
                        addScore(20, Lang.t("擊碎陰屍", "Inferius smashed"), z.x, z.y - 20);
                        emit(EvType.BOSS_HIT, z.x, z.y - 8, null, 0);
                        emit(EvType.CANCEL, z.x, z.y, "INFERI", 0);
                        if (!snake) { kill(o); break; }
                    } else if (z.kind == Kind.MINION && Math.hypot(z.x - o.x, z.y - o.y) < 8) {
                        damageMinion(z, (int) o.b);
                        kill(o);
                        break;
                    }
                }
                if (o.dead) break;
                if (pvp) {
                    if (!spellMeetsFoe(o) && outOfMap(o.x, o.y, 10)) kill(o);
                } else if (!b.hidden && Math.hypot(o.x - b.x, o.y - b.y) < (snake ? 10 : 9)) {
                    damageBoss((int) o.b, (o.kind == Kind.P_EXP && b.phase == 1) || o.kind == Kind.P_STUP, o.kind == Kind.P_STUP ? 1.5 : 0);
                    kill(o);
                } else if (outOfMap(o.x, o.y, 10)) kill(o);
                break;
            }
            default: break;
        }
    }

    // ---------------------------------------------------------------- two-player duel

    /** The longer a spell takes to read, the more clashing it can take: 1 for Densaugeo up to 4 for the snake. */
    static int cancelValue(ActionType a) {
        switch (a) {
            case DENSAUGEO: return 1;
            case EXPELLIARMUS: return 2;
            case STUPEFY: return 3;
            case SERPENSORTIA: return 4;
            default: return 1;
        }
    }

    static boolean pvpSpell(Obj o) {
        return o.kind == Kind.P_DENS || o.kind == Kind.P_EXP || o.kind == Kind.P_STUP || o.kind == Kind.P_SNAKE || o.kind == Kind.P_REFLECT;
    }

    /** One step of the duel: both players act, then every spell, then spells that meet wear each other down. */
    private void stepPvp() {
        ev.clear();
        if (over) return;
        steps++;
        time = steps * DT;
        int keep = me;
        for (int i = 0; i < 2; i++) { setMe(i); updatePlayer(); }
        for (int i = 0; i < objs.size(); i++) {
            Obj o = objs.get(i);
            if (o.dead) continue;
            setMe(o.owner);
            updateObj(o);
        }
        clashSpells();
        objs.removeIf(o -> o.dead);
        for (Player pl : ps) if (pl.hurtFlash > 0) pl.hurtFlash -= DT;
        setMe(keep);
        boolean down0 = ps[0].ko || ps[0].hp <= 0, down1 = ps[1].ko || ps[1].hp <= 0;
        if (down0 || down1) {
            over = true;
            winner = down0 && down1 ? -1 : down0 ? 1 : 0;
            won = winner == me;
            if (down0) emit(EvType.LOSE, ps[0].x, ps[0].y - 13, null, 0);
            if (down1) emit(EvType.LOSE, ps[1].x, ps[1].y - 13, null, 0);
        }
    }

    /**
     * Spells of the two players that meet both lose the smaller one's cancel value; a spell worn down to
     * zero bursts. A Densaugeo (1) and a Stupefy (3): the Densaugeo bursts and the Stupefy flies on with 2.
     */
    private void clashSpells() {
        for (int i = 0; i < objs.size(); i++) {
            Obj a = objs.get(i);
            if (a.dead || !pvpSpell(a)) continue;
            for (int j = i + 1; j < objs.size() && !a.dead; j++) {
                Obj c = objs.get(j);
                if (c.dead || !pvpSpell(c) || c.owner == a.owner) continue;
                double r = PVP_CLASH_R + (a.kind == Kind.P_SNAKE ? 4 : 0) + (c.kind == Kind.P_SNAKE ? 4 : 0);
                if (Math.hypot(a.x - c.x, a.y - c.y) > r) continue;
                int m = Math.min(a.cv, c.cv);
                a.cv -= m; c.cv -= m;
                cancels++;
                emit(EvType.CANCEL, (a.x + c.x) / 2, (a.y + c.y) / 2, a.cv <= 0 && c.cv <= 0 ? "AVADA" : "CHIP", 0);
                if (c.cv <= 0) { c.hit = true; kill(c); }
                if (a.cv <= 0) { a.hit = true; kill(a); }
            }
        }
    }

    /**
     * A spell of p's reaching q: it passes a dodging, vanished or airborne player, bounces off Protego
     * (it becomes q's and flies straight back), and otherwise it is a knockout. True once it has hit something.
     */
    private boolean spellMeetsFoe(Obj o) {
        double r = PVP_HIT_R + (o.kind == Kind.P_SNAKE ? 2 : 0);
        double d = Math.hypot(o.x - q.x, o.y - (q.y - q.z()));
        if (d >= r || o.hit) return false;
        if (q.vanished() || q.dodgeInvuln() || q.airborne()) {
            if (!o.grazed) { o.grazed = true; emit(EvType.FIZZLE, q.x, q.y - 30, Lang.t("閃過", "Dodged"), 0); }
            return false;
        }
        if (q.shield()) {
            o.owner = q.side;
            o.e = 1;
            // it flies straight back at whoever cast it
            double ux = p.x - q.x, uy = p.y - q.y, ul = Math.max(1e-6, Math.hypot(ux, uy));
            double sp = Math.max(o.a, Math.hypot(o.vx, o.vy));
            o.vx = ux / ul * sp; o.vy = uy / ul * sp;
            o.x = q.x + ux / ul * (r + 2); o.y = q.y + uy / ul * (r + 2);
            if (o.kind == Kind.P_SNAKE) { o.c = p.x; o.d = p.y; }
            emit(EvType.BLOCK, o.x, o.y, null, 0);
            return true;
        }
        o.hit = true;
        kill(o);
        q.ko = true;
        stagger(q, o.x, o.y);
        emit(EvType.HIT, q.x, q.y - 13, Lang.t("中咒", "Spell hit"), 0);
        return true;
    }

    /** q staggers away from (sx, sy): the current action and the rest of the plan are lost. */
    private void stagger(Player v, double sx, double sy) {
        if (v.grabbed) {
            // dropping whoever they were throwing
            v.grabbed = false;
            Player held = ps[1 - v.side];
            held.carried = false; held.lift = 0; held.rot = 0;
        }
        v.queue.clear();
        v.hitEpoch++;
        v.cur = PlanItem.move(ActionType.HURT, v.x - sx, v.y - sy, ActionType.HURT.steps);
        v.step = 0; v.fizzled = false; v.missed = false;
        v.mdx = v.cur.dx; v.mdy = v.cur.dy;
        v.hurtFlash = 0.3;
    }

    /** A close-combat blow from p on q: health goes down, and (unless q is being thrown) q staggers. */
    private void hurtMelee(int dmg, boolean stun) {
        if (over) return;
        if (!q.carried && (q.vanished() || q.dodgeInvuln())) {
            emit(EvType.FIZZLE, q.x, q.y - 30, Lang.t("閃過", "Dodged"), 0);
            return;
        }
        q.hp = Math.max(0, q.hp - dmg);
        q.hurtFlash = 0.3;
        if (stun && !q.carried && q.downT <= 0) stagger(q, p.x, p.y);
        emit(EvType.HIT, q.x, q.y - 13, null, dmg);
    }

    /**
     * Both players have finished their plans (and nobody is being thrown or lying on the floor). Spells still in
     * flight carry on into the next bullet time, where the preview shows them coming.
     */
    boolean pvpSettled() {
        for (Player pl : ps) if (!pl.idle() || pl.carried || pl.downT > 0) return false;
        return true;
    }

    /** Which of Voldemort's attacks each spell can meet head-on and cancel. */
    static boolean counters(Kind spell, Kind threat) {
        switch (spell) {
            // the light attack answers every barrage (glass, bolts, curse orbs), two shots for one
            case P_DENS: return threat == Kind.SHARD || threat == Kind.M_BOLT || threat == Kind.CRUCIO;
            case P_EXP: return threat == Kind.AVADA;
            case P_STUP: return threat == Kind.CRUCIO || threat == Kind.SERPENT;
            case P_REFLECT: return threat == Kind.CRUCIO;
            default: return false;
        }
    }

    /**
     * The snake lies in wait for Nagini: once a dash lane is showing (or she is already on it) within reach,
     * it heads for the nearest point of that lane; otherwise it goes for the nearest enemy.
     */
    private double[] snakeTarget(Obj s) {
        double[] best = null;
        double bd = 220;
        for (Obj z : objs) {
            if (z.dead || z.kind != Kind.NAGINI || (!z.active && z.delay - z.t > 1.0)) continue;
            double from = z.active ? z.c : 0;
            double t = clamp((s.x - z.x) * z.vx + (s.y - z.y) * z.vy, from, z.a);
            double lx = z.x + z.vx * t, ly = z.y + z.vy * t, d = Math.hypot(lx - s.x, ly - s.y);
            if (d < bd) { bd = d; best = new double[] { lx, ly }; }
        }
        return best != null ? best : target(s.x, s.y);
    }

    /** Where Malfoy's spells aim: the nearest enemy (Voldemort or a Death Eater), or null if none is visible. */
    private double[] target(double x, double y) {
        if (pvp) return foeSeen() ? new double[] { q.x, q.y } : null;
        double[] best = b.hidden ? null : new double[] { b.x, b.y };
        double bd = b.hidden ? 1e18 : Math.hypot(b.x - x, b.y - y);
        for (Obj m : objs) {
            if (m.kind != Kind.MINION || !m.active || m.dead) continue;
            double d = Math.hypot(m.x - x, m.y - y);
            if (d < bd) { bd = d; best = new double[] { m.x, m.y }; }
        }
        return best;
    }

    private void damageMinion(Obj m, int dmg) {
        m.b -= dmg;
        double hx = m.x - p.x, hy = m.y - p.y, hl = Math.max(1e-6, Math.hypot(hx, hy));
        m.vx = hx / hl; m.vy = hy / hl; m.a = 0.3;
        double[] q = { m.x + m.vx * 6, m.y + m.vy * 6 };
        resolve(q, 6);
        m.x = q[0]; m.y = q[1];
        emit(EvType.BOSS_HIT, m.x, m.y - 12, null, dmg);
        if (m.b <= 0) {
            m.hit = true;
            kill(m);
            addScore(40, Lang.t("擊倒食死人", "Death Eater down"), m.x, m.y - 24);
            emit(EvType.APPARATE, m.x, m.y - 10, null, 0);
        }
    }

    int inferiAlive() {
        int n = 0;
        for (Obj o : objs) if (o.kind == Kind.INFERI && !o.dead) n++;
        return n;
    }

    int minionsAlive() {
        int n = 0;
        for (Obj o : objs) if (o.kind == Kind.MINION && !o.dead) n++;
        return n;
    }

    private void onActivate(Obj o) {
        // every hazard announces the moment it goes live (sound cues; Avada and Mark also flash)
        switch (o.kind) {
            case CRUCIO: case SERPENT: case FIRE_RING: case NAGINI: case SHARD: case CONE: case SMOKE:
                emit(EvType.STRIKE, o.x, o.y, o.kind.name(), 0);
                break;
            default: break;
        }
        switch (o.kind) {
            case CRUCIO: case SERPENT: {
                double ang = o.kind == Kind.CRUCIO ? o.a : o.a;
                double sp = o.c;
                o.vx = Math.cos(ang) * sp; o.vy = Math.sin(ang) * sp;
                if (o.kind == Kind.SERPENT) { o.x = b.x; o.y = b.y; }
                break;
            }
            case SMOKE:
                if (o.idx == 0) emit(EvType.APPARATE, b.x, b.y, null, 0);
                b.hidden = true;
                break;
            case SHARD: case FIRE_RING:
                if (o.e == 0) { o.x = b.x; o.y = b.y; }
                break;
            case AVADA: case MARK:
                emit(EvType.STRIKE, o.x, o.y, o.kind.name(), 0);
                break;
            default: break;
        }
    }

    private void kill(Obj o) {
        if (o.dead) return;
        o.dead = true;
        if (o.kind == Kind.SERPENT && o.active && !o.hit && o.t >= o.life) {
            // a burnt-out serpent bursts into a small ring of fire
            Obj r = hostile(Kind.FIRE_RING, 0);
            r.castId = -1; r.active = true; r.e = 1;
            r.x = o.x; r.y = o.y; r.c = 120; r.d = 70;
        }
        if (!o.hostile && !outOfMap(o.x, o.y, 0)) emit(EvType.IMPACT, o.x, o.y, null, o.kind.ordinal());
        if (o.hostile && o.active && o.perfect && !o.hit) {
            p.mana = Math.min(MAX_MANA, p.mana + 8);
            addScore(o.kind == Kind.SHARD ? 10 : 30, Lang.t("完美閃避", "Perfect dodge"), p.x, p.y - 34);
        } else if (o.hostile && o.active && o.grazed && !o.hit) graze(o.kind == Kind.SHARD ? 5 : 15);
        if (o.kind == Kind.SMOKE && o.active && o.e == 1) {
            b.hidden = false;
            b.x = o.a; b.y = o.b;
            emit(EvType.APPARATE, b.x, b.y, null, 0);
        }
    }

    private void graze(int pts) {
        p.mana = Math.min(MAX_MANA, p.mana + 4);
        addScore(pts, Lang.t("驚險閃過", "Close call"), p.x, p.y - 34);
    }

    // ---------------------------------------------------------------- geometry

    static boolean inObstacle(double x, double y, double pad) {
        for (double[] ob : OBST) if (Math.hypot(x - ob[0], y - ob[1]) < ob[2] + pad) return true;
        return false;
    }

    static boolean outOfMap(double x, double y, double m) { return x < -m || x > MAPW + m || y < -m || y > MAPH + m; }

    /** Pushes a circle of radius r out of the scenery and back inside the hall. */
    static void resolve(double[] q, double r) {
        for (int pass = 0; pass < 2; pass++) {
            for (double[] ob : OBST) {
                double dx = q[0] - ob[0], dy = q[1] - ob[1], d = Math.hypot(dx, dy), min = ob[2] + r;
                if (d < min) {
                    if (d < 1e-6) { dx = 1; dy = 0; d = 1; }
                    q[0] = ob[0] + dx / d * min; q[1] = ob[1] + dy / d * min;
                }
            }
            q[0] = clamp(q[0], MIN_X, MAX_X);
            q[1] = clamp(q[1], MIN_Y, MAX_Y);
        }
    }

    static boolean losBlocked(double x0, double y0, double x1, double y1) {
        for (double[] ob : OBST) {
            if (Math.hypot(x0 - ob[0], y0 - ob[1]) < ob[2] || Math.hypot(x1 - ob[0], y1 - ob[1]) < ob[2]) continue;
            if (segDist(ob[0], ob[1], x0, y0, x1, y1) < ob[2] - 1) return true;
        }
        return false;
    }

    /** Distance along a ray until it hits scenery, or {@code max}. */
    static double rayLen(double x, double y, double ux, double uy, double max) {
        double best = max;
        for (double[] ob : OBST) {
            double fx = x - ob[0], fy = y - ob[1];
            double bq = fx * ux + fy * uy, c = fx * fx + fy * fy - ob[2] * ob[2];
            double disc = bq * bq - c;
            if (disc < 0) continue;
            double t = -bq - Math.sqrt(disc);
            if (t > 0 && t < best) best = t;
        }
        return best;
    }

    private static void steer(Obj o, double tx, double ty, double turnRate, double speed) {
        double ang = Math.atan2(o.vy, o.vx);
        double want = Math.atan2(ty - o.y, tx - o.x);
        double diff = Math.atan2(Math.sin(want - ang), Math.cos(want - ang));
        ang += clamp(diff, -turnRate * DT, turnRate * DT);
        o.vx = Math.cos(ang) * speed; o.vy = Math.sin(ang) * speed;
    }

    // ---------------------------------------------------------------- helpers

    private void addScore(int amount, String label, double x, double y) {
        score += amount;
        String sign = amount >= 0 ? "+" : "";
        emit(EvType.SCORE, x, y, label == null ? sign + amount : label + " " + sign + amount, amount);
    }

    private void emit(EvType t, double x, double y, String text, int amount) { ev.add(new Ev(t, x, y, text, amount)); }

    private static void aim(Obj o, double tx, double ty, double speed) {
        double dx = tx - o.x, dy = ty - o.y, len = Math.max(1e-6, Math.hypot(dx, dy));
        o.vx = dx / len * speed; o.vy = dy / len * speed;
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
    static double ease(double t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }

    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 < 1e-9 ? 0 : clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
