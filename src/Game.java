import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Screen, camera, input and the bullet-time director. The simulation itself lives in World.
 * Between Voldemort's spells Malfoy is driven in real time; when he casts, time freezes and the
 * player directs a plan that is previewed by ghosts.
 */
final class Game extends JPanel implements KeyListener {
    static final int W = 1280, H = 720, SCALE = 3, LW = 427, LH = 240;
    static final int PANEL_Y = 530;
    static final int VIEW_H = PANEL_Y / SCALE;   // low-res rows left visible above the bottom panel
    static final double DT = World.DT;

    enum State { TITLE, PLAN, RUN, END, REPLAY }

    /** A button in the action bar. Moves carry a direction; a null type is the run/walk toggle. */
    static final class Card {
        final String key, zh, en; final ActionType type; final int dx, dy;
        Card(String key, String zh, String en, ActionType type, int dx, int dy) { this.key = key; this.zh = zh; this.en = en; this.type = type; this.dx = dx; this.dy = dy; }
        String name() { return Lang.t(zh, en); }
        String keyName() { return "空白".equals(key) ? Lang.t("空白", "Space") : key; }
    }

    static Card card(String key, ActionType t) { return new Card(key, t.zh, t.abbr, t, 0, 0); }

    static final Card[] CARDS = {
        new Card("W", "移 ↑", "Move ↑", ActionType.RUN, 0, -1), new Card("A", "移 ←", "Move ←", ActionType.RUN, -1, 0),
        new Card("S", "移 ↓", "Move ↓", ActionType.RUN, 0, 1), new Card("D", "移 →", "Move →", ActionType.RUN, 1, 0),
        new Card("V", "跑/漫", "Run/Walk", null, 0, 0), card("空白", ActionType.WAIT),
        card("Q", ActionType.ROLL), card("E", ActionType.DASH), card("Z", ActionType.BACKFLIP), card("C", ActionType.APPARATE),
        card("1", ActionType.DENSAUGEO), card("2", ActionType.EXPELLIARMUS), card("3", ActionType.STUPEFY), card("4", ActionType.SERPENSORTIA),
        card("F", ActionType.KICK), card("G", ActionType.FLURRY), card("R", ActionType.SPIN), card("T", ActionType.THROW),
        card("5", ActionType.PROTEGO),
    };
    static final int[] GROUP_START = { 0, 6, 10, 14, 18 };
    static String groupName(int i) {
        return Lang.en ? new String[] { "Move", "Dodge (cooldown)", "Ranged (charge → store → cast)", "Melee (cooldown · circle)", "Shield" }[i]
                       : new String[] { "移動", "閃避（冷卻）", "遠程（讀條→儲存→施放）", "近戰（冷卻・圓圈命中）", "常駐" }[i];
    }

    static final class Ghost { final double t; final World.Player pl; final World.Boss boss;
        Ghost(double t, World.Player pl, World.Boss boss) { this.t = t; this.pl = pl; this.boss = boss; } }
    static final class Mark { final double t; final String text;
        Mark(double t, String text) { this.t = t; this.text = text; } }
    static final class Particle { double x, y, vx, vy, life, max, grav; Color c; int size; }
    static final class Popup { double x, y, life; String text; Color c; boolean big, shout; double tilt; }
    /** An expanding ring of light: spell flashes, impacts and shockwaves. A filled ring is a flash disc. */
    static final class Ring { double x, y, r, grow, life, max; Color c; int width; boolean fill; }
    /** A sweeping crescent: the trail of a melee strike. */
    static final class Arc { double x, y, r, a0, ext, life, max; Color c; int width; }
    /** One radial speed line of an impact frame. */
    static final class Streak { double x, y, ang, len, life, max; Color c; }

    State state = State.TITLE;
    World world;
    /**
     * Malfoy against a second player's Voldemort. Only Malfoy has bullet time: Voldemort acts in real time in his
     * turn, the moment her plan has played out (arrows float, Enter apparates, 6-0 - = [ ] ; ' or the number pad
     * pick one of his spells). He may choose the next spell while her plan plays; if his turn runs out unused,
     * she plans again.
     */
    boolean bossMode;
    /** The edition for sharing (SoloMain): only the duel against Voldemort, plus the tutorial. */
    static boolean soloOnly;
    /** The beginner's tutorial while it runs, else null. */
    Tutorial tutorial;
    /** Title buttons of the solo edition: the duel and the tutorial. */
    final Rectangle[] soloRects = { new Rectangle(W / 2 - 250, 420, 240, 54), new Rectangle(W / 2 + 10, 420, 240, 54) };
    boolean p2U, p2D, p2L, p2R;
    /** How long Voldemort's turn has lasted so far. */
    double p2Wait;
    static final double P2_TURN = 2.5;
    // two-player duel on one computer: who plans first this round, and which half of the round is being planned
    boolean pvp;
    int round, firstSide, pvpPhase, runSteps;
    double pvpWindow;
    /** Two-player duel: each side's plan for this round so far. Both cover the same shared 5 s; they take turns
     *  extending them (Enter hands over), and the round plays once both are full. */
    final ArrayList<World.PlanItem> plan0 = new ArrayList<>(), plan1 = new ArrayList<>();
    final boolean[] sideDone = new boolean[2];
    /** How much of the planning player's draft the other one has already seen: that part cannot be undone. */
    int turnBase;
    /** The second player's turn fills exactly this many steps (the first player's last turn; whatever is left
     *  unplanned is spent waiting), or -1: a first player's turn, as long as they like within the shared 5 s. */
    int allowSteps = -1, turnStartSteps;
    /** The other player's key poses in the preview (the second player sees the first one's plan). */
    final ArrayList<Ghost> foeGhosts = new ArrayList<>();
    static final double PVP_TURN = 5.0;
    /** The whole hall at once instead of following the duellist: U (or the button by the minimap) toggles it anywhere,
     *  F as well while a plan plays or in a replay. A two-player replay opens this way. */
    boolean fullView, fullBeforeReplay;
    /** The view toggle button, left of the minimap. */
    final Rectangle viewRect = new Rectangle(W - 290, 104, 104, 30);
    final BufferedImage full = new BufferedImage(World.MAPW, World.MAPH, BufferedImage.TYPE_INT_ARGB);
    /** Screen pixels per world pixel and the screen offset of the map, for the follow view or the whole hall. */
    double vk = SCALE, vox, voy;
    /** While a round plays the camera can be let go of: it then stays where it was dragged (Tab follows again). */
    boolean camLocked;
    double lockX, lockY;
    /** Two-player bullet time: preview only from the start of the other side's last turn (Y), or the whole plan. */
    boolean previewTurn;
    /** The step (from the round's start) at which each side's latest turn this round began, or -1. */
    final int[] turnBegin = { -1, -1 };
    /** The preview range toggle, under the hand-over button. */
    final Rectangle rangeRect = new Rectangle(W - 246, 104 + (int) Math.round(160.0 * World.MAPH / World.MAPW) + 12 + 46, 230, 30);
    /** The round was cut short because someone's fall is already settled: it plays on until the duel ends. */
    boolean doomedRun;
    /** The "hand over" button of a two-player bullet time. */
    // under the minimap, clear of the card explanations that pop up above the panel
    final Rectangle handRect = new Rectangle(W - 246, 104 + (int) Math.round(160.0 * World.MAPH / World.MAPW) + 12, 230, 38);
    /** Title screen: single duel and two-player buttons. */
    final Rectangle[] modeRects = { new Rectangle(W / 2 - 380, 420, 240, 54), new Rectangle(W / 2 - 120, 420, 240, 54), new Rectangle(W / 2 + 140, 420, 240, 54) };
    // replay: every duel is recorded as inputs; after it ends the whole fight can be watched straight through
    Replay rec;
    Replay.Player replay;
    World finalWorld;
    static final double[] SPEEDS = { 0.25, 0.5, 1, 2, 4 };
    int speedIdx = 2;
    boolean replayPaused;
    double replayAcc;
    long seed = System.nanoTime();
    final ArrayList<World.PlanItem> draft = new ArrayList<>();
    boolean walkMode;
    double lastMapWX = Double.NaN, lastMapWY = Double.NaN;
    boolean kUp, kDown, kLeft, kRight;

    // ghost preview of the plan being edited
    final ArrayList<World> snaps = new ArrayList<>();
    final ArrayList<Ghost> ghosts = new ArrayList<>();
    final ArrayList<double[]> path = new ArrayList<>();
    final ArrayList<Mark> hits = new ArrayList<>(), casts = new ArrayList<>();
    World planEndWorld;
    double previewEnd, cursorT, manualHold;
    /** Length of the current bullet time: Voldemort's attack plus his pause afterwards. */
    double planWindow = World.POST_CAST, castLen;
    int previewScore, previewFizzles;
    /** Step (from now) at which the previewed plan has fully played out, hits and staggers included. */
    int previewPlanEnd;
    boolean previewDeath;

    final ArrayList<Particle> parts = new ArrayList<>();
    final ArrayList<Popup> pops = new ArrayList<>();
    final ArrayList<Ring> rings = new ArrayList<>();
    final ArrayList<Arc> arcs = new ArrayList<>();
    final ArrayList<Streak> streaks = new ArrayList<>();
    /** Game feel: camera kick (screen px), zoom punch, white impact frame, slow motion. */
    double kickX, kickY, zoom, whiteT, slowT, chargeFxT;
    /** Hit-stop: the duel freezes for a split second on a heavy blow. */
    double hitStop;
    String banner, toast, planReason = "";
    Color bannerColor = Color.WHITE;
    double bannerT, toastT, shake, flashT, btAnim, animT, acc, endDelay;
    Color flashColor = Color.RED;
    boolean help, shiftDown;
    double camX, camY;
    /** Bullet-time camera: how far the view has been panned away from Malfoy (world pixels). */
    double panX, panY;
    boolean panning, kPanL, kPanR, kPanU, kPanD;
    double panStartLX, panStartLY, panStartX, panStartY;

    final BufferedImage low = new BufferedImage(LW, LH, BufferedImage.TYPE_INT_ARGB);
    final BufferedImage map = Sprites.map(), pillar = Sprites.pillarImg(), fountain = Sprites.fountainImg();
    final Font fBig, fTitle, fMid, fSmall, fTiny, fCard;
    final Rectangle[] cardRects = new Rectangle[CARDS.length];
    int hover = -1;
    /** Long press: a card or skill key held this long shows its details instead of acting. */
    static final double LONG_PRESS = 0.4;
    int pressCard = -1, heldKey = -1, heldCard = -1;
    double pressT, keyHeldT;
    double mouseLX = -1, mouseLY = -1;
    double viewScale = 1, viewX, viewY;
    long lastNs = System.nanoTime();
    final java.util.Random fxRng = new java.util.Random();
    final Timer timer;

    Game(double scale) {
        setPreferredSize(new Dimension((int) (W * scale), (int) (H * scale)));
        setBackground(Color.BLACK);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        String fam = pickFont();
        fTitle = new Font(fam, Font.BOLD, 64);
        fBig = new Font(fam, Font.BOLD, 30);
        fMid = new Font(fam, Font.BOLD, 19);
        fSmall = new Font(fam, Font.PLAIN, 15);
        fTiny = new Font(fam, Font.PLAIN, 12);
        fCard = new Font(fam, Font.BOLD, 15);
        int x = 18;
        for (int i = 0; i < cardRects.length; i++) {
            for (int gs : GROUP_START) if (gs == i && i > 0) x += 10;
            cardRects[i] = new Rectangle(x, 622, 54, 74);
            x += 58;
        }
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { track(e); }
            @Override public void mouseDragged(MouseEvent e) {
                track(e);
                if (panning) { panX = panStartX - (mouseLX - panStartLX) / SCALE; panY = panStartY - (mouseLY - panStartLY) / SCALE; clampPan(); return; }
                if (state == State.PLAN) scrubTo(mouseLX, mouseLY);
            }
            @Override public void mouseExited(MouseEvent e) { mouseLX = -1; hover = -1; }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                track(e);
                if (state == State.TITLE && !help) {
                    for (int i = 0; i < langRects.length; i++) if (langRects[i].contains(mouseLX, mouseLY)) setLanguage(i == 1);
                    if (soloOnly) {
                        if (soloRects[0].contains(mouseLX, mouseLY)) newGame();
                        else if (soloRects[1].contains(mouseLX, mouseLY)) newTutorial();
                        return;
                    }
                    if (modeRects[0].contains(mouseLX, mouseLY)) newGame();
                    else if (modeRects[1].contains(mouseLX, mouseLY)) newPvp();
                    else if (modeRects[2].contains(mouseLX, mouseLY)) newBossGame();
                    return;
                }
                if (world != null && !help && viewRect.contains(mouseLX, mouseLY)) { toggleView(); return; }
                // a two-player round or replay playing out: drag anywhere on the map to move the camera freely
                if (pvp && !fullView && (state == State.RUN || state == State.REPLAY) && mouseLY < PANEL_Y) {
                    if (minimapClick(mouseLX, mouseLY)) return;
                    detachCam();
                    panning = true; panStartLX = mouseLX; panStartLY = mouseLY; panStartX = panX; panStartY = panY;
                    return;
                }
                if (state == State.REPLAY) { replayClick(mouseLX, mouseLY); return; }
                if (help || world == null || state != State.PLAN) return;
                if (pvp && handRect.contains(mouseLX, mouseLY)) { execute(); return; }
                if (pvp && rangeRect.contains(mouseLX, mouseLY)) { togglePreviewRange(); return; }
                if (state == State.PLAN) {
                    // middle drag (or Ctrl + left drag) pans the bullet-time camera; a click on the minimap jumps there
                    boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
                    if (SwingUtilities.isMiddleMouseButton(e) || (ctrl && SwingUtilities.isLeftMouseButton(e))) {
                        panning = true; panStartLX = mouseLX; panStartLY = mouseLY; panStartX = panX; panStartY = panY;
                        return;
                    }
                    if (minimapClick(mouseLX, mouseLY)) return;
                }
                if (hover >= 0) { pressCard = hover; pressT = 0; return; }
                if (state == State.PLAN && scrubTo(mouseLX, mouseLY)) return;
                if (onMap(mouseLX, mouseLY)) {
                    double wx = mouseWX(), wy = mouseWY();
                    boolean roll = SwingUtilities.isRightMouseButton(e) || (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                    if (roll) command(dirItem(ActionType.ROLL, wx, wy)); else runTo(wx, wy);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                track(e);
                if (panning) { panning = false; return; }
                if (pressCard >= 0 && pressT < LONG_PRESS && hover == pressCard && !help) clickCard(CARDS[pressCard]);
                pressCard = -1;
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        timer = new Timer(15, e -> tick());
        timer.start();
    }

    private void track(MouseEvent e) {
        mouseLX = (e.getX() - viewX) / viewScale;
        mouseLY = (e.getY() - viewY) / viewScale;
        shiftDown = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        hover = cardAt(mouseLX, mouseLY);
        if (world != null && onMap(mouseLX, mouseLY)) { lastMapWX = mouseWX(); lastMapWY = mouseWY(); }
    }

    double mouseWX() { return camX + (mouseLX - vox) / vk; }
    double mouseWY() { return camY + (mouseLY - voy) / vk; }

    static String pickFont() {
        HashSet<String> have = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : new String[] { "Microsoft JhengHei UI", "Microsoft JhengHei", "PingFang TC", "Noto Sans CJK TC", "Microsoft YaHei", "SimHei" })
            if (have.contains(f)) return f;
        return Font.DIALOG;
    }

    // ------------------------------------------------------------------ flow

    void newGame() { startSolo(false); }

    static final double TUTORIAL_WINDOW = 5.0;

    void tutorialTick() { if (tutorial != null && world != null && state != State.TITLE) tutorial.tick(this); }

    /** The tutorial: a duel against Voldemort in which Malfoy cannot fall, with lesson cards. */
    void newTutorial() { startSolo(true); }

    void startSolo(boolean practice) {
        pvp = false;
        bossMode = false;
        long sd = seed++;
        world = new World(sd);
        world.practice = practice;
        rec = new Replay(sd);
        if (practice) rec.start = world.copy(); // the replay starts from the same unfalling Malfoy
        tutorial = practice ? new Tutorial() : null;
        Audio.music(Audio.MUSIC_DUEL);
        state = State.RUN;
        walkMode = false;
        parts.clear(); pops.clear(); rings.clear(); draft.clear();
        banner = null; toast = null; help = false;
        snapCamera(world.p.x, world.p.y);
        // Voldemort opens the duel; the first bullet time comes just before his first spell lands
        String reason = null;
        for (int i = 0; i < 600 && reason == null; i++) { world.step(); reason = handle(world.ev); }
        enterPlan(reason == null ? Lang.t("決鬥開始", "The duel begins") : reason);
    }

    /** Malfoy (player 1, bullet time) against Voldemort played by player 2 (real time). Voldemort has the first turn. */
    void newBossGame() {
        pvp = false;
        bossMode = true;
        tutorial = null;
        long sd = seed++;
        world = new World(sd);
        world.bossHuman = true;
        rec = new Replay(sd);
        rec.bossHuman = true;
        Audio.music(Audio.MUSIC_DUEL);
        walkMode = false;
        parts.clear(); pops.clear(); rings.clear(); draft.clear();
        toast = null; help = false;
        snapCamera(world.p.x, world.p.y);
        p2Wait = 0;
        state = State.RUN;
        acc = 0;
        p2Sync();
        banner = Lang.t("佛地魔（P2）先出手", "Voldemort (P2) moves first");
        bannerT = 2.0;
        bannerColor = new Color(0xC9A0FF);
    }

    /** Player 2's held arrows into the duel (and the recording), while Voldemort can act. */
    void p2Sync() {
        if (world == null || !world.bossHuman || state != State.RUN) return;
        double dx = (p2R ? 1 : 0) - (p2L ? 1 : 0), dy = (p2D ? 1 : 0) - (p2U ? 1 : 0);
        if (dx == world.bossDx && dy == world.bossDy) return;
        world.bossDx = dx; world.bossDy = dy;
        rec.boss(world);
    }

    /** Player 2's keys while Voldemort's side plays; returns whether the key was one of them. */
    boolean p2Key(int k, boolean down) {
        switch (k) {
            case KeyEvent.VK_UP: p2U = down; p2Sync(); return true;
            case KeyEvent.VK_DOWN: p2D = down; p2Sync(); return true;
            case KeyEvent.VK_LEFT: p2L = down; p2Sync(); return true;
            case KeyEvent.VK_RIGHT: p2R = down; p2Sync(); return true;
            default: break;
        }
        if (!down || state != State.RUN || world == null || !world.bossHuman) return false;
        if (k == KeyEvent.VK_ENTER) {
            if (world.b.blinkCd > 0) { showToast(String.format(Lang.t("幻影移形冷卻中（%.1f 秒）", "Apparition cooling down (%.1f s)"), world.b.blinkCd)); return true; }
            world.bossBlink = true;
            rec.boss(world);
            return true;
        }
        int ai = p2AttackKey(k);
        if (ai < 0) return false;
        World.Attack a = World.Attack.values()[ai];
        world.bossOrder = a;
        rec.boss(world);
        String why = world.castBlock(a);
        String msg = Lang.t("佛地魔選好：", "Voldemort readies: ") + a.label();
        if (why != null) showToast(msg + Lang.t("（" + why + "，等條件符合才會施放）", " (" + why + " — cast once allowed)"));
        else { toast = msg; toastT = 1.5; Audio.play(Audio.Sfx.CLICK); }
        return true;
    }

    /** The key labels of Voldemort's spells, in the order of World.Attack. */
    static final String[] P2_KEYS = { "6", "7", "8", "9", "0", "-", "=", "[", "]", ";", "'" };

    static int p2AttackKey(int k) {
        switch (k) {
            case KeyEvent.VK_6: case KeyEvent.VK_NUMPAD1: return 0;
            case KeyEvent.VK_7: case KeyEvent.VK_NUMPAD2: return 1;
            case KeyEvent.VK_8: case KeyEvent.VK_NUMPAD3: return 2;
            case KeyEvent.VK_9: case KeyEvent.VK_NUMPAD4: return 3;
            case KeyEvent.VK_0: case KeyEvent.VK_NUMPAD5: return 4;
            case KeyEvent.VK_MINUS: case KeyEvent.VK_NUMPAD6: return 5;
            case KeyEvent.VK_EQUALS: case KeyEvent.VK_NUMPAD7: return 6;
            case KeyEvent.VK_OPEN_BRACKET: case KeyEvent.VK_NUMPAD8: return 7;
            case KeyEvent.VK_CLOSE_BRACKET: case KeyEvent.VK_NUMPAD9: return 8;
            case KeyEvent.VK_SEMICOLON: case KeyEvent.VK_NUMPAD0: return 9;
            case KeyEvent.VK_QUOTE: case KeyEvent.VK_DECIMAL: return 10;
            default: return -1;
        }
    }

    void enterPlan(String reason) {
        Audio.play(Audio.Sfx.BULLET_TIME, 0.7f);
        panX = 0; panY = 0; kickX = 0; kickY = 0; zoom = 0; whiteT = 0;
        state = State.PLAN;
        planReason = reason;
        if (world.bossHuman) {
            // time stands still for Voldemort too, and he chooses afresh once her plan plays: his choice never
            // shows in her preview
            p2Wait = 0;
            if (world.bossDx != 0 || world.bossDy != 0 || world.bossOrder != null || world.bossBlink) {
                world.bossDx = 0; world.bossDy = 0; world.bossOrder = null; world.bossBlink = false;
                rec.boss(world);
            }
        }
        // the plan runs until Voldemort can cast again (his attack, plus any stun); never less than half a second
        planWindow = Math.max(world.bulletWindow(), 0.5);
        castLen = Math.min(planWindow, Math.max(0, world.b.busy) + Math.max(0, world.b.stun));
        if (pvp) { planWindow = pvpWindow; castLen = 0; }
        // the tutorial leaves room to try every lesson in one bullet time (he waits for a plan to end anyway)
        if (world.practice) planWindow = Math.max(planWindow, TUTORIAL_WINDOW);
        draft.clear();
        btAnim = 0;
        cursorT = 0;
        manualHold = 0;
        rebuildPreview();
    }

    /** World step at which the running plan was committed, to slide the predicted hits along the timeline. */
    int runStart;

    void execute() {
        if (pvp) { pvpEnter(); return; }
        // an empty plan still lets time move on, or the next bullet time would open on the same frame
        if (draft.isEmpty() && world.p.idle()) draft.add(World.PlanItem.of(ActionType.WAIT));
        world.ev.clear();
        rec.plan(world, draft, castLen);
        world.commit(draft, castLen);
        runStart = world.steps;
        Audio.play(Audio.Sfx.GO);
        handle(world.ev);
        world.ev.clear();
        draft.clear();
        state = State.RUN;
        acc = 0;
        p2Sync();
    }

    void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNs) / 1e9);
        lastNs = now;
        animT += dt;
        btAnim += dt;
        if (pressCard >= 0) pressT += dt;
        if (heldKey >= 0) keyHeldT += dt;
        if (bannerT > 0) bannerT -= dt;
        if (toastT > 0) toastT -= dt;
        if (flashT > 0) flashT -= dt;
        shake = Math.max(0, shake - dt * 30);

        if (slowT > 0) slowT -= dt;
        double sim = slowT > 0 ? dt * 0.3 : dt;
        if (state == State.RUN || state == State.END || state == State.REPLAY) updateFx(sim);
        if (state == State.RUN || state == State.REPLAY) chargeFx(dt);
        if (state == State.RUN) {
            runFrame(sim);
        } else if (state == State.REPLAY) {
            replayFrame(sim);
        } else if (state == State.PLAN) {
            if (manualHold > 0) manualHold -= dt;
            else { cursorT += dt * 0.75; if (cursorT > previewEnd || cursorT < previewFrom()) cursorT = previewFrom(); }
        } else if (state == State.END && endDelay > 0) {
            endDelay -= dt;
        }
        if (world != null) updateCamera(dt);
        tutorialTick();
        repaint();
    }

    /** One real-time frame of a plan playing out: effects, and as many fixed steps as are due. */
    void runFrame(double dt) {
        kickDust(dt);
        trails(dt);
        if (hitStop > 0) { hitStop -= dt; dt = 0; }
        acc += dt;
        while (acc >= DT) {
            acc -= DT;
            world.step();
            actionSound(world);
            flurryFx(world);
            String reason = handle(world.ev);
            if (world.over) { state = State.END; endDelay = 1.4; rec.endStep = world.steps; break; }
            if (pvp) {
                runSteps++;
                if ((!doomedRun && runSteps > 6 && world.pvpSettled()) || runSteps > (PVP_TURN + 3) * 60) { round++; startRound(); break; }
                continue;
            }
            // the moment a plan has played out, the next bullet time begins; against a second player that is when
            // his turn opens, and only if it passes unused does she plan again
            if (reason == null && world.p.idle() && !world.resolving()) {
                if (world.bossHuman && world.bossTurn()) {
                    p2Wait += DT;
                    if (p2Wait >= P2_TURN) reason = Lang.t("佛地魔遲疑了 — 換你安排", "Voldemort hesitates — your move");
                } else {
                    reason = Lang.t("計畫演完 — 佛地魔還在施法，安排下一段", "Plan finished — Voldemort is still casting, plan the next stretch");
                }
            }
            if (reason != null) { enterPlan(reason); break; }
        }
    }

    // ------------------------------------------------------------------ replay

    void startReplay() {
        if (rec == null) return;
        if (rec.endStep == 0) rec.endStep = world.steps;
        finalWorld = world;
        replay = new Replay.Player(rec);
        world = replay.world;
        state = State.REPLAY;
        Audio.music(Audio.MUSIC_DUEL);
        replayPaused = false;
        speedIdx = 2;
        replayAcc = 0;
        clearFx();
        fullBeforeReplay = fullView;
        if (pvp) fullView = true;
        camLocked = false; panX = 0; panY = 0;
        snapCamera(world.p.x, world.p.y);
    }

    void exitReplay() {
        Audio.music(Audio.MUSIC_NONE);
        fullView = fullBeforeReplay;
        world = finalWorld;
        state = State.END;
        endDelay = 0;
        clearFx();
    }

    void clearFx() {
        parts.clear(); pops.clear(); rings.clear(); arcs.clear(); streaks.clear();
        banner = null; toast = null; hitStop = 0; flashT = 0; shake = 0; kickX = 0; kickY = 0; zoom = 0; whiteT = 0; slowT = 0;
    }

    void replaySeek(int step) {
        replay.seek(Math.max(0, Math.min(rec.endStep, step)));
        world = replay.world;
        clearFx();
    }

    /** Plays the recording continuously: no bullet-time stops, Voldemort's spells are captioned instead. */
    void replayFrame(double dt) {
        if (replayPaused) return;
        trails(dt);
        if (hitStop > 0) { hitStop -= dt; return; }
        replayAcc += dt * SPEEDS[speedIdx];
        while (replayAcc >= DT) {
            replayAcc -= DT;
            if (!replay.advance()) { replayPaused = true; replayAcc = 0; break; }
            actionSound(world);
            flurryFx(world);
            castBanner = null;
            handle(world.ev);
            if (castBanner != null) showBanner(castBanner, new Color(0xD8B0FF));
        }
    }

    double replayTime() { return world.steps * DT; }

    /** The camera keeps Malfoy exactly in the middle of the visible area (above the bottom panel). */
    void updateCamera(double dt) {
        if (fullView) { camX = 0; camY = 0; return; }
        if (freeCam()) {
            double sp = 260 * dt;
            int kx = (kPanR ? 1 : 0) - (kPanL ? 1 : 0), ky = (kPanD ? 1 : 0) - (kPanU ? 1 : 0);
            if (kx != 0 || ky != 0) detachCam();
            panX += kx * sp;
            panY += ky * sp;
            clampPan();
        } else { panX = 0; panY = 0; }
        double[] c = camBase();
        snapCamera(c[0] + panX, c[1] + panY);
    }

    /** The player can steer the camera: in bullet time, and while a two-player round or replay plays. */
    boolean freeCam() { return state == State.PLAN || (pvp && (state == State.RUN || state == State.REPLAY)); }

    /** What the camera follows: the planning player, both duellists' midpoint, or the spot it was let go at. */
    double[] camBase() {
        if (pvp && state != State.PLAN) {
            if (camLocked) return new double[] { lockX, lockY };
            return new double[] { (world.ps[0].x + world.ps[1].x) / 2, (world.ps[0].y + world.ps[1].y) / 2 };
        }
        World v = viewWorld();
        return new double[] { v.p.x, v.p.y };
    }

    /** While a round plays, the first pan stops the camera following the duellists. */
    void detachCam() {
        if (!pvp || state == State.PLAN || camLocked) return;
        double[] c = camBase();
        lockX = c[0] + panX; lockY = c[1] + panY;
        panX = 0; panY = 0;
        camLocked = true;
    }

    /** Keeps a panned view over the hall. */
    void clampPan() {
        if (world == null) return;
        double[] c = camBase();
        panX = World.clamp(c[0] + panX, LW * 0.25, World.MAPW - LW * 0.25) - c[0];
        panY = World.clamp(c[1] + panY, VIEW_H * 0.25, World.MAPH - VIEW_H * 0.25) - c[1];
    }

    void centerPanOn(double wx, double wy) { detachCam(); double[] c = camBase(); panX = wx - c[0]; panY = wy - c[1]; clampPan(); }

    boolean minimapClick(double x, double y) {
        if (fullView) return false;
        int mw = 160, mh = (int) Math.round(160.0 * World.MAPH / World.MAPW), mx = W - 176, my = 104;
        if (x < mx || x > mx + mw || y < my || y > my + mh) return false;
        centerPanOn((x - mx) / mw * World.MAPW, (y - my) / mh * World.MAPH);
        return true;
    }

    void snapCamera(double x, double y) { camX = x - LW / 2.0; camY = y - 12 - VIEW_H / 2.0; }

    /** Turns simulation events into effects; returns a bullet-time reason when Voldemort starts a cast. */
    /** The banner a replay shows when Voldemort starts a spell. */
    String castBanner;

    String handle(List<World.Ev> evs) {
        String reason = null;
        for (World.Ev e : evs) {
            sound(e);
            switch (e.type) {
                case ENEMY_ATTACK:
                    // summoned enemies only flash a warning; bullet time belongs to Voldemort's spells
                    ring(e.x, e.y, new Color(0x7CFF9A), 3, 60, 0.3, 2);
                    break;
                case CAST_START:
                    reason = Lang.t("佛地魔即將施放【" + e.text + "】（" + World.bandName(e.amount) + "）",
                                    "Voldemort is about to cast " + e.text + " (" + World.bandName(e.amount) + " range)");
                    castBanner = Lang.t("佛地魔施放【" + e.text + "】", "Voldemort casts " + e.text);
                    burst(e.x, e.y - 20, new Color(0x3BFF6A), 18, 60);
                    ring(e.x, e.y - 20, new Color(0x3BFF6A), 4, 120, 0.4, 2);
                    break;
                case SCORE: if (!pvp) popup(e.x, e.y, e.text, e.amount >= 0 ? new Color(0xFFE38A) : new Color(0xFF6060)); break;
                case HIT: {
                    // Malfoy is hurt: red flash, a hard jolt away from the blow, a beat of hit-stop
                    shake = 14; flashT = 0.3; flashColor = new Color(255, 40, 40);
                    hitStop = Math.max(hitStop, 0.09);
                    punch(0.05);
                    double[] d = dirFrom(e.x, e.y, world.b.x, world.b.y, true);
                    kick(d[0], d[1], 9);
                    burst(e.x, e.y, new Color(0xFF4040), 30, 110);
                    impactLines(e.x, e.y, new Color(0xFF8080), 10, 18);
                    flashDisc(e.x, e.y, new Color(0xFF6060), 9);
                    break;
                }
                case BOSS_HIT: {
                    burst(e.x, e.y, Color.WHITE, 12, 80);
                    if (e.amount > 0) {
                        double[] d = dirFrom(world.p.x, world.p.y - 12, e.x, e.y, false);
                        damageNumber(e.x, e.y - 22, e.amount);
                        // heavier hits stop time longer, shove the camera further, punch the zoom harder
                        hitStop = Math.max(hitStop, Math.min(0.16, 0.035 + e.amount * 0.007));
                        kick(d[0], d[1], 2 + e.amount * 0.45);
                        punch(0.012 + e.amount * 0.0028);
                        shake = Math.max(shake, 4 + e.amount / 2.5);
                        burstDir(e.x, e.y, d[0], d[1], 0.8, new Color(0xFFF4C8), 10 + e.amount, 150);
                        impactLines(e.x, e.y, Color.WHITE, 8, 10 + e.amount);
                        flashDisc(e.x, e.y, Color.WHITE, 6 + e.amount / 2);
                        if (e.amount >= 12) whiteT = Math.max(whiteT, 0.06);
                    }
                    break;
                }
                case RELEASE: {
                    Color c = Sprites.spellColor(ActionType.values()[e.amount]);
                    double[] d = dirFrom(world.p.x, world.p.y, e.x, e.y, false);
                    burst(e.x, e.y - Sprites.AIR, c, 22, 90);
                    burstDir(e.x, e.y - Sprites.AIR, d[0], d[1], 0.5, Color.WHITE, 10, 140);
                    ring(e.x, e.y - Sprites.AIR, c, 3, 110, 0.28, 2);
                    ring(e.x, e.y - Sprites.AIR, Color.WHITE, 1, 50, 0.16, 1);
                    flashDisc(e.x, e.y - Sprites.AIR, c, 7);
                    impactLines(e.x, e.y - Sprites.AIR, c, 6, 8);
                    kick(-d[0], -d[1], 2.5);
                    break;
                }
                case IMPACT: {
                    Color c = impactColor(e.amount);
                    double y = e.y - Sprites.AIR;
                    burst(e.x, y, c, 34, 120);
                    burst(e.x, y, Color.WHITE, 12, 70);
                    embers(e.x, y, c, 12);
                    ring(e.x, y, c, 2, 150, 0.32, 3);
                    ring(e.x, y, Color.WHITE, 1, 80, 0.18, 1);
                    ring(e.x, e.y, alphaOf(c, 170), 2, 90, 0.4, 2);
                    flashDisc(e.x, y, c, 12);
                    impactLines(e.x, y, c, 10, 16);
                    if (World.Kind.values()[e.amount] == World.Kind.P_STUP) pillar(e.x, e.y, c);
                    break;
                }
                case STORED: {
                    Color c = Sprites.spellColor(ActionType.values()[e.amount]);
                    burst(e.x, e.y, c, 18, 50);
                    ring(e.x, e.y + 18, c, 4, 60, 0.35, 2);
                    popup(e.x, e.y - 6, e.text, new Color(0xBFF3FF));
                    break;
                }
                case CANCEL:
                    cancelFx(e.text == null ? "" : e.text, e.x, e.y);
                    break;
                case BLOCK:
                    burst(e.x, e.y - 10, new Color(0xA8E4FF), 18, 80);
                    ring(e.x, e.y - 10, new Color(0xA8E4FF), 3, 70, 0.22, 2);
                    break;
                case MELEE: {
                    ActionType a = world.p.act();
                    double[] d = dirFrom(world.p.x, world.p.y, e.x, e.y + 14, false);
                    slash(a, d);
                    burstDir(e.x, e.y, d[0], d[1], 1.0, new Color(0xFFE9B0), 22, 140);
                    ring(e.x, e.y, new Color(0xFFE9B0), 3, 110, 0.22, 2);
                    if (e.amount > 0) {
                        hitStop = Math.max(hitStop, 0.06 + e.amount * 0.004);
                        kick(d[0], d[1], 4 + e.amount * 0.4);
                        punch(0.03 + e.amount * 0.002);
                        shake = Math.max(shake, 8);
                    }
                    break;
                }
                case THROW:
                    // the slam: the floor cracks, dust erupts, the screen flashes and jolts down
                    shake = 20;
                    hitStop = Math.max(hitStop, 0.16);
                    punch(0.1);
                    kick(0, 1, 12);
                    whiteT = Math.max(whiteT, 0.08);
                    flashDisc(e.x, e.y, new Color(0xE8DCC0), 18);
                    ring(e.x, e.y, new Color(0xD8C8A0), 4, 200, 0.45, 3);
                    ring(e.x, e.y, Color.WHITE, 2, 120, 0.25, 2);
                    impactLines(e.x, e.y, new Color(0x6A5440), 14, 34);
                    burst(e.x, e.y, new Color(0xD8C8A0), 44, 120);
                    embers(e.x, e.y, new Color(0xB8A890), 14);
                    if ("final".equals(e.text)) showBanner(Lang.t("過肩摔 ×3！", "Triple slam!"), new Color(0xFFD86B));
                    break;
                case INTERRUPT:
                    showBanner(Lang.t("打斷施法！", "Interrupted!"), new Color(0xFFD86B));
                    burst(e.x, e.y, new Color(0xFFD86B), 30, 100);
                    ring(e.x, e.y, new Color(0xFFD86B), 4, 160, 0.4, 3);
                    punch(0.05);
                    break;
                case APPARATE: burst(e.x, e.y - 16, new Color(0x1A1422), 26, 60); burst(e.x, e.y - 16, new Color(0x5A4A70), 10, 40); ring(e.x, e.y - 8, new Color(0x5A4A70), 3, 70, 0.3, 2); break;
                case STRIKE:
                    if (!"AVADA".equals(e.text) && !"MARK".equals(e.text)) break;   // the rest are sound cues only
                    if ("AVADA".equals(e.text)) {
                        flashT = 0.16; flashColor = new Color(60, 255, 110);
                        ring(e.x, e.y - Sprites.AIR, new Color(0x39FF7A), 4, 140, 0.35, 3);
                        flashDisc(e.x, e.y - Sprites.AIR, new Color(0x9CFFB8), 12);
                        shake = Math.max(shake, 7);
                    } else {
                        burst(e.x, e.y, new Color(0x39FF7A), 22, 90);
                        ring(e.x, e.y, new Color(0x39FF7A), 4, 110, 0.32, 2);
                        flashDisc(e.x, e.y, new Color(0x9CFFB8), 10);
                        shake = Math.max(shake, 4);
                    }
                    break;
                case PHASE2:
                    showBanner(Lang.t("佛地魔被激怒了 — 六連索命咒、棋盤落雷、更多食死人", "Voldemort is enraged — six Avadas, checkerboard lightning, more Death Eaters"), new Color(0xFF5050));
                    shake = 18; punch(0.08); whiteT = 0.1; slowT = 0.8;
                    break;
                case WIN:
                    slowT = 1.6; punch(0.12); whiteT = 0.15; shake = 20;
                    ring(e.x, e.y, Color.WHITE, 6, 260, 0.8, 4);
                    burst(e.x, e.y, new Color(0xFFD86B), 60, 160);
                    break;
                case HEAL: burst(e.x, e.y, new Color(0x9CFFB0), 20, 40); embers(e.x, e.y, new Color(0x9CFFB0), 10); break;
                case RETREAT: popup(e.x, e.y, e.text, new Color(0xD8B0FF)); break;
                case FIZZLE: burst(e.x, e.y - 12, new Color(0x888888), 6, 20); break;
                case STUN: burst(e.x, e.y, new Color(0xFFE066), 12, 35); break;
                default: break;
            }
        }
        return reason;
    }

    /** The sound of each simulation event. */
    static void sound(World.Ev e) {
        switch (e.type) {
            case CAST_START: Audio.play(Audio.Sfx.BOSS_CAST); break;
            case HIT: Audio.play(Audio.Sfx.PLAYER_HIT); break;
            case BOSS_HIT: Audio.play(e.amount >= 12 ? Audio.Sfx.HEAVY_HIT : e.amount > 0 ? Audio.Sfx.BOSS_HIT : Audio.Sfx.IMPACT); break;
            case RELEASE:
                switch (ActionType.values()[e.amount]) {
                    case DENSAUGEO: Audio.play(Audio.Sfx.DENS); break;
                    case EXPELLIARMUS: Audio.play(Audio.Sfx.EXP); break;
                    case STUPEFY: Audio.play(Audio.Sfx.STUP); break;
                    default: Audio.play(Audio.Sfx.SNAKE); break;
                }
                break;
            case IMPACT: Audio.play(Audio.Sfx.IMPACT, 0.8f); break;
            case STORED: Audio.play(Audio.Sfx.STORED); break;
            case CANCEL:
                switch (e.text == null ? "" : e.text) {
                    case "CHIP": Audio.play(Audio.Sfx.X_CHIP); break;
                    case "AVADA": Audio.play(Audio.Sfx.CLASH); break;
                    case "SHARD": Audio.play(Audio.Sfx.X_GLASS); break;
                    case "M_BOLT": Audio.play(Audio.Sfx.X_BOLT); break;
                    case "CRUCIO": Audio.play(Audio.Sfx.X_CRUCIO); break;
                    case "REFLECT": Audio.play(Audio.Sfx.X_REFLECT); break;
                    case "SERPENT": Audio.play(Audio.Sfx.X_FIRE); break;
                    case "FIRE_RING": Audio.play(Audio.Sfx.X_SWALLOW); break;
                    case "NAGINI": Audio.play(Audio.Sfx.X_NAGINI); break;
                    case "INFERI": Audio.play(Audio.Sfx.X_INFERI); break;
                    case "BODY": Audio.play(Audio.Sfx.HEAVY_HIT); break;
                    case "BOUNCE": Audio.play(Audio.Sfx.X_REFLECT); break;
                    default: Audio.play(Audio.Sfx.CANCEL); break;
                }
                break;
            case BLOCK: Audio.play(Audio.Sfx.BLOCK); break;
            case MELEE:
                if (e.amount > 0 && ActionType.FLURRY.zh.equals(e.text)) Audio.play(Audio.Sfx.TRUCK);
                else Audio.play(e.amount > 0 ? Audio.Sfx.MELEE : Audio.Sfx.SWISH);
                break;
            case THROW: Audio.play(Audio.Sfx.SLAM); break;
            case INTERRUPT: Audio.play(Audio.Sfx.INTERRUPT); break;
            case APPARATE: Audio.play(Audio.Sfx.APPARATE, 0.8f); break;
            case STUN: Audio.play(Audio.Sfx.STUN); break;
            case FIZZLE: Audio.play(Audio.Sfx.ERROR, 0.6f); break;
            case ENEMY_ATTACK: Audio.play(Audio.Sfx.MINION, 0.8f); break;
            case PHASE2: Audio.play(Audio.Sfx.PHASE2); Audio.music(Audio.MUSIC_RAGE); break;
            case WIN: Audio.play(Audio.Sfx.WIN); Audio.music(Audio.MUSIC_NONE); break;
            case LOSE: Audio.play(Audio.Sfx.LOSE); Audio.music(Audio.MUSIC_NONE); break;
            case STRIKE:
                switch (e.text) {
                    case "AVADA": Audio.play(Audio.Sfx.AVADA); break;
                    case "MARK": Audio.play(Audio.Sfx.THUNDER, 0.8f); break;
                    case "FIRE_RING": case "SERPENT": Audio.play(Audio.Sfx.FIRE); break;
                    case "NAGINI": Audio.play(Audio.Sfx.HISS); break;
                    case "SHARD": Audio.play(Audio.Sfx.GLASS, 0.7f); break;
                    case "CONE": Audio.play(Audio.Sfx.SLAM, 0.7f); break;
                    case "SMOKE": Audio.play(Audio.Sfx.WHOOSH); break;
                    case "CRUCIO": Audio.play(Audio.Sfx.MINION, 0.6f); break;
                    default: break;
                }
                break;
            default: break;
        }
    }

    /** Whooshes and swings the moment Malfoy starts a dodge or a melee move. */
    static void actionSound(World w) {
        World.Player p = w.p;
        if (p.cur == null || p.step != 1 || p.fizzled) return;
        switch (p.cur.type) {
            case ROLL: case DASH: case BACKFLIP: Audio.play(Audio.Sfx.WHOOSH, 0.8f); break;
            case FLURRY: case SPIN: case THROW: Audio.play(Audio.Sfx.SWISH, 0.8f); break;
            case KICK: Audio.play(Audio.Sfx.WHOOSH, 0.9f); break;
            default: break;
        }
    }

    /** Unit vector from (x0,y0) to (x1,y1); {@code flip} reverses it. */
    static double[] dirFrom(double x0, double y0, double x1, double y1, boolean flip) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.max(1e-6, Math.hypot(dx, dy));
        return flip ? new double[] { -dx / l, -dy / l } : new double[] { dx / l, dy / l };
    }

    // ------------------------------------------------------------------ orders

    int planSteps() { int s = 0; for (World.PlanItem it : draft) s += it.steps; return s; }
    double planTotal() { return world.remainingPlan() + planSteps() * DT; }
    int roomSteps() {
        int room = (int) Math.floor((planWindow - planTotal()) * 60 + 1e-6);
        if (pvp && state == State.PLAN && allowSteps >= 0) room = Math.min(room, turnStartSteps + allowSteps - toSteps(planTotal()));
        return room;
    }
    static int toSteps(double secs) { return (int) Math.round(secs * 60); }

    /** What the toast says when an order does not fit. */
    String noRoom() {
        if (pvp && allowSteps >= 0 && roomSteps() < (int) Math.floor((planWindow - planTotal()) * 60 + 1e-6))
            return String.format(Lang.t("這一動只有 %.2f 秒（對手上一動的長度），還剩 %.2f 秒", "This turn is %.2f s (the other side's last turn), %.2f s left"),
                                 allowSteps * DT, Math.max(0, roomSteps()) * DT);
        return String.format(Lang.t("這次子彈時間只有 %.1f 秒", "This bullet time is only %.1f s"), planWindow);
    }

    /** Malfoy as she will be when the next order starts: end of plan while planning, now in real time. */
    World.Player origin() { return state == State.PLAN && planEndWorld != null ? planEndWorld.p : world.p; }
    World.Boss originBoss() { return state == State.PLAN && planEndWorld != null ? planEndWorld.b : world.b; }
    /** Where the foe (Voldemort, or the other player) will be when the next order starts. */
    double[] originFoe() { World w = state == State.PLAN && planEndWorld != null ? planEndWorld : world; return new double[] { w.fx(), w.fy() }; }

    // ------------------------------------------------------------------ two-player duel (one computer, taking turns)

    static String sideName(int side) { return side == 0 ? Lang.t("玩家一（綠）", "Player 1 (green)") : Lang.t("玩家二（紅）", "Player 2 (red)"); }
    static Color sideColor(int side) { return side == 0 ? new Color(0x7FE8A0) : new Color(0xFF8C8C); }

    void newPvp() {
        long sd = seed++;
        world = new World(sd, true);
        rec = new Replay(sd, true);
        pvp = true;
        round = 0;
        Audio.music(Audio.MUSIC_DUEL);
        walkMode = false;
        parts.clear(); pops.clear(); rings.clear(); draft.clear();
        banner = null; toast = null; help = false;
        startRound();
    }

    ArrayList<World.PlanItem> planOf(int side) { return side == 0 ? plan0 : plan1; }

    /** Where the preview starts: 0, or with the turn range on, where the other side's last turn began. */
    double previewFrom() {
        if (!pvp || !previewTurn || state != State.PLAN) return 0;
        int other = turnBegin[1 - world.me];
        int st = other >= 0 ? Math.min(other, turnStartSteps) : turnStartSteps;
        return Math.min(st * DT, previewEnd);
    }

    /** The hall as the preview starts: the frozen duel itself, or its state where the turn range begins. */
    World viewWorld() { double f = previewFrom(); return f > 0 ? snapAt(f) : world; }

    void togglePreviewRange() {
        previewTurn = !previewTurn;
        Audio.play(Audio.Sfx.CLICK);
        cursorT = Math.max(cursorT, previewFrom());
        showToast(previewTurn ? Lang.t("預演：只看對手上一動到這一動（Y 切換）", "Preview: the other side's last turn to this one (Y)")
                              : Lang.t("預演：目前所有安排（Y 切換）", "Preview: everything planned so far (Y)"));
    }

    /** A round opens: both plans are empty and the first player (alternating) starts on the shared 5 s. */
    void startRound() {
        firstSide = round % 2;
        pvpPhase = 0;
        plan0.clear(); plan1.clear();
        sideDone[0] = false; sideDone[1] = false;
        turnBegin[0] = -1; turnBegin[1] = -1;
        pvpWindow = PVP_TURN;
        beginTurn(firstSide, -1, String.format(Lang.t("第 %d 回合 — %s 先手，每一動長度自選", "Round %d — %s first: any length per turn"),
                                               round + 1, sideName(firstSide)));
    }

    /** One side takes over the keyboard: its plan so far comes back into the draft, the other plan is in the preview. */
    void beginTurn(int side, int allow, String reason) {
        world.setMe(side);
        snapCamera(world.p.x, world.p.y);
        enterPlan(reason);
        draft.addAll(planOf(side));
        turnBase = draft.size();
        allowSteps = allow;
        turnStartSteps = toSteps(planTotal());
        turnBegin[side] = turnStartSteps;
        cursorT = Math.max(0, planTotal() - 0.3);
        rebuildPreview();
        showBanner(sideName(side) + Lang.t(" 的子彈時間", "'s bullet time"), sideColor(side));
    }

    /** Seconds of the shared bullet time a side has planned (an action carried over from last round included). */
    double sideTotal(int side) {
        if (side == world.me) return planTotal();
        World.Player pl = world.ps[side];
        int st = pl.cur == null ? 0 : pl.cur.steps - pl.step;
        for (World.PlanItem it : pl.queue) st += it.steps;
        for (World.PlanItem it : planOf(side)) st += it.steps;
        return st * DT;
    }

    /**
     * Someone falls within the time both sides have already planned: nothing either of them plans later can change
     * that stretch, so the round need not wait for the rest of the 5 s.
     */
    boolean settledFall() {
        int locked = Math.min(toSteps(sideTotal(0)), toSteps(sideTotal(1)));
        if (locked <= 0) return false;
        World w = world.copy();
        int me = w.me;
        for (int side = 0; side < 2; side++) { w.setMe(side); w.commit(planOf(side)); }
        w.setMe(me);
        for (int i = 0; i < locked && !w.over; i++) w.step();
        return w.over;
    }

    /** Steps left of the shared 5 s for the planning side. */
    int sharedRoom() { return (int) Math.floor((planWindow - planTotal()) * 60 + 1e-6); }

    /** Enter in a two-player bullet time: the turn ends and the other side answers with a turn exactly as long;
     *  whatever this turn left unplanned is spent waiting. Once both have the full 5 s, the round plays. */
    /** Two-player duel: the shortest a turn (one bullet time) can be, 0.3 s. */
    static final int PVP_MIN_TURN = 18;

    void pvpEnter() {
        int me = world.me, other = 1 - me;
        int added = toSteps(planTotal()) - turnStartSteps;
        // the second side must use all the time it was given; an empty first-side turn stands still for the rest of the 5 s
        int room = allowSteps >= 0 ? roomSteps() : added == 0 ? sharedRoom() : 0;
        // every bullet time covers at least 0.3 s (its actions may be shorter): a shorter first-side turn waits out the rest
        if (allowSteps < 0 && added > 0 && added < PVP_MIN_TURN) room = Math.min(PVP_MIN_TURN - added, sharedRoom());
        if (room > 0) { draft.add(new World.PlanItem(ActionType.WAIT, 0, 0, room, false, false, 0, 0)); added += room; }
        // a sliver too short for any order is spent waiting, and the side is full
        int left = sharedRoom();
        if (left < 3) {
            if (left > 0) draft.add(new World.PlanItem(ActionType.WAIT, 0, 0, left, false, false, 0, 0));
            sideDone[me] = true;
        }
        planOf(me).clear();
        planOf(me).addAll(draft);
        doomedRun = settledFall();
        if (!(sideDone[0] && sideDone[1]) && !doomedRun) {
            pvpPhase++;
            if (other == firstSide) beginTurn(other, -1, String.format(Lang.t("%s（先手）：這一動長度自選（最少 0.3 秒）", "%s (first): this turn any length (at least 0.3 s)"), sideName(other)));
            else beginTurn(other, added, String.format(Lang.t("%s（後手）：這一動排滿 %.2f 秒（＝先手上一動）", "%s (second): fill %.2f s (= the first side's last)"),
                                                       sideName(other), Math.min(added, (int) Math.round((PVP_TURN - sideTotal(other)) * 60)) * DT));
            return;
        }
        draft.clear();
        world.ev.clear();
        if (rec.start == null) rec.start = world.copy();
        for (int side : new int[] { firstSide, 1 - firstSide }) {
            world.setMe(side);
            rec.plan(world, side, planOf(side));
            world.commit(planOf(side));
        }
        plan0.clear(); plan1.clear();
        if (doomedRun) showBanner(Lang.t("勝負已定 — 立刻播放", "The duel is decided — playing now"), new Color(0xFFD86B));
        runStart = world.steps;
        runSteps = 0;
        camLocked = false; panX = 0; panY = 0;
        Audio.play(Audio.Sfx.GO);
        state = State.RUN;
        acc = 0;
    }

    /** A directed move: toward (wx, wy) when given, else straight away from Voldemort. */
    World.PlanItem dirItem(ActionType t, double wx, double wy) {
        World.Player o = origin();
        double[] b = originFoe();
        double dx = wx - o.x, dy = wy - o.y;
        if (Double.isNaN(wx) || Math.hypot(dx, dy) < 2) { dx = o.x - b[0]; dy = o.y - b[1]; }
        switch (t) {
            case DASH: {
                double cross = (b[0] - o.x) * dy - (b[1] - o.y) * dx;
                return new World.PlanItem(ActionType.DASH, cross >= 0 ? 1 : -1, 0, ActionType.DASH.steps, false, false, 0, 0);
            }
            case APPARATE: return World.PlanItem.apparate(Math.min(90, Math.max(20, Math.hypot(dx, dy))), Math.atan2(dy, dx));
            default: return World.PlanItem.move(t, dx, dy, t.steps);
        }
    }

    World.PlanItem itemFor(ActionType t, int kdx, int kdy) {
        boolean onMap = onMap(mouseLX, mouseLY);
        double wx = onMap ? mouseWX() : Double.NaN, wy = onMap ? mouseWY() : Double.NaN;
        switch (t) {
            case RUN: return World.PlanItem.move(ActionType.RUN, kdx, kdy, ActionType.RUN.steps, walkMode);
            case ROLL: case DASH: case APPARATE: return dirItem(t, wx, wy);
            default:
                if (t.isSpell()) {
                    // Densaugeo homes in by itself; every other spell goes exactly where the player points
                    if (!t.charged()) return World.PlanItem.aimed(t, false, 0, 0);
                    if (origin().stored[t.spellIndex()]) { double[] d = aimDir(); return World.PlanItem.aimed(t, true, d[0], d[1]); }
                }
                return World.PlanItem.of(t);
        }
    }

    /** Where an aimed spell ordered now goes: the last mouse point on the map, or Voldemort's spot if the mouse never touched the map. */
    double[] aimDir() {
        World.Player o = origin();
        double tx = Double.isNaN(lastMapWX) ? originFoe()[0] : lastMapWX, ty = Double.isNaN(lastMapWX) ? originFoe()[1] : lastMapWY;
        double dx = tx - o.x, dy = ty - o.y;
        if (Math.hypot(dx, dy) < 3) return new double[] { o.face, 0 };
        return new double[] { dx, dy };
    }

    /** Every order goes into the bullet-time plan. */
    void command(World.PlanItem it) {
        if (it != null && state == State.PLAN) addItem(it);
    }

    void addItem(World.PlanItem it) {
        if (it.steps > roomSteps()) { showToast(noRoom()); return; }
        int before = previewFizzles;
        draft.add(it);
        rebuildPreview();
        if (previewFizzles > before) {
            draft.remove(draft.size() - 1);
            rebuildPreview();
            World probe = world.copy();
            ArrayList<World.PlanItem> tmp = new ArrayList<>(draft);
            tmp.add(it);
            probe.commit(tmp);
            String why = Lang.t("無法施放", "Can't cast");
            for (int i = 0; i < 700 && probe.fizzles == world.fizzles + previewFizzles; i++) probe.step();
            if (!probe.lastFizzle.isEmpty()) why = probe.lastFizzle;
            showToast(it.type.label() + Lang.t("：", ": ") + why);
            return;
        }
        manualHold = 0;
        cursorT = Math.max(0, planTotal() - it.dur());
        Audio.play(Audio.Sfx.CLICK);
    }

    void clickCard(Card c) {
        if (c.type == null) { toggleWalk(); return; }
        command(itemFor(c.type, c.dx, c.dy));
    }

    void toggleWalk() {
        walkMode = !walkMode;
        showToast(walkMode ? Lang.t("漫步模式：移動速度 40", "Walk mode: speed 40") : Lang.t("跑步模式：移動速度 75", "Run mode: speed 75"));
    }

    void runTo(double wx, double wy) {
        World.Player e = origin();
        double dist = Math.hypot(wx - e.x, wy - e.y);
        double speed = walkMode ? ActionType.WALK_SPEED : World.RUN_SPEED;
        int steps = (int) Math.ceil(dist / speed * 60);
        if (state == State.PLAN) steps = Math.min(steps, roomSteps());
        else steps = Math.min(steps, 240);
        if (steps < 3) { showToast(state == State.PLAN && roomSteps() < 3 ? noRoom() : Lang.t("太近了", "Too close")); return; }
        command(World.PlanItem.move(ActionType.RUN, wx - e.x, wy - e.y, steps, walkMode));
    }

    void undo() {
        if (pvp && draft.size() <= turnBase) { showToast(Lang.t("對手已經看過的動作不能復原", "The other player has seen that already: it can't be undone")); return; }
        if (!draft.isEmpty()) draft.remove(draft.size() - 1);
        else if (!world.p.queue.isEmpty()) world.p.queue.pollLast();
        else return;
        rebuildPreview();
    }

    void clearPlan() {
        if (pvp) { while (draft.size() > turnBase) draft.remove(draft.size() - 1); rebuildPreview(); return; }
        draft.clear();
        world.p.queue.clear();
        rebuildPreview();
    }

    /** Runs a copy of the duel forward with the current plan and records what the ghosts should show. */
    void rebuildPreview() {
        World w = world.copy();
        if (pvp) { int me = w.me; w.setMe(1 - me); w.commit(planOf(1 - me)); w.setMe(me); }
        w.commit(draft);
        snaps.clear(); ghosts.clear(); hits.clear(); casts.clear(); path.clear(); foeGhosts.clear();
        double plan = w.remainingPlan();
        int planSteps = (int) Math.round(plan * 60);
        int max = (int) Math.round(Math.min(Math.max(plan, planWindow) + World.RESOLVE_MAX + 0.2, World.MAX_PLAN + 1.4) * 60);
        int baseScore = world.score, baseFizz = world.fizzles;
        snaps.add(w.copy());
        path.add(new double[] { w.p.x, w.p.y });
        planEndWorld = planSteps == 0 ? w.copy() : null;
        previewPlanEnd = w.p.planActive() ? -1 : 0;
        previewDeath = false;
        for (int i = 1; i <= max; i++) {
            w.step();
            double t = i * DT;
            for (World.Ev e : w.ev) {
                if (w.pvp && e.type == World.EvType.KEY_POSE && e.amount != w.me) foeGhosts.add(new Ghost(t, w.q.copy(), w.b.copy()));
                else if (e.type == World.EvType.KEY_POSE && t <= plan + 1e-6) ghosts.add(new Ghost(t, w.p.copy(), w.b.copy()));
                else if (e.type == World.EvType.HIT && (!w.pvp || Math.hypot(e.x - w.p.x, e.y + 13 - w.p.y) < 2)) hits.add(new Mark(t, e.text));
                else if (e.type == World.EvType.CAST_START) casts.add(new Mark(t, e.text));
            }
            if (i % 3 == 0 && t <= plan + 1e-6) path.add(new double[] { w.p.x, w.p.y });
            if (i == planSteps) planEndWorld = w.copy();
            if (previewPlanEnd < 0 && !w.p.planActive()) previewPlanEnd = i;
            if (i % 2 == 0) snaps.add(w.copy());
            if (w.over) { previewDeath = w.pvp ? w.winner != w.me : !w.won; snaps.add(w.copy()); break; }
        }
        if (planEndWorld == null) planEndWorld = w.copy();
        previewEnd = (snaps.size() - 1) * 2 * DT;
        previewScore = w.score - baseScore;
        previewFizzles = w.fizzles - baseFizz;
        cursorT = Math.min(cursorT, previewEnd);
    }

    World snapAt(double t) {
        if (snaps.isEmpty()) return world;
        int i = (int) Math.round(t / (2 * DT));
        return snaps.get(Math.max(0, Math.min(snaps.size() - 1, i)));
    }

    boolean scrubTo(double x, double y) {
        if (state != State.PLAN || y < 562 || y > 606 || x < TL_X - 10 || x > TL_X + TL_W + 10) return false;
        cursorT = Math.max(previewFrom(), Math.min(previewEnd, (x - TL_X) / TL_W * planWindow));
        manualHold = 2.5;
        return true;
    }

    int cardAt(double x, double y) {
        if (world == null || state != State.PLAN) return -1;
        for (int i = 0; i < cardRects.length; i++) if (cardRects[i].contains(x, y)) return i;
        return -1;
    }

    boolean onMap(double x, double y) {
        if (x < 0 || y < 0 || x > W || y > PANEL_Y) return false;
        if (y < 98 && (x < 350 || x > W - 350)) return false;   // HUD boxes
        if (viewRect.contains(x, y)) return false;               // view toggle
        return fullView || !(x > W - 180 && y > 100 && y < 212); // minimap
    }

    // ------------------------------------------------------------------ input

    void toggleView() {
        fullView = !fullView;
        Audio.play(Audio.Sfx.CLICK);
        showToast(fullView ? Lang.t("全地圖視角（U 切換）", "Whole hall (U)") : Lang.t("跟隨視角（U 切換）：拖曳或 IJKL 移動鏡頭，Tab 跟回角色", "Follow view (U): drag or IJKL to move, Tab to follow again"));
    }

    /** IJKL pan the camera, Tab brings it back; returns whether the key was one of those. */
    boolean panKey(int k, boolean down) {
        switch (k) {
            case KeyEvent.VK_I: kPanU = down; return true;
            case KeyEvent.VK_K: kPanD = down; return true;
            case KeyEvent.VK_J: kPanL = down; return true;
            case KeyEvent.VK_L: kPanR = down; return true;
            case KeyEvent.VK_TAB: panX = 0; panY = 0; camLocked = false; return true;
            default: return false;
        }
    }

    @Override public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_SHIFT) shiftDown = true;
        if (k == KeyEvent.VK_O) {
            setPopupsOn(!popupsOn);
            toast = popupsOn ? Lang.t("浮出字：開（O 切換）", "Floating text: on (O)") : Lang.t("浮出字：關（O 切換）", "Floating text: off (O)");
            toastT = 1.5;
            return;
        }
        if (k == KeyEvent.VK_M) { Audio.toggleMute(); toast = Audio.muted ? Lang.t("已靜音（M 恢復）", "Muted (M to unmute)") : Lang.t("聲音開啟", "Sound on"); toastT = 1.5; return; }
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP && state != State.PLAN) kUp = true;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN && state != State.PLAN) kDown = true;
        if (k == KeyEvent.VK_A) kLeft = true;
        if (k == KeyEvent.VK_D) kRight = true;
        if (state == State.TITLE && soloOnly) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE || k == KeyEvent.VK_1) newGame();
            if (k == KeyEvent.VK_2 || k == KeyEvent.VK_T) newTutorial();
            if (k == KeyEvent.VK_H) help = !help;
            if (k == KeyEvent.VK_L) setLanguage(!Lang.en);
            return;
        }
        if (state == State.TITLE) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE || k == KeyEvent.VK_1) newGame();
            if (k == KeyEvent.VK_2) newPvp();
            if (k == KeyEvent.VK_3) newBossGame();
            if (k == KeyEvent.VK_H) help = !help;
            if (k == KeyEvent.VK_L) setLanguage(!Lang.en);
            return;
        }
        if (state != State.TITLE && world != null && (k == KeyEvent.VK_U || (k == KeyEvent.VK_F && (state == State.RUN || state == State.REPLAY)))) {
            toggleView();
            return;
        }
        if (state == State.REPLAY && pvp && !fullView && panKey(k, true)) return;
        if (state == State.REPLAY) {
            switch (k) {
                case KeyEvent.VK_SPACE:
                    if (replayPaused && world.steps >= rec.endStep) replaySeek(0);
                    replayPaused = !replayPaused;
                    break;
                case KeyEvent.VK_LEFT: replaySeek(world.steps - 300); break;
                case KeyEvent.VK_RIGHT: replaySeek(world.steps + 300); break;
                case KeyEvent.VK_UP: speedIdx = Math.min(SPEEDS.length - 1, speedIdx + 1); break;
                case KeyEvent.VK_DOWN: speedIdx = Math.max(0, speedIdx - 1); break;
                case KeyEvent.VK_R: replaySeek(0); replayPaused = false; break;
                case KeyEvent.VK_ESCAPE: case KeyEvent.VK_P: exitReplay(); break;
                default: break;
            }
            return;
        }
        if (state == State.END) {
            if (endDelay <= 0 && k == KeyEvent.VK_P) { startReplay(); return; }
            if (endDelay <= 0 && (k == KeyEvent.VK_R || k == KeyEvent.VK_ENTER)) { if (pvp) newPvp(); else if (bossMode) newBossGame(); else newGame(); }
            if (k == KeyEvent.VK_ESCAPE) { state = State.TITLE; Audio.music(Audio.MUSIC_DUEL); }
            return;
        }
        if (k == KeyEvent.VK_H) { help = !help; return; }
        if (k == KeyEvent.VK_ESCAPE) { if (help) help = false; else state = State.TITLE; return; }
        if (tutorial != null && !help && tutorial.key(this, k)) return;
        if (state == State.RUN && world != null && world.bossHuman && !help && p2Key(k, true)) return;
        if (k == KeyEvent.VK_V) { toggleWalk(); return; }
        if (state == State.PLAN && pvp && k == KeyEvent.VK_Y) { togglePreviewRange(); return; }
        if (state == State.PLAN || (pvp && state == State.RUN)) {
            if (panKey(k, true)) return;
            if (k == KeyEvent.VK_B) { if (pvp) centerPanOn(world.q.x, world.q.y); else if (!world.b.hidden) centerPanOn(world.b.x, world.b.y); return; }
        }
        if (help) return;
        boolean planning = state == State.PLAN;
        if (!planning) return;
        int ci = cardForKey(k);
        if (ci >= 0) {
            if (heldKey != k) { heldKey = k; heldCard = ci; keyHeldT = 0; }
            return;
        }
        switch (k) {
            case KeyEvent.VK_W: if (planning) command(itemFor(ActionType.RUN, 0, -1)); break;
            case KeyEvent.VK_A: if (planning) command(itemFor(ActionType.RUN, -1, 0)); break;
            case KeyEvent.VK_S: if (planning) command(itemFor(ActionType.RUN, 0, 1)); break;
            case KeyEvent.VK_D: if (planning) command(itemFor(ActionType.RUN, 1, 0)); break;
            case KeyEvent.VK_BACK_SPACE: if (planning) undo(); break;
            case KeyEvent.VK_DELETE: if (planning) clearPlan(); break;
            case KeyEvent.VK_LEFT: if (planning) { cursorT = Math.max(previewFrom(), cursorT - 0.1); manualHold = 2.5; } break;
            case KeyEvent.VK_RIGHT: if (planning) { cursorT = Math.min(previewEnd, cursorT + 0.1); manualHold = 2.5; } break;
            case KeyEvent.VK_ENTER: if (planning) execute(); break;
            default: break;
        }
    }

    /** Index into CARDS of the skill a key triggers, or -1 for keys that are not skills. */
    static int cardForKey(int k) {
        switch (k) {
            case KeyEvent.VK_SPACE: case KeyEvent.VK_N: return 5;
            case KeyEvent.VK_Q: return 6;
            case KeyEvent.VK_E: return 7;
            case KeyEvent.VK_Z: return 8;
            case KeyEvent.VK_C: return 9;
            case KeyEvent.VK_1: case KeyEvent.VK_NUMPAD1: return 10;
            case KeyEvent.VK_2: case KeyEvent.VK_NUMPAD2: return 11;
            case KeyEvent.VK_3: case KeyEvent.VK_NUMPAD3: return 12;
            case KeyEvent.VK_4: case KeyEvent.VK_NUMPAD4: return 13;
            case KeyEvent.VK_F: return 14;
            case KeyEvent.VK_G: return 15;
            case KeyEvent.VK_R: return 16;
            case KeyEvent.VK_T: return 17;
            case KeyEvent.VK_5: case KeyEvent.VK_NUMPAD5: return 18;
            default: return -1;
        }
    }

    /** The card whose details are open because it is being long-pressed, or -1. */
    int infoCard() {
        if (pressCard >= 0 && pressT >= LONG_PRESS) return pressCard;
        if (heldKey >= 0 && keyHeldT >= LONG_PRESS) return heldCard;
        return -1;
    }

    @Override public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_I) kPanU = false;
        if (k == KeyEvent.VK_K) kPanD = false;
        if (k == KeyEvent.VK_J) kPanL = false;
        if (k == KeyEvent.VK_L) kPanR = false;
        if (k == heldKey) {
            int ci = heldCard;
            boolean tap = keyHeldT < LONG_PRESS;
            heldKey = -1; heldCard = -1;
            if (tap && !help && world != null && state == State.PLAN) clickCard(CARDS[ci]);
        }
        if (k == KeyEvent.VK_SHIFT) shiftDown = false;
        if (k == KeyEvent.VK_UP || k == KeyEvent.VK_DOWN || k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT) p2Key(k, false);
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP) kUp = false;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) kDown = false;
        if (k == KeyEvent.VK_A) kLeft = false;
        if (k == KeyEvent.VK_D) kRight = false;
    }
    @Override public void keyTyped(KeyEvent e) {}

    // ------------------------------------------------------------------ fx

    void burst(double x, double y, Color c, int n, double speed) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = fxRng.nextDouble() * Math.PI * 2, s = speed * (0.3 + fxRng.nextDouble() * 0.7);
            p.x = x; p.y = y; p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s - 10;
            p.max = p.life = 0.3 + fxRng.nextDouble() * 0.4;
            p.grav = 60; p.c = c; p.size = fxRng.nextInt(3) == 0 ? 2 : 1;
            parts.add(p);
        }
    }

    /**
     * The flurry as a certain Stand would throw it: every few steps a shout, a spray of impact sparks and speed
     * lines off her fists, a thud and a jolt. Visual only; the punches that count are the four in World.
     */
    void flurryFx(World w) {
        World.Player p = w.p;
        if (!p.is(ActionType.FLURRY) || p.step < ActionType.FLURRY_RUSH_FROM || p.step >= ActionType.FLURRY_RUSH_TO || p.step % 3 != 0) return;
        double fx = p.x + p.face * 15, fy = p.y - 16;
        Popup o = new Popup();
        o.x = fx + p.face * (4 + fxRng.nextDouble() * 10); o.y = fy - 10 - fxRng.nextDouble() * 14;
        o.text = Lang.t("歐拉！", "ORA!"); o.c = p.step % 6 == 0 ? new Color(0xFFE066) : new Color(0xFFB0F0);
        o.life = 0.7; o.shout = true; o.tilt = (fxRng.nextDouble() - 0.5) * 0.5;
        pops.add(o);
        burstDir(fx, fy, p.face, 0, 0.6, new Color(0xFFF4D8), 8, 160);
        impactLines(fx + p.face * 4, fy, Color.WHITE, 4, 10);
        ring(fx + p.face * 3, fy, new Color(0xFFF4D8), 1, 70, 0.12, 1);
        Audio.play(Audio.Sfx.TRUCK, 0.75f);
        shake = Math.max(shake, 11);
        kick(p.face, 0, 3);
    }

    void popup(double x, double y, String text, Color c) {
        Popup p = new Popup();
        p.x = x; p.y = y - pops.size() % 3 * 5; p.text = text; p.c = c; p.life = 1.1;
        pops.add(p);
    }

    double dustT, trailT;

    /** Sparks shed by every spell in flight, Malfoy's and Voldemort's alike. */
    void trails(double dt) {
        trailT -= dt;
        if (trailT > 0) return;
        trailT = 0.03;
        for (World.Obj o : world.objs) {
            Color c;
            switch (o.kind) {
                case P_DENS: c = new Color(0xFFF2A8); break;
                case P_EXP: case P_REFLECT: c = new Color(0xFF5050); break;
                case P_STUP: c = new Color(0x8FD0FF); break;
                case CRUCIO: c = o.active ? new Color(0xFF3030) : null; break;
                case M_BOLT: c = new Color(0x7CFF9A); break;
                default: c = null;
            }
            if (c == null) continue;
            Particle p = new Particle();
            p.x = o.x + (fxRng.nextDouble() - 0.5) * 3; p.y = o.y - Sprites.AIR + (fxRng.nextDouble() - 0.5) * 3;
            p.vx = -o.vx * 0.08 + (fxRng.nextDouble() - 0.5) * 12; p.vy = -o.vy * 0.08 + (fxRng.nextDouble() - 0.5) * 12;
            p.max = p.life = 0.22 + fxRng.nextDouble() * 0.15;
            p.grav = 0; p.c = c; p.size = fxRng.nextInt(3) == 0 ? 2 : 1;
            parts.add(p);
        }
    }

    /** Motes of light pulled into Malfoy while she charges a spell. */
    void chargeFx(double dt) {
        World.Player p = world.p;
        if (!p.charging()) return;
        chargeFxT -= dt;
        if (chargeFxT > 0) return;
        chargeFxT = 0.025;
        Color c = Sprites.spellColor(p.act());
        double a = fxRng.nextDouble() * Math.PI * 2, r = 18 + fxRng.nextDouble() * 6;
        Particle q = new Particle();
        q.x = p.x + Math.cos(a) * r; q.y = p.y - 14 + Math.sin(a) * r * 0.7;
        q.vx = -Math.cos(a) * r * 3.2; q.vy = -Math.sin(a) * r * 2.2;
        q.max = q.life = 0.3; q.grav = 0; q.c = fxRng.nextInt(4) == 0 ? Color.WHITE : c; q.size = fxRng.nextInt(2) + 1;
        parts.add(q);
    }

    /** Every kind of cancel has its own blast, sized to be seen in the thick of a fight. */
    void cancelFx(String what, double x, double gy) {
        double y = gy - Sprites.AIR;
        switch (what) {
            case "CHIP":
                // half a cancel: a crack of sparks, the projectile keeps coming
                burst(x, y, new Color(0xFFF2A8), 14, 80);
                ring(x, y, new Color(0xFFF2A8), 1, 70, 0.18, 1);
                impactLines(x, y, Color.WHITE, 4, 7);
                return;
            case "AVADA":
                // Priori Incantatem: red meets green
                burst(x, y, new Color(0x39FF7A), 44, 190);
                burst(x, y, new Color(0xFF3B3B), 44, 190);
                burst(x, y, Color.WHITE, 16, 120);
                ring(x, y, new Color(0xFFD86B), 3, 260, 0.45, 4);
                ring(x, y, new Color(0x39FF7A), 2, 170, 0.35, 2);
                flashDisc(x, y, Color.WHITE, 22);
                impactLines(x, y, new Color(0xFFD86B), 16, 26);
                whiteT = Math.max(whiteT, 0.08); punch(0.06); hitStop = Math.max(hitStop, 0.1); shake = Math.max(shake, 10);
                showBanner(Lang.t("咒語相撞！", "Spells collide!"), new Color(0xFFD86B));
                return;
            case "SHARD":
                // glass blown to glittering splinters
                burst(x, y, new Color(0xDFF6FF), 40, 180);
                burst(x, y, Color.WHITE, 16, 130);
                burstDir(x, y, 0, -1, 1.4, new Color(0xA8E4FF), 18, 160);
                ring(x, y, new Color(0xBFEFFF), 2, 210, 0.3, 2);
                flashDisc(x, y, Color.WHITE, 12);
                impactLines(x, y, Color.WHITE, 12, 16);
                hitStop = Math.max(hitStop, 0.03);
                return;
            case "M_BOLT":
                // the Death Eater's green bolt shorts out against the yellow light
                burst(x, y, new Color(0x7CFF9A), 34, 170);
                burst(x, y, new Color(0xFFF2A8), 22, 130);
                ring(x, y, new Color(0x7CFF9A), 3, 200, 0.32, 3);
                ring(x, y, new Color(0xFFF2A8), 1, 120, 0.22, 1);
                flashDisc(x, y, new Color(0xB8FFC8), 13);
                impactLines(x, y, new Color(0xB8FFC8), 12, 16);
                hitStop = Math.max(hitStop, 0.03);
                return;
            case "CRUCIO":
                // the curse burst open by Stupefy: red cloud, blue lightning
                burst(x, y, new Color(0xFF3030), 40, 180);
                burst(x, y, new Color(0x8FD0FF), 26, 160);
                ring(x, y, new Color(0xFF5050), 3, 230, 0.38, 3);
                ring(x, y, new Color(0x8FD0FF), 1, 150, 0.26, 2);
                flashDisc(x, y, Color.WHITE, 15);
                impactLines(x, y, new Color(0xCFEFFF), 14, 22);
                shake = Math.max(shake, 5); hitStop = Math.max(hitStop, 0.04);
                return;
            case "REFLECT":
                // a curse thrown back by Protego meets another
                burst(x, y, new Color(0xFF3030), 30, 160);
                burst(x, y, new Color(0xA8E4FF), 30, 160);
                ring(x, y, new Color(0xA8E4FF), 4, 220, 0.4, 3);
                ring(x, y, Color.WHITE, 1, 140, 0.2, 1);
                flashDisc(x, y, new Color(0xA8E4FF), 15);
                impactLines(x, y, Color.WHITE, 10, 18);
                hitStop = Math.max(hitStop, 0.04);
                return;
            case "SERPENT":
                // the fire serpent blown apart in a gout of flame and smoke
                burst(x, y, new Color(0xFF8A1E), 56, 200);
                burst(x, y, new Color(0xFFE066), 26, 140);
                burst(x, y, new Color(0x3A3030), 18, 70);
                embers(x, y, new Color(0xFF8A1E), 26);
                ring(x, y, new Color(0xFF8A1E), 4, 240, 0.45, 4);
                ring(x, gy, new Color(255, 120, 30, 160), 3, 160, 0.5, 3);
                flashDisc(x, y, new Color(0xFFE066), 20);
                shake = Math.max(shake, 7); punch(0.03); hitStop = Math.max(hitStop, 0.05);
                return;
            case "FIRE_RING":
                // the snake swallows the ring whole: flames sucked into a dark vortex
                ring(x, gy, new Color(0xFF8A1E), 46, -140, 0.3, 4);
                ring(x, gy, new Color(0x7CFF9A), 30, -90, 0.3, 2);
                embers(x, y, new Color(0xFF8A1E), 30);
                burst(x, y, new Color(0x15151A), 24, 90);
                burst(x, y, new Color(0x7CFF9A), 18, 110);
                flashDisc(x, y, new Color(0x7CFF9A), 14);
                shake = Math.max(shake, 5); hitStop = Math.max(hitStop, 0.05);
                return;
            case "NAGINI":
                // snake slams into snake: a burst of dust and a heavy shock
                burst(x, y, new Color(0x6E7E50), 44, 190);
                burst(x, y, new Color(0x15151A), 30, 150);
                burstDir(x, gy, 0, -1, 1.2, new Color(0x8A6A4A), 24, 120);
                ring(x, gy, new Color(0xB8A070), 3, 220, 0.45, 3);
                ring(x, y, new Color(0x7CFF9A), 2, 150, 0.3, 2);
                flashDisc(x, y, Color.WHITE, 16);
                impactLines(x, y, Color.WHITE, 16, 22);
                shake = Math.max(shake, 11); punch(0.05); hitStop = Math.max(hitStop, 0.08);
                return;
            case "INFERI":
                // the corpse shattered into bone and black water
                burst(x, y, new Color(0xD8D2BC), 34, 170);
                burst(x, y, new Color(0x7E8A82), 22, 130);
                burst(x, y, new Color(0x2E4A5A), 18, 110);
                ring(x, y, new Color(0xC8D2C6), 2, 180, 0.32, 2);
                ring(x, gy, new Color(0x2E4A5A), 2, 120, 0.4, 2);
                impactLines(x, y, Color.WHITE, 10, 14);
                hitStop = Math.max(hitStop, 0.03);
                return;
            case "BODY":
                // Voldemort's flying body bowls a servant over
                burst(x, y, new Color(0xE8DCC0), 30, 150);
                burst(x, y, new Color(0x3A2E48), 20, 110);
                ring(x, gy, new Color(0xD8C8A0), 3, 180, 0.4, 3);
                flashDisc(x, y, Color.WHITE, 14);
                impactLines(x, y, Color.WHITE, 14, 22);
                shake = Math.max(shake, 9); hitStop = Math.max(hitStop, 0.06); punch(0.04);
                return;
            case "BOUNCE":
                // his own barrage glancing off him
                burst(x, y, new Color(0xFFF0FF), 24, 160);
                burst(x, y, new Color(0xC07CFF), 18, 130);
                ring(x, y, new Color(0xE0C0FF), 2, 200, 0.3, 2);
                flashDisc(x, y, Color.WHITE, 11);
                impactLines(x, y, new Color(0xE0C0FF), 10, 16);
                hitStop = Math.max(hitStop, 0.03);
                return;
            default:
                burst(x, y, new Color(0xFFF0C0), 30, 140);
                ring(x, y, new Color(0xFFF0C0), 2, 160, 0.3, 2);
                flashDisc(x, y, new Color(0xFFF0C0), 11);
                impactLines(x, y, Color.WHITE, 10, 12);
        }
    }

    void ring(double x, double y, Color c, double r0, double grow, double life, int width) {
        Ring r = new Ring();
        r.x = x; r.y = y; r.r = r0; r.grow = grow; r.life = r.max = life; r.c = c; r.width = width;
        rings.add(r);
    }

    void kick(double dx, double dy, double amount) { kickX += dx * amount; kickY += dy * amount; }
    void punch(double z) { zoom = Math.max(zoom, z); }

    void flashDisc(double x, double y, Color c, double r) {
        Ring d = new Ring();
        d.x = x; d.y = y; d.r = r; d.grow = r * 3; d.life = d.max = 0.12; d.c = c; d.fill = true;
        rings.add(d);
    }

    void impactLines(double x, double y, Color c, int n, double len) {
        for (int i = 0; i < n; i++) {
            Streak s = new Streak();
            s.x = x; s.y = y; s.ang = fxRng.nextDouble() * Math.PI * 2; s.len = len * (0.6 + fxRng.nextDouble() * 0.6);
            s.life = s.max = 0.12 + fxRng.nextDouble() * 0.08; s.c = c;
            streaks.add(s);
        }
    }

    /** Sparks thrown mostly one way: out of the far side of whatever was hit. */
    void burstDir(double x, double y, double dx, double dy, double spread, Color c, int n, double speed) {
        double base = Math.atan2(dy, dx);
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = base + (fxRng.nextDouble() - 0.5) * 2 * spread, sp = speed * (0.4 + fxRng.nextDouble() * 0.8);
            p.x = x; p.y = y; p.vx = Math.cos(a) * sp; p.vy = Math.sin(a) * sp;
            p.max = p.life = 0.2 + fxRng.nextDouble() * 0.3;
            p.grav = 90; p.c = c; p.size = fxRng.nextInt(3) == 0 ? 2 : 1;
            parts.add(p);
        }
    }

    /** Glowing motes that drift upward after a blast. */
    void embers(double x, double y, Color c, int n) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = x + (fxRng.nextDouble() - 0.5) * 10; p.y = y + (fxRng.nextDouble() - 0.5) * 6;
            p.vx = (fxRng.nextDouble() - 0.5) * 20; p.vy = -10 - fxRng.nextDouble() * 25;
            p.max = p.life = 0.6 + fxRng.nextDouble() * 0.6;
            p.grav = -8; p.c = c; p.size = 2;
            parts.add(p);
        }
    }

    /** Stupefy leaves a column of light where it lands. */
    void pillar(double x, double y, Color c) {
        for (int i = 0; i < 14; i++) {
            Particle p = new Particle();
            p.x = x + (fxRng.nextDouble() - 0.5) * 6; p.y = y - fxRng.nextDouble() * 10;
            p.vx = 0; p.vy = -60 - fxRng.nextDouble() * 80;
            p.max = p.life = 0.35 + fxRng.nextDouble() * 0.2;
            p.grav = 0; p.c = i % 3 == 0 ? Color.WHITE : c; p.size = 2;
            parts.add(p);
        }
    }

    /** The visible trail of a melee move around Malfoy. */
    void slash(ActionType a, double[] d) {
        World.Player p = world.p;
        if (a == null) return;
        Arc s = new Arc();
        s.x = p.x; s.y = p.y - 12; s.c = new Color(0xFFF4D8);
        double ang = Math.toDegrees(Math.atan2(-d[1], d[0]));
        switch (a) {
            case SPIN: s.r = a.meleeRadius(); s.a0 = ang; s.ext = 360; s.width = 4; s.life = s.max = 0.25; break;
            case THROW: return;
            default: s.r = a.meleeRadius() * 0.9; s.a0 = ang - 55; s.ext = 110; s.width = 3; s.life = s.max = 0.16; break;
        }
        arcs.add(s);
        Arc inner = new Arc();
        inner.x = s.x; inner.y = s.y; inner.r = s.r * 0.75; inner.a0 = s.a0 + 10; inner.ext = s.ext - 20;
        inner.width = 1; inner.life = inner.max = s.max; inner.c = new Color(0xFFD86B);
        arcs.add(inner);
    }

    void damageNumber(double x, double y, int dmg) {
        Popup p = new Popup();
        p.x = x + (fxRng.nextDouble() - 0.5) * 10; p.y = y; p.text = "-" + dmg; p.life = 0.9; p.big = true;
        p.c = dmg >= 12 ? new Color(0xFF6A50) : new Color(0xFFFFFF);
        pops.add(p);
    }

    static Color alphaOf(Color c, int a) { return Sprites.alpha(c, a); }

    static Color impactColor(int kindOrdinal) {
        World.Kind k = World.Kind.values()[kindOrdinal];
        switch (k) {
            case P_DENS: return new Color(0xFFF2A8);
            case P_EXP: case P_REFLECT: return new Color(0xFF4A4A);
            case P_STUP: return new Color(0x8FD0FF);
            case P_SNAKE: return new Color(0x7CFF9A);
            default: return Color.WHITE;
        }
    }

    /** Puffs of dust behind Malfoy's heels while she runs (not while walking). */
    void kickDust(double dt) {
        World.Player p = world.p;
        boolean running = (p.is(ActionType.RUN) && !p.cur.alt) || p.is(ActionType.DASH)
                          || (p.idle() && !p.walk && (p.freeDx != 0 || p.freeDy != 0));
        if (!running) return;
        dustT -= dt;
        if (dustT > 0) return;
        dustT = 0.11;
        for (int i = 0; i < 2; i++) {
            Particle d = new Particle();
            d.x = p.x - p.face * (3 + fxRng.nextDouble() * 3); d.y = p.y - 1;
            d.vx = -p.face * (8 + fxRng.nextDouble() * 10); d.vy = -6 - fxRng.nextDouble() * 6;
            d.max = d.life = 0.35 + fxRng.nextDouble() * 0.2;
            d.grav = 20; d.c = new Color(0x8A7A68); d.size = fxRng.nextInt(2) + 1;
            parts.add(d);
        }
    }

    void showBanner(String s, Color c) { banner = s; bannerColor = c; bannerT = 2.2; }

    /**
     * Screen height of the big banner: under the HUD, above the characters' heads (Malfoy's feet sit near y 300 on
     * screen and her head near 183), so announcements never cover the fight.
     */
    static final int BANNER_Y = 132;
    /** Floating score, damage and shout texts start this many screen pixels higher, clear of the heads. */
    static final int POPUP_LIFT = 36;

    /** Whether floating texts (scores, damage, shouts) and banners are shown; remembered between runs. */
    static boolean popupsOn = loadPopupsOn();

    static boolean loadPopupsOn() {
        try { return java.util.prefs.Preferences.userRoot().node("draco-duel").getBoolean("popups", true); } catch (Throwable e) { return true; }
    }

    static void setPopupsOn(boolean on) {
        popupsOn = on;
        try { java.util.prefs.Preferences.userRoot().node("draco-duel").putBoolean("popups", on); } catch (Throwable ignored) { }
    }
    void showToast(String s) { toast = s; toastT = 1.8; Audio.play(Audio.Sfx.ERROR, 0.7f); }

    void updateFx(double dt) {
        for (Iterator<Particle> it = parts.iterator(); it.hasNext();) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.vy += p.grav * dt; p.x += p.vx * dt; p.y += p.vy * dt;
        }
        for (Iterator<Popup> it = pops.iterator(); it.hasNext();) {
            Popup p = it.next();
            p.life -= dt; p.y -= (p.big ? 24 : 14) * dt;
            if (p.life <= 0) it.remove();
        }
        for (Iterator<Ring> it = rings.iterator(); it.hasNext();) {
            Ring r = it.next();
            r.life -= dt; r.r += r.grow * dt;
            if (r.life <= 0) it.remove();
        }
        for (Iterator<Arc> it = arcs.iterator(); it.hasNext();) {
            Arc a = it.next();
            a.life -= dt;
            if (a.life <= 0) it.remove();
        }
        for (Iterator<Streak> it = streaks.iterator(); it.hasNext();) {
            Streak st = it.next();
            st.life -= dt;
            if (st.life <= 0) it.remove();
        }
        double k = Math.exp(-dt * 16);
        kickX *= k; kickY *= k;
        zoom *= Math.exp(-dt * 7);
        if (whiteT > 0) whiteT -= dt;
    }

    // ------------------------------------------------------------------ rendering

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        viewScale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
        viewX = (getWidth() - W * viewScale) / 2;
        viewY = (getHeight() - H * viewScale) / 2;
        g.translate(viewX, viewY);
        g.scale(viewScale, viewScale);
        g.setClip(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        if (state == State.TITLE || world == null) { drawTitle(g); if (help) drawHelp(g); return; }

        renderLow();
        int sx = (shake > 0 ? (int) ((fxRng.nextDouble() - 0.5) * shake) : 0) + (int) Math.round(kickX * SCALE);
        int sy = (shake > 0 ? (int) ((fxRng.nextDouble() - 0.5) * shake) : 0) + (int) Math.round(kickY * SCALE);
        if (fullView) {
            // the whole hall, scaled to fit above the bottom panel
            vk = Math.min(W / (double) World.MAPW, PANEL_Y / (double) World.MAPH);
            vox = (W - World.MAPW * vk) / 2; voy = (PANEL_Y - World.MAPH * vk) / 2;
            g.setColor(new Color(0x07060C));
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, (int) Math.round(vox) + sx / 2, (int) Math.round(voy) + sy / 2, (int) Math.round(World.MAPW * vk), (int) Math.round(World.MAPH * vk), null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        } else {
            vk = SCALE; vox = 0; voy = 0;
            double zs = 1 + zoom;
            double zcx = LW * SCALE / 2.0, zcy = VIEW_H * SCALE / 2.0;
            int dw = (int) Math.round(LW * SCALE * zs), dh = (int) Math.round(LH * SCALE * zs);
            g.drawImage(low, (int) Math.round(zcx - zcx * zs) + sx, (int) Math.round(zcy - zcy * zs) + sy, dw, dh, null);
        }
        if (flashT > 0) { g.setColor(Sprites.alpha(flashColor, (int) (flashT * 300))); g.fillRect(0, 0, W, H); }
        if (whiteT > 0) { g.setColor(Sprites.alpha(Color.WHITE, (int) Math.min(170, whiteT * 2200))); g.fillRect(0, 0, W, PANEL_Y); }

        resetLabels();
        drawPopups(g);
        drawBossPointer(g);
        drawHud(g);
        drawMinimap(g);
        if (state == State.REPLAY) drawReplayPanel(g);
        else if (state == State.PLAN) { drawHoverOrder(g); drawPlanPanel(g); }
        else drawRunPanel(g);
        if (tutorial != null && state != State.REPLAY && state != State.END && !help) tutorial.draw(this, g);
        if (bannerT > 0 && banner != null && popupsOn) {
            g.setFont(fBig);
            centered(g, banner, W / 2, BANNER_Y, Sprites.alpha(bannerColor, (int) (Math.min(1, bannerT) * 255)));
        }
        if (toastT > 0 && toast != null) {
            g.setFont(fMid);
            int tw = g.getFontMetrics().stringWidth(toast);
            g.setColor(new Color(0, 0, 0, 180));
            g.fillRoundRect(W / 2 - tw / 2 - 16, 440, tw + 32, 36, 12, 12);
            centered(g, toast, W / 2, 465, new Color(0xFFB0B0));
        }
        int info = infoCard();
        if (info >= 0 && state == State.PLAN && !help) drawSkillInfo(g, CARDS[info]);
        if (state == State.END && endDelay <= 0) drawEnd(g);
        if (help) drawHelp(g);
        tipBox = tipBoxNow;
        tipBoxNow = null;
    }

    static String catName(ActionType.Cat c) {
        return Lang.en ? new String[] { "Move", "Wait", "Dodge", "Ranged spell", "Shield", "Melee", "" }[c.ordinal()]
                       : new String[] { "移動", "等待", "閃避", "遠程咒語", "常駐", "近戰", "" }[c.ordinal()];
    }

    static final String NOW_ZH = "目前：", NOW_EN = "Now: ";
    static String NOW = NOW_ZH;

    /** Full description of a skill, opened by long-pressing its card or key. */
    void drawSkillInfo(Graphics2D g, Card cd) {
        ActionType a = cd.type;
        NOW = Lang.t(NOW_ZH, NOW_EN);
        World.Player ref = state == State.PLAN && planEndWorld != null ? planEndWorld.p : world.p;
        ArrayList<String> lines = new ArrayList<>();
        String title, sub;
        Color accent;
        if (a == null) {
            title = Lang.t("跑步 / 漫步", "Run / Walk"); sub = Lang.t("按鍵 V", "Key V"); accent = new Color(0x9FD0FF);
            lines.add(Lang.t("切換移動速度：跑步每秒 75 像素、漫步每秒 40 像素。每局從跑步開始。", "Switches movement speed: running is 75 px per second, walking 40. Every duel starts running."));
            lines.add(Lang.t("影響 WASD 與左鍵點地圖的移動。漫步適合貼著彈幕邊緣細微走位。", "Applies to WASD and left-click moves. Walking is for fine steps along the edge of a barrage."));
        } else {
            title = Lang.en ? a.latin : a.zh + "　" + a.latin;
            sub = Lang.t("按鍵 ", "Key ") + cd.keyName() + Lang.t("　·　", "  ·  ") + catName(a.cat);
            accent = a.color();
            StringBuilder st = new StringBuilder();
            int mana = a.mana(pvp), sta = a.stamina(pvp);
            double cool = a.cooldown(pvp);
            if (a.isSpell() && !a.charged()) st.append(Lang.t("免吟唱：按一次 " + trim(a.dur) + " 秒直接射出　消耗 " + mana + " 魔力",
                                                              "Wordless: one press fires it in " + trim(a.dur) + " s   costs " + mana + " mana"));
            else if (a.isSpell()) st.append(String.format(Lang.t("讀條 %.1f 秒（消耗 %d 魔力）→ 儲存 → 再按一次施放 0.2 秒（不再扣魔力）", "Charge %.1f s (costs %d mana) → stored → press again to cast in 0.2 s (no more mana)"), a.dur, mana));
            else st.append(Lang.t("動作時間 " + trim(a.dur) + " 秒", "Action time " + trim(a.dur) + " s"));
            if (cool > 0) st.append(String.format(a.charged() ? Lang.t("　施放後恢復 %s 秒才能再讀條", "   %s s to recover after casting") : Lang.t("　冷卻 %s 秒", "   cooldown %s s"), trim(cool)));
            if (mana > 0 && !a.isSpell()) st.append(Lang.t("　消耗魔力 ", "   mana cost ")).append(mana);
            if (sta > 0) st.append(Lang.t("　消耗體力 ", "   stamina cost ")).append(sta);
            lines.add(st.toString());
            if (a.isSpell()) lines.add(String.format(Lang.t("飛行速度：每 0.1 秒 %.0f m（每秒 %d m）", "Flight: %.0f m every 0.1 s (%d m a second)"), a.speed() * 0.1, (int) a.speed()));
            int[] inv = a.iframes(), air = a.airtime();
            if (inv != null) lines.add(String.format(Lang.t("無敵時間：%.2f ～ %.2f 秒（時間軸上的白線）", "Invincible: %.2f – %.2f s (white line on the timeline)"), inv[0] / 60.0, (inv[1] + 1) / 60.0));
            if (air != null) lines.add(String.format(Lang.t("騰空時間：%.2f ～ %.2f 秒（時間軸上的青線），可越過火焰、火環、娜吉妮", "Airborne: %.2f – %.2f s (cyan line on the timeline), clears fire, fire rings and Nagini"), air[0] / 60.0, (air[1] + 1) / 60.0));
            if (a.isMelee()) lines.add(String.format(Lang.t("範圍圈半徑 %d：圈內的佛地魔、食死人、陰屍全部命中（圈變綠＝有敵人）", "Circle radius %d: hits Voldemort, Death Eaters and Inferi inside it (a green circle = enemy in range)"), (int) a.meleeRadius()));
            if (a.isSpell()) lines.add((a.charged() ? Lang.t("瞄準：施放當下朝滑鼠指的位置（不追蹤）", "Aim: flies toward the mouse at the moment of casting (no homing)") : Lang.t("瞄準：自動追蹤最近的敵人", "Aim: homes on the nearest enemy"))
                                       + Lang.t("。飛行中撞到它能對抗的敵方咒語會互相抵銷，石柱與噴泉會擋下咒語。", ". In flight it cancels enemy spells it counters; pillars and the fountain block spells."));
            lines.add(a.info(pvp));
            String now = "", sep = Lang.t("；", "; ");
            if (a.charged() && ref.stored[a.spellIndex()]) now = NOW + Lang.t("已儲存，下一次按下就會施放", "stored, the next press casts it");
            else if (a.charged()) now = NOW + Lang.t("未儲存，下一次按下會開始讀條", "not stored, the next press starts charging");
            if (ref.cd[a.ordinal()] > 1e-9) now = (now.isEmpty() ? NOW : now + sep) + String.format(Lang.t("冷卻剩 %.1f 秒", "%.1f s of cooldown left"), ref.cd[a.ordinal()]);
            if (a.mana(pvp) > ref.mana) now = (now.isEmpty() ? NOW : now + sep) + Lang.t("魔力不足", "not enough mana");
            if (a.stamina(pvp) > ref.sta) now = (now.isEmpty() ? NOW : now + sep) + Lang.t("體力不足", "not enough stamina");
            now += fastestHitText(a, now.isEmpty() ? NOW : sep);
            if (!now.isEmpty()) lines.add(now);
        }
        int w = 720, x = (W - w) / 2;
        g.setFont(fSmall);
        ArrayList<String> wrapped = new ArrayList<>();
        for (String l : lines) wrapped.addAll(wrap(g, l, w - 40));
        int h = 74 + wrapped.size() * 22, y = PANEL_Y - 20 - h;
        g.setColor(new Color(6, 8, 20, 240));
        g.fillRoundRect(x, y, w, h, 14, 14);
        tipBoxNow = new Rectangle(x, y, w, h);
        g.setColor(accent);
        g.fillRect(x, y, w, 4);
        g.drawRoundRect(x, y, w, h, 14, 14);
        g.setFont(fBig);
        shadowText(g, title, x + 20, y + 40, Color.WHITE);
        g.setFont(fSmall);
        shadowText(g, sub, x + w - 20 - g.getFontMetrics().stringWidth(sub), y + 38, accent.brighter());
        int ly = y + 68;
        for (int i = 0; i < wrapped.size(); i++, ly += 22)
            shadowText(g, wrapped.get(i), x + 20, ly, wrapped.get(i).startsWith(NOW) ? new Color(0xFFD86B) : new Color(0xD8DEE8));
    }

    int camIX() { return (int) Math.round(camX); }
    int camIY() { return (int) Math.round(camY); }

    /** The whole world in big pixels: the floor, then scenery and people sorted by depth, then the air. */
    void renderLow() {
        BufferedImage target = fullView ? full : low;
        int tw = target.getWidth(), th = target.getHeight();
        Graphics2D g = target.createGraphics();
        g.setColor(new Color(0x07060C));
        g.fillRect(0, 0, tw, th);
        g.translate(-camIX(), -camIY());
        g.drawImage(map, 0, 0, null);
        Sprites.mapAnim(g, animT);
        World base = viewWorld();
        if (base.p.charging()) Sprites.magicCircle(g, base.p.x, base.p.y, Sprites.spellColor(base.p.act()), base.p.progress(), animT);
        drawFloor(g, base);
        drawPeople(g, base);
        drawAir(g, base);
        for (Particle p : parts) {
            int a = (int) (255 * p.life / p.max);
            if (p.size > 1) { g.setColor(Sprites.alpha(p.c, a / 4)); g.fillRect((int) p.x - 1, (int) p.y - 1, p.size + 2, p.size + 2); }
            g.setColor(Sprites.alpha(p.c, a));
            g.fillRect((int) p.x, (int) p.y, p.size, p.size);
        }
        for (Ring r : rings) {
            int a = (int) (230 * r.life / r.max), rr = (int) r.r;
            if (r.fill) {
                g.setColor(Sprites.alpha(r.c, a * 2 / 3));
                g.fillOval((int) r.x - rr, (int) r.y - rr, rr * 2, rr * 2);
                g.setColor(Sprites.alpha(Color.WHITE, a));
                g.fillOval((int) r.x - rr / 2, (int) r.y - rr / 2, rr, rr);
                continue;
            }
            g.setStroke(new BasicStroke(r.width));
            g.setColor(Sprites.alpha(r.c, Math.min(a, r.c.getAlpha())));
            g.drawOval((int) r.x - rr, (int) r.y - rr * 3 / 4, rr * 2, rr * 3 / 2);
            g.setStroke(new BasicStroke(1));
        }
        for (Arc s : arcs) {
            double f = s.life / s.max;
            int rr = (int) s.r;
            // the crescent sweeps open, then thins out
            double ext = s.ext * Math.min(1, (1 - f) * 2.5 + 0.2);
            g.setStroke(new BasicStroke(Math.max(1, (int) Math.round(s.width * f + 0.5)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Sprites.alpha(s.c, (int) (60 * f)));
            g.drawArc((int) s.x - rr - 2, (int) s.y - rr * 3 / 4 - 2, rr * 2 + 4, rr * 3 / 2 + 4, (int) s.a0, (int) ext);
            g.setColor(Sprites.alpha(s.c, (int) (240 * f)));
            g.drawArc((int) s.x - rr, (int) s.y - rr * 3 / 4, rr * 2, rr * 3 / 2, (int) s.a0, (int) ext);
            g.setStroke(new BasicStroke(1));
        }
        for (Streak st : streaks) {
            double f = st.life / st.max, r0 = st.len * (1 - f) * 0.8 + 3, r1 = r0 + st.len * f;
            g.setColor(Sprites.alpha(st.c, (int) (230 * f)));
            g.drawLine((int) (st.x + Math.cos(st.ang) * r0), (int) (st.y + Math.sin(st.ang) * r0 * 0.8),
                       (int) (st.x + Math.cos(st.ang) * r1), (int) (st.y + Math.sin(st.ang) * r1 * 0.8));
        }
        if (state == State.PLAN) {
            // bullet time: the real world is frozen and dimmed, the future plays as ghosts
            g.setColor(new Color(12, 20, 60, 120));
            g.fillRect(camIX(), camIY(), tw, th);
            if (btAnim < 0.6) {
                int r = (int) (btAnim * 500);
                g.setColor(new Color(160, 200, 255, (int) (200 * (1 - btAnim / 0.6))));
                g.setStroke(new BasicStroke(2));
                g.drawOval((int) world.p.x - r, (int) world.p.y - 12 - r, r * 2, r * 2);
                g.setStroke(new BasicStroke(1));
            }
            drawGhosts(g);
        } else {
            Sprites.bossArrow(g, world.p, world.b);
        }
        // melee reach circles belong to planning only: the hovered card's, and the ghosts' in the preview
        if (state == State.PLAN && hover >= 0 && CARDS[hover].type != null && CARDS[hover].type.isMelee()) {
            World.Player o = origin();
            ActionType a = CARDS[hover].type;
            World ref = state == State.PLAN ? planEndWorld : world;
            Sprites.meleeCircle(g, o.x, o.y, a.meleeRadius(), ref != null && enemyInside(ref, o, a), 230);
        }
        g.dispose();
    }

    static boolean enemyInside(World w, World.Player p, ActionType a) {
        double r = a.meleeRadius();
        if (!w.b.hidden && Math.hypot(w.b.x - p.x, w.b.y - p.y) <= r + 6) return true;
        for (World.Obj o : w.objs) {
            if ((o.kind == World.Kind.MINION || o.kind == World.Kind.INFERI) && o.active && !o.dead && Math.hypot(o.x - p.x, o.y - p.y) <= r + 5) return true;
        }
        return false;
    }

    /** One depth-sorted thing on the map. */
    static final class Drawable {
        final double y; final int kind; final Object ref;
        Drawable(double y, int kind, Object ref) { this.y = y; this.kind = kind; this.ref = ref; }
    }

    /** Everything lying on the floor. */
    void drawFloor(Graphics2D g, World w) {
        for (World.Obj o : w.objs) if (Sprites.layer(o) == 0) Sprites.obj(g, w, o, w.time);
    }

    /** Everything flying above the people. */
    void drawAir(Graphics2D g, World w) {
        for (World.Obj o : w.objs) if (Sprites.layer(o) == 2) Sprites.obj(g, w, o, w.time);
    }

    /** Scenery and everyone in it, sorted by depth. */
    void drawPeople(Graphics2D g, World w) {
        double t = w.time;
        ArrayList<Drawable> ds = new ArrayList<>();
        for (int i = 0; i < World.OBST.length; i++) ds.add(new Drawable(World.OBST[i][1], 0, i));
        ds.add(new Drawable(w.p.y, 1, w.p));
        if (w.pvp) ds.add(new Drawable(w.q.y, 4, w.q));
        if (!w.b.hidden) ds.add(new Drawable(w.b.y, 2, w.b));
        for (World.Obj o : w.objs) if (Sprites.layer(o) == 1) ds.add(new Drawable(o.y, 3, o));
        Collections.sort(ds, (a, b) -> Double.compare(a.y, b.y));
        for (Drawable d : ds) {
            switch (d.kind) {
                case 0: {
                    int i = (Integer) d.ref;
                    double[] ob = World.OBST[i];
                    if (i == 0) { g.drawImage(fountain, (int) ob[0] - 36, (int) ob[1] - 62, null); Sprites.fountainWater(g, animT); }
                    else g.drawImage(pillar, (int) ob[0] - 12, (int) ob[1] - 65, null);
                    break;
                }
                case 1: case 4: {
                    World.Player pl = (World.Player) d.ref;
                    if (w.pvp) {
                        // a ring of house colour under each duellist's feet
                        Color hc = sideColor(pl.side);
                        g.setColor(new Color(hc.getRed(), hc.getGreen(), hc.getBlue(), 110));
                        g.drawOval((int) Math.round(pl.x) - 9, (int) Math.round(pl.y) - 3, 18, 6);
                    }
                    Chars.alt = pl.side == 1;
                    Sprites.draco(g, pl, t, null);
                    Chars.alt = false;
                    if (pl.shield()) Sprites.shield(g, pl, t, null);
                    break;
                }
                case 2: Sprites.voldemort(g, w.b, t, w.b.busy > 0 && casting(w), null, w.p.x >= w.b.x ? 1 : -1); break;
                default: Sprites.obj(g, w, (World.Obj) d.ref, t); break;
            }
        }
        Chars.barrageOverlay(g, w.p, t);
        if (w.pvp) Chars.barrageOverlay(g, w.q, t);
    }

    static boolean casting(World w) {
        for (World.Obj o : w.objs) if (o.hostile && !o.active && o.castId == w.b.castId) return true;
        return false;
    }

    void drawGhosts(Graphics2D g) {
        Composite old = g.getComposite();
        // Voldemort chooses his next spell by distance when he casts again: show the bands there
        World end = planEndWorld != null ? planEndWorld : world;
        if (!end.b.hidden) {
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] { 3, 4 }, 0));
            int bx = (int) end.b.x, by = (int) end.b.y;
            int band = World.rangeBand(Math.hypot(end.p.x - end.b.x, end.p.y - end.b.y));
            int[] r = { (int) World.CLOSE_RANGE, (int) World.FAR_RANGE };
            for (int i = 0; i < 2; i++) {
                g.setColor(new Color(200, 160, 255, band == i || band == i + 1 ? 130 : 60));
                g.drawOval(bx - r[i], by - r[i], r[i] * 2, r[i] * 2);
            }
            g.setStroke(new BasicStroke(1));
        }
        g.setColor(new Color(140, 230, 255, 200));
        double from = previewFrom();
        // path points are three steps apart
        for (int i = Math.max(1, (int) Math.ceil(from / (3 * DT) - 1e-6) + 1); i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            g.fillRect((int) b[0], (int) b[1], 1, 1);
            if (i % 2 == 0) g.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
        }
        for (Ghost gh : ghosts) {
            if (gh.t < from - 1e-6) continue;
            ActionType a = gh.pl.act();
            if (a != null && a.isMelee()) {
                World tmp = world.copy();
                tmp.b = gh.boss;
                Sprites.meleeCircle(g, gh.pl.x, gh.pl.y, a.meleeRadius(), enemyInside(tmp, gh.pl, a), 170);
            }
        }
        g.setComposite(Sprites.alphaComp(0.3f));
        for (Ghost gh : ghosts) if (gh.t >= from - 1e-6) Sprites.draco(g, gh.pl, gh.t, new Color(0x9FE8FF));
        for (Ghost gh : foeGhosts) if (gh.t >= from - 1e-6) Sprites.draco(g, gh.pl, gh.t, new Color(0xFFA0A0));

        World s = snapAt(cursorT);
        double t = s.time;
        g.setComposite(Sprites.alphaComp(0.65f));
        for (World.Obj o : s.objs) Sprites.obj(g, s, o, t);
        g.setComposite(Sprites.alphaComp(0.45f));
        if (!s.b.hidden) Sprites.voldemort(g, s.b, t, false, new Color(0xC9A0FF), s.p.x >= s.b.x ? 1 : -1);
        if (s.pvp) {
            g.setComposite(Sprites.alphaComp(0.7f));
            Sprites.draco(g, s.q, t, s.q.act() == ActionType.HURT ? new Color(0xFF7070) : new Color(0xFFC8C8));
            if (s.q.shield()) Sprites.shield(g, s.q, t, new Color(0xFFC8C8));
        }
        g.setComposite(Sprites.alphaComp(0.8f));
        boolean hurt = s.p.act() == ActionType.HURT;
        Sprites.draco(g, s.p, t, hurt ? new Color(0xFF7070) : new Color(0xBFF3FF));
        if (s.p.shield()) Sprites.shield(g, s.p, t, new Color(0xBFF3FF));
        g.setComposite(old);
        Sprites.bossArrow(g, s.p, s.b);
    }

    int toSX(double wx) { return (int) Math.round((wx - camX) * vk + vox); }
    int toSY(double wy) { return (int) Math.round((wy - camY) * vk + voy); }

    /** Screen boxes taken this frame by the HUD and by labels already drawn: map labels step aside from them. */
    final ArrayList<Rectangle> taken = new ArrayList<>();
    /** Where a card's tooltip or description box was drawn last frame (and this frame so far), for labels to avoid. */
    Rectangle tipBox, tipBoxNow;

    /** A new frame: only the fixed HUD boxes are taken. */
    void resetLabels() {
        taken.clear();
        taken.add(new Rectangle(16, 12, 330, 82));
        taken.add(new Rectangle(W - 346, 12, 330, 82));
        taken.add(new Rectangle(W / 2 - 230, 8, 460, 90));          // score / round and the lines under it
        if (!fullView) taken.add(new Rectangle(W - 180, 100, 176, 112)); // minimap
        taken.add(viewRect);
        if (pvp && state == State.PLAN) { taken.add(handRect); taken.add(rangeRect); }
        if (tipBox != null && state == State.PLAN && (hover >= 0 || infoCard() >= 0)) taken.add(tipBox);
    }

    /**
     * Draws a label on baseline y, centred on x or starting at x, in the current font: moved a line down or up at a
     * time until it clears everything taken this frame, kept on screen and above the bottom panel.
     */
    void tag(Graphics2D g, String text, int x, int y, Color c, boolean center) {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text), h = fm.getAscent() + fm.getDescent();
        int lx = Math.max(4, Math.min(W - 4 - w, center ? x - w / 2 : x));
        int minY = fm.getAscent() + 2, maxY = PANEL_Y - 4 - fm.getDescent();
        int by = Math.max(minY, Math.min(maxY, y));
        Rectangle box = null;
        for (int k = 0; k < 41 && box == null; k++) {
            int off = (k + 1) / 2 * (h + 1) * (k % 2 == 1 ? 1 : -1);   // 0, +1, -1, +2, -2 lines…
            int yy = y + off;
            if (yy < minY || yy > maxY) continue;
            Rectangle r = new Rectangle(lx - 2, yy - fm.getAscent(), w + 4, h);
            boolean free = true;
            for (Rectangle t : taken) if (t.intersects(r)) { free = false; break; }
            if (free) { box = r; by = yy; }
        }
        if (box == null) box = new Rectangle(lx - 2, by - fm.getAscent(), w + 4, h);
        taken.add(box);
        shadowText(g, text, lx, by, c);
    }

    void drawPopups(Graphics2D g) {
        // the result screen covers the hall: floating text left over from the last moments would only clutter it
        boolean show = popupsOn && !(state == State.END && endDelay <= 0);
        for (Popup p : show ? pops : java.util.Collections.<Popup>emptyList()) {
            int a = (int) (255 * Math.min(1, p.life / 0.4));
            if (p.shout) {
                // a comic-book shout: heavy, tilted, outlined, slamming in large then settling
                double age = 0.7 - p.life, pop = age < 0.08 ? 1.6 - age / 0.08 * 0.6 : 1.0;
                Graphics2D s = (Graphics2D) g.create();
                s.translate(toSX(p.x), toSY(p.y) - POPUP_LIFT);
                s.rotate(p.tilt);
                s.setFont(fTitle.deriveFont((float) (34 * pop)));
                int w = s.getFontMetrics().stringWidth(p.text);
                s.setColor(new Color(40, 10, 50, a));
                for (int dx = -3; dx <= 3; dx += 3) for (int dy = -3; dy <= 3; dy += 3) s.drawString(p.text, -w / 2 + dx, dy);
                s.setColor(Sprites.alpha(p.c, a));
                s.drawString(p.text, -w / 2, 0);
                s.setColor(new Color(255, 255, 255, a * 2 / 3));
                s.drawString(p.text, -w / 2 - 1, -2);
                s.dispose();
                continue;
            }
            if (p.big) {
                double age = 0.9 - p.life, pop = age < 0.12 ? 1.9 - age / 0.12 * 0.9 : 1.0;
                g.setFont(fBig.deriveFont((float) (fBig.getSize() * pop * (p.c.getRed() > 250 && p.c.getGreen() < 200 ? 1.25 : 1.0))));
            } else g.setFont(fSmall);
            tag(g, p.text, toSX(p.x), toSY(p.y) - POPUP_LIFT, Sprites.alpha(p.c, a), true);
        }
        if (state == State.PLAN) {
            g.setFont(fTiny);
            double from = previewFrom();
            for (Ghost gh : ghosts) if (gh.t >= from - 1e-6) tag(g, String.format("+%.1fs", gh.t), toSX(gh.pl.x), toSY(gh.pl.y - gh.pl.z() - 46), new Color(170, 235, 255, 210), true);
            World end = planEndWorld;
            if (end != null && !end.b.hidden) {
                int band = World.rangeBand(Math.hypot(end.p.x - end.b.x, end.p.y - end.b.y));
                g.setFont(fSmall);
                tag(g, Lang.t("計畫結束：此距離＝", "Plan ends: range = ") + World.bandName(band), toSX(end.p.x), toSY(end.p.y + 14), new Color(0xD8B0FF), true);
            }
        }
    }

    /** Where a click would send Malfoy, and how long it takes; in aim mode, where her spells would fly. */
    void drawHoverOrder(Graphics2D g) {
        if (state == State.PLAN && !help && !Double.isNaN(lastMapWX)) {
            World.Player o = origin();
            double[] d = aimDir();
            double len = Math.max(1e-6, Math.hypot(d[0], d[1]));
            int x0 = toSX(o.x), y0 = toSY(o.y - Sprites.AIR);
            int x1 = toSX(o.x + d[0]), y1 = toSY(o.y - Sprites.AIR + d[1]);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[] { 2, 8 }, (float) (animT * 40 % 10)));
            g.setColor(new Color(255, 110, 110, 210));
            g.drawLine(x0, y0, x1, y1);
            g.setStroke(new BasicStroke(1));
            g.fillOval(x1 - 4, y1 - 4, 8, 8);
            g.setFont(fTiny);
            // the line stays while a card is pointed at, but its caption would sit under the card's description
            if (hover < 0 && infoCard() < 0)
                tag(g, Lang.t("繳械・昏擊朝這裡／黑蛇埋伏點", "Expelliarmus / Stupefy aim here · snake ambush spot"), x1 + 10, y1 + 4, new Color(0xFFB0B0), false);
        }
        if (help || hover >= 0 || !onMap(mouseLX, mouseLY)) return;
        World.Player e = origin();
        double wx = mouseWX(), wy = mouseWY();
        double dist = Math.hypot(wx - e.x, wy - e.y);
        boolean roll = shiftDown;
        double speed = walkMode ? ActionType.WALK_SPEED : World.RUN_SPEED;
        double dur = roll ? ActionType.ROLL.dur : Math.min(Math.ceil(dist / speed * 60), state == State.PLAN ? roomSteps() : 240) / 60.0;
        int x0 = toSX(e.x), y0 = toSY(e.y), x1 = (int) mouseLX, y1 = (int) mouseLY;
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[] { 6, 6 }, 0));
        g.setColor(roll ? new Color(255, 216, 107, 200) : new Color(160, 230, 255, 200));
        if (roll) {
            double ux = (wx - e.x) / Math.max(1e-6, dist), uy = (wy - e.y) / Math.max(1e-6, dist);
            x1 = toSX(e.x + ux * World.ROLL_DIST); y1 = toSY(e.y + uy * World.ROLL_DIST);
        }
        g.drawLine(x0, y0, x1, y1);
        g.setStroke(new BasicStroke(1));
        g.drawOval(x1 - 8, y1 - 5, 16, 10);
        g.setFont(fTiny);
        String verb = roll ? Lang.t("翻滾", "Roll") : (walkMode ? Lang.t("漫步到這裡", "Walk here") : Lang.t("跑到這裡", "Run here"));
        tag(g, verb + String.format(" %.1fs", dur) + (roll ? "" : Lang.t("（右鍵＝翻滾）", " (right-click = roll)")), x1 + 12, y1 - 8, Color.WHITE, false);
    }

    void drawBossPointer(Graphics2D g) {
        if (pvp) { drawFoePointer(g); return; }
        World.Boss b = world.b;
        if (b.hidden) return;
        int sx = toSX(b.x), sy = toSY(b.y - 16);
        if (sx > 20 && sx < W - 20 && sy > 20 && sy < PANEL_Y - 10) return;
        int cx = Math.max(30, Math.min(W - 30, sx)), cy = Math.max(110, Math.min(PANEL_Y - 30, sy));
        double ang = Math.atan2(sy - cy, sx - cx);
        g.setColor(new Color(0xC07CFF));
        int[] xs = { cx + (int) (Math.cos(ang) * 16), cx + (int) (Math.cos(ang + 2.5) * 10), cx + (int) (Math.cos(ang - 2.5) * 10) };
        int[] ys = { cy + (int) (Math.sin(ang) * 16), cy + (int) (Math.sin(ang + 2.5) * 10), cy + (int) (Math.sin(ang - 2.5) * 10) };
        g.fillPolygon(xs, ys, 3);
        taken.add(new Polygon(xs, ys, 3).getBounds());
        g.setFont(fTiny);
        tag(g, Lang.t("佛地魔 ", "Voldemort ") + (int) Math.hypot(b.x - world.p.x, b.y - world.p.y), cx - (int) (Math.cos(ang) * 26), cy - (int) (Math.sin(ang) * 26) + 4, new Color(0xD8B0FF), true);
    }

    /** An arrow beside each duellist pointing at the other one, with the distance; and an edge marker when off screen. */
    void drawFoePointer(Graphics2D g) {
        drawSpellTags(g);
        if (state == State.PLAN) { World v = viewWorld(); foeArrow(g, v.p, v.q); }
        else { foeArrow(g, world.ps[0], world.ps[1]); foeArrow(g, world.ps[1], world.ps[0]); }
        if (fullView) return;
        World.Player o = world.q;
        int sx = toSX(o.x), sy = toSY(o.y - 16);
        if (sx > 20 && sx < W - 20 && sy > 20 && sy < PANEL_Y - 10) return;
        int cx = Math.max(30, Math.min(W - 30, sx)), cy = Math.max(110, Math.min(PANEL_Y - 30, sy));
        double ang = Math.atan2(sy - cy, sx - cx);
        Color c = sideColor(o.side);
        g.setColor(c);
        int[] xs = { cx + (int) (Math.cos(ang) * 16), cx + (int) (Math.cos(ang + 2.5) * 10), cx + (int) (Math.cos(ang - 2.5) * 10) };
        int[] ys = { cy + (int) (Math.sin(ang) * 16), cy + (int) (Math.sin(ang + 2.5) * 10), cy + (int) (Math.sin(ang - 2.5) * 10) };
        g.fillPolygon(xs, ys, 3);
        taken.add(new Polygon(xs, ys, 3).getBounds());
        g.setFont(fTiny);
        tag(g, sideName(o.side) + " " + String.format(Lang.t("%d m", "%d m"), (int) Math.hypot(o.x - world.p.x, o.y - world.p.y)), cx - (int) (Math.cos(ang) * 26), cy - (int) (Math.sin(ang) * 26) + 4, c, true);
    }

    void drawViewButton(Graphics2D g) {
        Rectangle r = viewRect;
        boolean on = r.contains(mouseLX, mouseLY);
        g.setColor(on ? new Color(50, 44, 30, 235) : new Color(6, 8, 20, 200));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(fullView ? new Color(0xFFD86B) : new Color(184, 155, 74, 160));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setFont(fTiny);
        centered(g, fullView ? Lang.t("跟隨視角 U", "Follow  U") : Lang.t("全地圖 U", "Whole hall  U"), r.x + r.width / 2, r.y + 20, Color.WHITE);
    }

    void foeArrow(Graphics2D g, World.Player from, World.Player to) {
        double dx = to.x - from.x, dy = to.y - from.y, d = Math.hypot(dx, dy);
        if (d < 1) return;
        // around the duellist's feet, clear of the body; never smaller than readable on the whole-hall view
        double ang = Math.atan2(dy, dx), r = Math.max(30, 17 * vk), k = Math.max(0.7, vk / SCALE);
        int cx = toSX(from.x), cy = toSY(from.y);
        if (cx < 0 || cx > W || cy < 0 || cy > PANEL_Y) return;
        double tx = cx + Math.cos(ang) * r, ty = cy + Math.sin(ang) * r;
        Color c = sideColor(to.side);
        int[] xs = { (int) (tx + Math.cos(ang) * 14 * k), (int) (tx + Math.cos(ang + 2.4) * 9 * k), (int) (tx + Math.cos(ang - 2.4) * 9 * k) };
        int[] ys = { (int) (ty + Math.sin(ang) * 14 * k), (int) (ty + Math.sin(ang + 2.4) * 9 * k), (int) (ty + Math.sin(ang - 2.4) * 9 * k) };
        g.setColor(new Color(0, 0, 0, 150));
        g.setStroke(new BasicStroke(3));
        g.drawPolygon(xs, ys, 3);
        g.setStroke(new BasicStroke(1));
        g.setColor(c);
        g.fillPolygon(xs, ys, 3);
        taken.add(new Polygon(xs, ys, 3).getBounds());
        g.setFont(fTiny);
        int lx = (int) (tx + Math.cos(ang) * 44 * k), ly = (int) (ty + Math.sin(ang) * 22 * k) + 12;
        tag(g, String.format(Lang.t("距離 %d m", "%d m"), (int) Math.round(d)), lx, ly, c, true);
    }

    /** Two-player duel: every spell in flight is labelled with how far it travels in 0.1 s. */
    void drawSpellTags(Graphics2D g) {
        World w = state == State.PLAN ? snapAt(cursorT) : world;
        g.setFont(fTiny);
        for (World.Obj o : w.objs) {
            if (o.dead || o.hostile) continue;
            double v = Math.hypot(o.vx, o.vy);
            if (v < 1) continue;
            int sx = toSX(o.x), sy = toSY(o.y - 12);
            if (sx < 0 || sx > W || sy < 0 || sy > PANEL_Y) continue;   // a bolt off screen has no label at the edge
            tag(g, String.format(Lang.t("%.0f m/0.1秒", "%.0f m/0.1s"), v * 0.1), sx, sy, new Color(255, 236, 200, 220), true);
        }
    }

    /** A spell's flight per 0.1 s (one world pixel is a metre), as written on its card. */
    static String speedTag(ActionType a) { return String.format(Lang.t("%.0f m/0.1秒", "%.0f m/0.1s"), a.speed() * 0.1); }
    /** The short form on a card: the group title says it is metres per 0.1 s. */
    static String speedCard(ActionType a) { return String.format(Lang.t("飛 %.0f m", "fly %.0f m"), a.speed() * 0.1); }

    void drawMinimap(Graphics2D g) {
        drawViewButton(g);
        if (fullView) return;
        int mw = 160, mh = (int) Math.round(160.0 * World.MAPH / World.MAPW), mx = W - 176, my = 104;
        double s = mw / (double) World.MAPW;
        g.setColor(new Color(6, 8, 20, 190));
        g.fillRect(mx - 4, my - 4, mw + 8, mh + 8);
        g.setColor(new Color(184, 155, 74, 160));
        g.drawRect(mx - 4, my - 4, mw + 8, mh + 8);
        g.setColor(new Color(0x2A1911));
        g.fillRect(mx, my, mw, mh);
        g.setColor(new Color(0x20140F));
        g.fillRect(mx, my, mw, (int) (54 * s));
        g.setColor(new Color(0x7A6E60));
        for (double[] ob : World.OBST) { int r = Math.max(2, (int) (ob[2] * s)); g.fillOval(mx + (int) (ob[0] * s) - r, my + (int) (ob[1] * s) - r, r * 2, r * 2); }
        World w = state == State.PLAN ? snapAt(cursorT) : world;
        for (World.Obj o : w.objs) {
            if (!o.hostile) continue;
            boolean minion = o.kind == World.Kind.MINION;
            g.setColor(minion ? new Color(0xC8CCD4) : o.active ? new Color(255, 80, 80) : new Color(255, 160, 80, 120));
            g.fillRect(mx + (int) (o.x * s) - (minion ? 1 : 0), my + (int) (o.y * s) - (minion ? 1 : 0), minion ? 3 : 2, minion ? 3 : 2);
        }
        if (state == State.PLAN) {
            g.setColor(new Color(140, 230, 255, 200));
            int first = (int) Math.ceil(previewFrom() / (3 * DT) - 1e-6);
            for (int i = Math.max(0, first); i < path.size(); i++) { double[] p = path.get(i); g.fillRect(mx + (int) (p[0] * s), my + (int) (p[1] * s), 1, 1); }
        }
        g.setColor(new Color(255, 255, 255, 90));
        g.drawRect(mx + (int) (camX * s), my + (int) (camY * s), (int) (LW * s), (int) (VIEW_H * s));
        if (!world.b.hidden) { g.setColor(new Color(0xC07CFF)); g.fillOval(mx + (int) (world.b.x * s) - 3, my + (int) (world.b.y * s) - 3, 6, 6); }
        if (pvp) { g.setColor(sideColor(world.q.side)); g.fillOval(mx + (int) (world.q.x * s) - 3, my + (int) (world.q.y * s) - 3, 6, 6); }
        g.setColor(pvp ? sideColor(world.p.side) : new Color(0x7FE0FF)); g.fillOval(mx + (int) (world.p.x * s) - 3, my + (int) (world.p.y * s) - 3, 6, 6);
    }

    void drawHud(Graphics2D g) {
        if (pvp) { drawPvpHud(g); return; }
        World.Player p = world.p;
        World.Boss b = world.b;
        panel(g, 16, 12, 330, 82);
        g.setFont(fMid);
        shadowText(g, Lang.t("馬爾福 ♀  Malfoy", "Draco Malfoy ♀"), 30, 38, new Color(0xE8F0E0));
        g.setFont(fTiny);
        String mode = walkMode ? Lang.t("漫步模式 (V)", "Walk (V)") : Lang.t("跑步模式 (V)", "Run (V)");
        shadowText(g, mode, 330 - g.getFontMetrics().stringWidth(mode), 36, walkMode ? new Color(0xBFE0A0) : new Color(0x9FD0FF));
        // no health bar: one hit is fatal
        g.setFont(fSmall);
        shadowText(g, Lang.t("一擊必死 — 任何攻擊都會讓她倒下", "One hit kills — any attack brings her down"), 30, 60, new Color(0xFF9090));
        World pv = hudPreview();
        bar(g, 30, 68, 300, 12, p.mana, pv == null ? p.mana : pv.p.mana, World.MAX_MANA, new Color(0x4F8FE0), Lang.t("魔力 ", "Mana "), "");

        panel(g, W - 346, 12, 330, 82);
        g.setFont(fMid);
        String bn = b.phase == 2 ? Lang.t("佛地魔  Lord Voldemort · 狂怒", "Lord Voldemort · Enraged") : Lang.t("佛地魔  Lord Voldemort", "Lord Voldemort");
        shadowText(g, world.bossHuman ? Lang.t("P2 ", "P2 ") + bn : bn, W - 332, 38, b.phase == 2 ? new Color(0xFF8080) : new Color(0xE0D8F0));
        bar(g, W - 332, 48, 300, 14, b.hp, pv == null ? b.hp : pv.b.hp, World.BOSS_MAX_HP, new Color(0x9B4FE0), "", " / " + World.BOSS_MAX_HP);
        g.setFont(fTiny);
        int de = world.minionsAlive();
        String st = b.stun > 0 ? Lang.t("昏迷中…", "Stunned…") : b.busy > 0 ? Lang.t("施法：", "Casting: ") + b.castName : b.hidden ? Lang.t("化為黑煙", "Turned to smoke")
                  : world.bossHuman ? (world.bossTurn() ? Lang.t("P2 的回合", "P2's turn") : Lang.t("等馬爾福的計畫演完", "Waiting for Malfoy's plan"))
                  : String.format(Lang.t("下一招 %.1f 秒後", "Next spell in %.1f s"), Math.max(0, b.cooldown));
        shadowText(g, st + (de > 0 ? Lang.t("　食死人 ×", "   Death Eaters ×") + de : ""), W - 332, 84, new Color(0xC8C0D8));

        g.setFont(fBig);
        centered(g, Lang.t("分數 ", "Score ") + world.score, W / 2, 44, new Color(0xFFE38A));
        g.setFont(fSmall);
        double mult = 1 + Math.min(p.combo, 20) * 0.1;
        String line = Lang.en
            ? String.format("Combo %d   Mult ×%.1f    Time %s  (speed +%d)", p.combo, mult, clock(world.time), World.timeBonus(world.time))
            : "連擊 " + p.combo + "   倍率 ×" + String.format("%.1f", mult) + "　　時間 " + clock(world.time) + "（速攻 +" + World.timeBonus(world.time) + "）";
        centered(g, line, W / 2, 70, p.combo > 0 ? new Color(0xBFF3FF) : new Color(0x8890A0));
        if (state == State.PLAN && Math.hypot(panX, panY) > 4) {
            g.setFont(fSmall);
            centered(g, Lang.t("鏡頭已移動　Tab 回到角色　B 看佛地魔", "Camera moved   Tab: back to Malfoy   B: look at Voldemort"), W / 2, 96, new Color(0xFFD86B));
        }
    }

    /** Duel HUD: each player's close-combat health and mana, and whose turn it is. */
    /** Which round the replay is in: each round sends its two plans on the same step. */
    int replayRound() {
        int n = -1, last = -1;
        for (Replay.Input in : rec.inputs) if (in.type == Replay.PLAN && in.at != last && in.at <= world.steps) { n++; last = in.at; }
        return Math.max(0, n);
    }

    void drawPvpHud(Graphics2D g) {
        World pv = hudPreview();
        for (int side = 0; side < 2; side++) {
            World.Player pl = world.ps[side], fp = pv == null ? pl : pv.ps[side];
            int x = side == 0 ? 16 : W - 346;
            panel(g, x, 12, 330, 82);
            g.setFont(fMid);
            shadowText(g, sideName(side), x + 14, 34, sideColor(side));
            bar(g, x + 14, 41, 300, 12, pl.hp, fp.hp, World.PVP_HP, new Color(0xE05050), Lang.t("近身血量 ", "Melee HP "), " / " + World.PVP_HP);
            bar(g, x + 14, 57, 300, 12, pl.mana, fp.mana, World.MAX_MANA, new Color(0x4F8FE0), Lang.t("魔力 ", "Mana "), "");
            bar(g, x + 14, 73, 300, 12, pl.sta, fp.sta, World.MAX_STA, new Color(0xE0B040), Lang.t("體力 ", "Stamina "), "");
        }
        g.setFont(fBig);
        centered(g, String.format(Lang.t("第 %d 回合", "Round %d"), (state == State.REPLAY ? replayRound() : round) + 1), W / 2, 44, new Color(0xFFE38A));
        g.setFont(fSmall);
        String line = state == State.PLAN
            ? sideName(world.me) + String.format(Lang.t(" 規劃中　對手 %.1f / %.0f 秒%s", " planning   other side %.1f / %.0f s%s"),
                                                 sideTotal(1 - world.me), PVP_TURN, sideDone[1 - world.me] ? Lang.t("（已排滿）", " (full)") : "")
            : state == State.REPLAY ? Lang.t("回放 — U 切換全地圖／跟隨視角", "Replay — U: whole hall / follow view")
            : Lang.t("演出中 — 中咒即敗，近身血量歸零也敗", "Playing out — a spell hit or 0 melee HP loses");
        centered(g, line, W / 2, 70, state == State.PLAN ? sideColor(world.me) : new Color(0xBFF3FF));
        if (state == State.RUN) {
            g.setFont(fTiny);
            centered(g, fullView ? Lang.t("U 回到跟隨視角", "U: follow view")
                                 : Lang.t("拖曳／IJKL 自由移動鏡頭　Tab 跟回雙方　B 看對手　U 全地圖", "Drag / IJKL: free camera   Tab: follow   B: opponent   U: whole hall"),
                     W / 2, 92, new Color(0xFFD86B));
        }
    }

    static final int TL_X = 60, TL_W = 1160;

    void drawTimeline(Graphics2D g, int y, boolean planning) {
        double pxs = TL_W / planWindow;
        g.setColor(new Color(255, 255, 255, 30));
        g.fillRect(TL_X, y, TL_W, 26);
        g.setFont(fTiny);
        for (int s = 0; s <= planWindow + 1e-6; s++) {
            int x = TL_X + (int) (s * pxs);
            g.setColor(new Color(255, 255, 255, 60));
            g.fillRect(x, y - 4, 1, 34);
            shadowText(g, s + "s", x + 3, y + 38, new Color(0x8890A0));
        }
        double t = 0;
        World.Player p = world.p;
        if (p.cur != null) {
            double rem = (p.cur.steps - p.step) * DT;
            block(g, TL_X, y, rem * pxs, p.cur, 90, "");
            t += rem;
        }
        for (World.PlanItem it : p.queue) { block(g, TL_X + t * pxs, y, it.dur() * pxs, it, 150, label(it)); t += it.dur(); }
        for (World.PlanItem it : draft) { block(g, TL_X + t * pxs, y, it.dur() * pxs, it, 255, label(it)); t += it.dur(); }
        if (planning && previewFrom() > 0) {
            int fx = TL_X + (int) (previewFrom() * pxs);
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(TL_X, y, fx - TL_X, 26);
            g.setColor(new Color(0x9FE8FF));
            g.fillRect(fx - 1, y - 6, 2, 38);
        }
        if (planning && pvp && allowSteps >= 0) {
            int cx = TL_X + (int) (Math.min(planWindow, (turnStartSteps + allowSteps) * DT) * pxs);
            g.setColor(new Color(0xFFD86B));
            g.fillRect(cx - 1, y - 6, 3, 38);
            g.setFont(fTiny);
            // inside the bar, in the empty stretch after the line
            String lim = Lang.t("這一動排到這裡", "this turn ends here");
            int lw = g.getFontMetrics().stringWidth(lim);
            shadowText(g, lim, cx + 6 + lw < TL_X + TL_W ? cx + 6 : cx - 6 - lw, y + 17, new Color(0xFFD86B));
        }
        if (!planning) {
            // while the plan plays out, the predicted hits slide left with it
            double done = (world.steps - runStart) * DT;
            for (Mark m : hits) hitCross(g, m.t - done, y, pxs);
            return;
        }

        // what the unplanned rest of this bullet time will be
        double gap = castLen - t;
        String rest = gap > 0.05
                ? (Lang.en ? String.format("%.1f s of his attack unplanned → %s", gap, gap > 0.5 ? "-" + Math.round(gap * 20) + " points" : "no penalty under 0.5 s")
                           : String.format("他的攻擊還有 %.1f 秒沒排 → %s", gap, gap > 0.5 ? "執行時扣 " + Math.round(gap * 20) + " 分" : "0.5 秒內不扣分"))
                : planWindow - t > 0.05 && !pvp ? String.format(Lang.t("還能排 %.1f 秒；計畫一演完就立刻進下一次子彈時間", "%.1f s still free; the next bullet time starts when the plan ends"), planWindow - t) : "";
        g.setFont(fTiny);
        int rx = TL_X + (int) (t * pxs) + 8;
        if (!rest.isEmpty() && rx + g.getFontMetrics().stringWidth(rest) < TL_X + TL_W - 16) shadowText(g, rest, rx, y + 17, gap > 0.05 ? new Color(0xFFB060) : new Color(0xBFF3FF));

        if (!pvp) {
            // where Voldemort's attack ends (a two-player round has no such line)
            g.setFont(fTiny);
            int ce = TL_X + (int) (castLen * pxs);
            g.setColor(new Color(0xC07CFF));
            g.fillRect(ce - 1, y - 6, 2, 38);
            String nextL = Lang.t("技能結束・下一波立刻開始", "End · next wave starts");
            FontMetrics tfm = g.getFontMetrics();
            // under the bar; one that would run off the right edge goes to the left of the line instead
            shadowText(g, nextL, ce + 4 + tfm.stringWidth(nextL) > W - 4 ? ce - 4 - tfm.stringWidth(nextL) : ce + 4, y + 38, new Color(0xA890C8));
        }
        for (Mark m : hits) hitCross(g, m.t, y, pxs);
        int cx = TL_X + (int) (cursorT * pxs);
        g.setColor(Color.WHITE);
        g.fillRect(cx - 1, y - 6, 3, 38);
        g.fillPolygon(new int[] { cx - 6, cx + 6, cx }, new int[] { y + 34, y + 34, y + 28 }, 3);
    }

    /** A red cross on the timeline where a hit is predicted; one beyond the end shows as an arrow at the edge. */
    void hitCross(Graphics2D g, double t, int y, double pxs) {
        if (t < -0.05) return;
        g.setColor(new Color(0xFF4040));
        if (t > planWindow + 1e-6) {
            int x = TL_X + TL_W - 2;
            g.fillPolygon(new int[] { x - 9, x + 3, x - 9 }, new int[] { y + 4, y + 13, y + 22 }, 3);
            return;
        }
        int x = TL_X + (int) (Math.max(0, t) * pxs);
        g.setStroke(new BasicStroke(3));
        g.drawLine(x - 7, y + 3, x + 7, y + 23); g.drawLine(x - 7, y + 23, x + 7, y + 3);
        g.setStroke(new BasicStroke(1));
    }

    static String label(World.PlanItem it) {
        if (it.type == ActionType.RUN) return (it.alt ? Lang.t("漫", "Walk") : Lang.t("跑", "Run")) + arrow(it.dx, it.dy);
        if (it.type == ActionType.ROLL) return it.type.shortName() + arrow(it.dx, it.dy);
        String aim = it.aimed() ? arrow(it.dx, it.dy) : "";
        if (!it.type.charged() && it.type.isSpell()) return it.type.shortName() + aim;
        if (it.type.isSpell()) return it.type.shortName() + (it.alt ? Lang.t("·放", " cast") + aim : Lang.t("·讀", " charge"));
        return it.type.shortName();
    }

    static String arrow(double dx, double dy) {
        String[] a = { "→", "↘", "↓", "↙", "←", "↖", "↑", "↗" };
        int i = (int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
        return a[Math.floorMod(i, 8)];
    }

    void block(Graphics2D g, double x, int y, double w, World.PlanItem it, int alpha, String label) {
        if (w < 1) return;
        ActionType a = it.type;
        Color c = it.isRelease() ? new Color(0xFF8A6A) : a.color();
        g.setColor(Sprites.alpha(c, alpha));
        g.fillRect((int) x + 1, y + 2, (int) w - 2, 22);
        g.setColor(Sprites.alpha(Color.WHITE, alpha / 2));
        g.drawRect((int) x + 1, y + 2, (int) w - 3, 21);
        int[] inv = a.iframes(), air = a.airtime();
        int[] band = inv != null ? inv : air;
        if (band != null) {
            // invincibility (white) or airtime (cyan)
            double f0 = band[0] / (double) it.steps, f1 = (band[1] + 1) / (double) it.steps;
            g.setColor(Sprites.alpha(inv != null ? Color.WHITE : new Color(0x80F0FF), alpha * 3 / 4));
            g.fillRect((int) (x + w * f0), y + 19, (int) (w * Math.min(1, f1) - w * f0), 4);
        }
        g.setFont(fTiny);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(label) < w - 4) shadowText(g, label, (int) (x + (w - fm.stringWidth(label)) / 2), y + 17, Sprites.alpha(Color.WHITE, Math.min(255, alpha + 40)));
    }

    void drawCards(Graphics2D g, World.Player ref, int room) {
        g.setFont(fTiny);
        for (int gi = 0; gi < GROUP_START.length; gi++) {
            Rectangle r = cardRects[GROUP_START[gi]];
            String gn = pvp && gi == 2 ? Lang.t("遠程（飛＝每 0.1 秒飛行 m）", "Ranged (fly = m per 0.1 s)") : groupName(gi);
            shadowText(g, gn, r.x, r.y - 4, new Color(0x9098B0));
        }
        for (int i = 0; i < cardRects.length; i++) {
            Card cd = CARDS[i];
            Rectangle r = cardRects[i];
            ActionType a = cd.type;
            boolean toggle = a == null;
            boolean stored = a != null && a.charged() && ref.stored[a.spellIndex()];
            double cdLeft = a == null ? 0 : ref.cd[a.ordinal()];
            boolean poor = a != null && (a.mana(pvp) > ref.mana || a.stamina(pvp) > ref.sta);
            double cool = a == null ? 0 : a.cooldown(pvp);
            int need = a == null ? 0 : a == ActionType.RUN ? 3 : stored ? ActionType.RELEASE_STEPS : a.steps;
            boolean full = state == State.PLAN && need > room;
            boolean dim = poor || full || cdLeft > 1e-9;
            Color c = toggle ? new Color(walkMode ? 0xBFE0A0 : 0x9FD0FF) : a.color();
            g.setColor(i == hover ? new Color(40, 44, 70) : stored ? new Color(52, 30, 30) : new Color(22, 24, 42));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 9, 9);
            g.setColor(Sprites.alpha(c, dim ? 80 : 220));
            g.fillRect(r.x, r.y, r.width, 4);
            g.drawRoundRect(r.x, r.y, r.width, r.height, 9, 9);
            if (cdLeft > 1e-9 && cool > 0) {
                int hh = (int) (r.height * Math.min(1, cdLeft / cool));
                g.setColor(new Color(0, 0, 0, 120));
                g.fillRect(r.x + 1, r.y + r.height - hh, r.width - 1, hh);
            }
            g.setFont(fTiny);
            shadowText(g, cd.keyName(), r.x + 5, r.y + 17, new Color(0xFFD86B));
            int mana = a == null ? 0 : a.mana(pvp), sta = a == null ? 0 : a.stamina(pvp);
            String corner = stored ? "★" : a == null ? "" : mana > 0 ? mana + Lang.t("魔", "mp") : sta > 0 ? sta + Lang.t("體", "st") : cool > 0 ? "CD" + trim(cool) : "";
            Color cc = stored ? Sprites.spellColor(a) : mana > 0 ? new Color(0x9FC4FF) : sta > 0 ? new Color(0xF0C860) : new Color(0x9098B0);
            shadowText(g, corner, r.x + r.width - 4 - g.getFontMetrics().stringWidth(corner), r.y + 17, cc);
            g.setFont(fCard);
            String name = toggle ? (walkMode ? Lang.t("漫步", "Walk") : Lang.t("跑步", "Run")) : cd.name();
            fit(g, name, r.width - 6);
            boolean tag = pvp && a != null && a.isSpell();
            centered(g, name, r.x + r.width / 2, r.y + (tag ? 37 : 42), dim ? new Color(0x707080) : Color.WHITE);
            if (tag) {
                // how far the bolt flies in 0.1 s
                g.setFont(fTiny);
                String sp = speedCard(a);
                centered(g, sp, r.x + r.width / 2, r.y + 52, new Color(0xFFD8A0));
            }
            g.setFont(fTiny);
            String meta;
            if (toggle) meta = Lang.t("切換", "Toggle");
            else if (cdLeft > 1e-9) meta = String.format(Lang.t("冷卻%.1f", "CD %.1f"), cdLeft);
            else if (a.isSpell() && !a.charged()) meta = Lang.t("免吟", "Fire ") + a.dur + "s";
            else if (a.isSpell()) meta = stored ? Lang.t("施放0.2s", "Cast 0.2s") : Lang.t("讀條", "Chg ") + a.dur + "s";
            else meta = a == ActionType.RUN ? (walkMode ? Lang.t("漫步", "Walk") : Lang.t("跑步", "Run")) + " 0.4s" : a.dur + "s";
            fit(g, meta, r.width - 4);
            centered(g, meta, r.x + r.width / 2, r.y + (pvp && a != null && a.isSpell() ? 67 : 62), poor ? new Color(0xFF8080) : stored ? new Color(0xFFC0A0) : new Color(0xA0A8C0));
        }
        if (hover >= 0 && infoCard() < 0) {
            Card cd = CARDS[hover];
            String s = (cd.type == null ? Lang.t("V：切換跑步（75）／漫步（40）", "V: toggle run (75) / walk (40)")
                        : cd.type.latin + (pvp && cd.type.isSpell() ? "（" + speedTag(cd.type) + "）" : "") + " — " + cd.type.info(pvp))
                       + Lang.t("　（長按看詳細）", "   (hold for details)");
            g.setFont(fSmall);
            List<String> ls = new ArrayList<>(wrap(g, s, W - 80));
            // the fastest hit gets a line of its own, in the colour of the "now" line of the details
            String hit = fastestHitText(cd.type, "");
            int hitLine = hit.isEmpty() ? -1 : ls.size();
            if (!hit.isEmpty()) ls.add(hit);
            int tw = 0;
            for (String l : ls) tw = Math.max(tw, g.getFontMetrics().stringWidth(l));
            Rectangle r = cardRects[hover];
            int x = Math.max(10, Math.min(W - tw - 30, r.x + r.width / 2 - tw / 2 - 10));
            int top = 520 - 12 - ls.size() * 22;
            g.setColor(new Color(0, 0, 0, 225));
            g.fillRoundRect(x, top, tw + 20, 12 + ls.size() * 22, 10, 10);
            tipBoxNow = new Rectangle(x, top, tw + 20, 12 + ls.size() * 22);
            for (int i = 0; i < ls.size(); i++) shadowText(g, ls.get(i), x + 10, top + 23 + i * 22, i == hitLine ? new Color(0xFFD86B) : Color.WHITE);
        }
    }

    /** "fastest it reaches the foe" for a spell, from where the plan leaves her, after a lead-in; "" otherwise. */
    String fastestHitText(ActionType a, String lead) {
        if (a == null) return "";
        double hit = (state == State.PLAN && planEndWorld != null ? planEndWorld : world).fastestHit(a);
        return hit < 0 ? "" : lead + String.format(Lang.t("最快 %.2f 秒打到對手", "reaches the foe in %.2f s at the fastest"), hit);
    }

    static String trim(double v) { return new java.math.BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString(); }

    void drawPlanPanel(Graphics2D g) {
        g.setColor(new Color(8, 10, 24, 228));
        g.fillRect(0, PANEL_Y, W, H - PANEL_Y);
        g.setColor(new Color(0xB89B4A));
        g.fillRect(0, PANEL_Y, W, 2);

        g.setFont(fMid);
        double total = planTotal();
        String right;
        Color rc;
        if (previewDeath) { right = Lang.t("！ 這樣安排會讓你倒下", "! This plan gets you killed"); rc = new Color(0xFF5050); }
        else if (!hits.isEmpty()) {
            Mark h = hits.get(0);
            right = Lang.en ? String.format("! Predicted hit by %s at %.1fs", h.text, h.t) : String.format("！ 預測 %.1fs 被【%s】擊中", h.t, h.text);
            rc = new Color(0xFF7070);
        }
        else if (pvp) {
            right = String.format(Lang.t("已規劃 %.1f / %.1f 秒", "Planned %.1f / %.1f s"), total, planWindow)
                  + (allowSteps >= 0 ? String.format(Lang.t("　這一動還要排 %.2f 秒", "   %.2f s still to fill this turn"), Math.max(0, roomSteps()) * DT) : Lang.t("　先手不限長度", "   first side: any length"));
            rc = new Color(0xCFF5D8);
        }
        else { right = String.format(Lang.t("已規劃 %.1f / %.1f 秒（他這波攻擊的長度）  預估 %+d", "Planned %.1f / %.1f s (length of his attack)  est. %+d"), total, planWindow, previewScore); rc = new Color(0xCFF5D8); }
        g.setFont(fSmall);
        FontMetrics fm = g.getFontMetrics();
        shadowText(g, right, W - 20 - fm.stringWidth(right), 554, rc);
        g.setFont(fMid);
        String head = ellipsize(g, Lang.t("【子彈時間】 ", "[Bullet time] ") + planReason, W - 60 - fm.stringWidth(right));
        shadowText(g, head, 20, 554, new Color(0xBFE0FF));
        drawTimeline(g, 566, true);
        drawCards(g, planEndWorld != null ? planEndWorld.p : world.p, roomSteps());
        if (pvp) { drawHandButton(g); drawRangeButton(g); }
        g.setFont(fTiny);
        shadowText(g, Lang.t("左鍵走・右鍵翻滾　繳械/昏擊/召蛇朝滑鼠　鏡頭：中鍵或 Ctrl+左鍵拖曳・IJKL・點小地圖・U 全地圖・B 佛地魔・Tab 回角色　長按看說明　Enter 執行　Backspace 復原　Delete 清空",
                             "Left-click move · right-click roll · spells aim at the mouse   Camera: middle or Ctrl+drag · IJKL · minimap · U whole hall · B Voldemort · Tab back   Hold: details   Enter go   Backspace undo   Delete clear"),
                   18, 714, new Color(0x8890A0));
    }

    /** Ends this player's turn whenever they like: hands over, or plays the round once both plans are full. */
    void drawHandButton(Graphics2D g) {
        Rectangle r = handRect;
        boolean on = r.contains(mouseLX, mouseLY);
        boolean plays = sideDone[1 - world.me];
        Color c = sideColor(plays ? world.me : 1 - world.me);
        g.setColor(on ? new Color(50, 44, 30, 240) : new Color(14, 16, 32, 230));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setColor(c);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setFont(fSmall);
        boolean empty = toSteps(planTotal()) == turnStartSteps;
        int room = Math.max(0, roomSteps());
        String t = allowSteps >= 0 && room > 0 ? String.format(plays ? Lang.t("剩 %.2f 秒原地等・開始演出（Enter）", "Wait %.2f s · play the round (Enter)")
                                                                      : Lang.t("剩 %.2f 秒原地等・換對手（Enter）", "Wait the last %.2f s · hand over (Enter)"), room * DT)
                 : plays ? Lang.t("排完・開始演出（Enter）", "Done · play the round (Enter)")
                 : allowSteps >= 0 ? Lang.t("排滿了・換對手（Enter）", "Full · hand over (Enter)")
                 : empty ? Lang.t("原地等到結束・換對手（Enter）", "Wait it out · hand over (Enter)")
                 : String.format(Lang.t("在 %.1f 秒停手・換對手（Enter）", "Stop at %.1f s · hand over (Enter)"), planTotal());
        fit(g, t, r.width - 12);
        centered(g, t, r.x + r.width / 2, r.y + 25, Color.WHITE);
    }

    void drawRangeButton(Graphics2D g) {
        Rectangle r = rangeRect;
        boolean on = r.contains(mouseLX, mouseLY);
        g.setColor(on ? new Color(40, 44, 60, 240) : new Color(14, 16, 32, 220));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(previewTurn ? new Color(0x9FE8FF) : new Color(110, 120, 150));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setFont(fTiny);
        String t = previewTurn ? Lang.t("預演：上一動起　Y 切換", "Preview: from last turn   Y") : Lang.t("預演：全部安排　Y 切換", "Preview: whole plan   Y");
        fit(g, t, r.width - 12);
        centered(g, t, r.x + r.width / 2, r.y + 20, Color.WHITE);
    }

    static final int RB_X = 160, RB_W = 960, RB_Y = 600;

    void replayClick(double x, double y) {
        if (y < RB_Y - 14 || y > RB_Y + 24 || x < RB_X - 6 || x > RB_X + RB_W + 6) return;
        replaySeek((int) Math.round((x - RB_X) / RB_W * rec.endStep));
    }

    void drawReplayPanel(Graphics2D g) {
        g.setColor(new Color(8, 10, 24, 215));
        g.fillRect(0, PANEL_Y, W, H - PANEL_Y);
        g.setColor(new Color(0xFFD86B));
        g.fillRect(0, PANEL_Y, W, 2);
        g.setFont(fMid);
        boolean ended = world.steps >= rec.endStep;
        shadowText(g, ended ? Lang.t("【回放結束】", "[Replay over]") : replayPaused ? Lang.t("【回放暫停】", "[Replay paused]") : Lang.t("【回放中】", "[Replay]"), 20, 562, new Color(0xFFD86B));
        String sp = String.format(Lang.t("速度 ×%s", "Speed ×%s"), trim(SPEEDS[speedIdx]));
        g.setFont(fSmall);
        shadowText(g, sp, W - 20 - g.getFontMetrics().stringWidth(sp), 562, Color.WHITE);
        // progress bar with a tick at every one of Voldemort's waves
        double f = rec.endStep == 0 ? 0 : Math.min(1, world.steps / (double) rec.endStep);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRect(RB_X, RB_Y, RB_W, 10);
        g.setColor(new Color(0xFFD86B));
        g.fillRect(RB_X, RB_Y, (int) (RB_W * f), 10);
        g.setColor(new Color(0xC07CFF));
        for (Replay.Input in : rec.inputs) {
            if (in.type != Replay.PLAN) continue;
            int x = RB_X + (int) (RB_W * in.at / (double) Math.max(1, rec.endStep));
            g.fillRect(x, RB_Y - 4, 2, 18);
        }
        int cx = RB_X + (int) (RB_W * f);
        g.setColor(Color.WHITE);
        g.fillOval(cx - 7, RB_Y - 2, 14, 14);
        g.setFont(fSmall);
        shadowText(g, clock(world.steps * DT), RB_X - 70, RB_Y + 11, Color.WHITE);
        shadowText(g, clock(rec.endStep * DT), RB_X + RB_W + 14, RB_Y + 11, new Color(0xA0A8C0));
        g.setFont(fTiny);
        shadowText(g, pvp ? Lang.t("紫色刻度＝每一回合開始（雙方的計畫在這裡送出）", "Purple ticks = the start of each round (where both plans were sent)")
                          : Lang.t("紫色刻度＝佛地魔每一波（你在那裡用了子彈時間）", "Purple ticks = each of Voldemort's waves (where you used bullet time)"), RB_X, RB_Y + 34, new Color(0xA890C8));
        shadowText(g, Lang.t("空白 暫停/播放　←/→ 倒退/快轉 5 秒　↑/↓ 速度　點進度條跳轉　R 從頭播放　Esc 返回結算", "Space pause/play   ←/→ back/forward 5 s   ↑/↓ speed   click the bar to jump   R restart   Esc back to results")
                   + Lang.t("　U 全地圖／跟隨", "   U whole hall / follow") + (pvp ? Lang.t("（拖曳、IJKL 移動鏡頭）", " (drag, IJKL)") : ""), 20, 712, new Color(0x8890A0));
    }

    static String clock(double sec) { int s = (int) sec; return String.format("%d:%02d", s / 60, s % 60); }

    void drawRunPanel(Graphics2D g) {
        g.setColor(new Color(8, 10, 24, 150));
        g.fillRect(0, 556, W, 66);
        drawTimeline(g, 568, false);
        if (world.bossHuman && state == State.RUN) drawP2Panel(g);
    }

    /** Player 2's Voldemort: his turn, and every spell with its key, the ranges it needs and whether it can go now. */
    void drawP2Panel(Graphics2D g) {
        World.Boss b = world.b;
        int y0 = 626;
        g.setColor(new Color(18, 8, 30, 215));
        g.fillRect(0, y0, W, H - y0);
        g.setColor(new Color(0x7A4FB0));
        g.drawLine(0, y0, W, y0);
        boolean turn = world.bossTurn();
        g.setFont(fSmall);
        String head;
        Color hc;
        if (b.busy > 0) { head = Lang.t("佛地魔（P2）施法中：", "Voldemort (P2) casting: ") + b.castName; hc = new Color(0xC8C0D8); }
        else if (b.stun > 0) { head = Lang.t("佛地魔（P2）昏迷中…", "Voldemort (P2) stunned…"); hc = new Color(0xC8C0D8); }
        else if (turn) { head = String.format(Lang.t("佛地魔（P2）的回合！剩 %.1f 秒　方向鍵飄移　Enter 幻影移形", "Voldemort (P2): your turn! %.1f s   Arrows: float   Enter: apparate"), Math.max(0, P2_TURN - p2Wait)); hc = new Color(0xFFD86B); }
        else { head = Lang.t("佛地魔（P2）：等馬爾福的計畫演完才能行動 — 可以先選好下一招", "Voldemort (P2): acts once Malfoy's plan has played out — pick the next spell now"); hc = new Color(0xC9A0FF); }
        shadowText(g, head, 16, y0 + 20, hc);
        String right = (world.bossOrder != null ? Lang.t("下一招：", "Next: ") + world.bossOrder.label() + "　" : "")
                     + (b.blinkCd > 0 ? String.format(Lang.t("幻影移形 %.1f 秒", "Apparate %.1f s"), b.blinkCd) : Lang.t("幻影移形 可用", "Apparate ready"))
                     + "　" + Lang.t("距離：", "Range: ") + World.bandName(World.rangeBand(Math.hypot(world.p.x - b.x, world.p.y - b.y)));
        g.setFont(fTiny);
        shadowText(g, right, W - 16 - g.getFontMetrics().stringWidth(right), y0 + 20, new Color(0xE0D8F0));
        if (turn) {
            g.setColor(new Color(0xFFD86B));
            g.fillRect(0, y0 + 1, (int) Math.round(W * Math.max(0, 1 - p2Wait / P2_TURN)), 3);
        }
        World.Attack[] all = World.Attack.values();
        int cw = 108, gap = 6, x = (W - (all.length * cw + (all.length - 1) * gap)) / 2, y = y0 + 30;
        String[] bands = Lang.en ? new String[] { "C", "M", "F" } : new String[] { "近", "中", "遠" };
        for (int i = 0; i < all.length; i++) {
            World.Attack a = all[i];
            boolean ok = world.canCast(a), chosen = world.bossOrder == a;
            g.setColor(chosen ? new Color(70, 40, 100, 240) : new Color(30, 18, 46, ok ? 235 : 150));
            g.fillRoundRect(x, y, cw, 56, 8, 8);
            g.setColor(chosen ? new Color(0xFFD86B) : ok ? new Color(0x9B6FE0) : new Color(0x4A3A60));
            g.drawRoundRect(x, y, cw, 56, 8, 8);
            g.setFont(fTiny);
            shadowText(g, P2_KEYS[i], x + 6, y + 15, new Color(0xFFD86B));
            boolean[] bd = World.bandsOf(a);
            int bx = x + cw - 6;
            for (int k = 2; k >= 0; k--) {
                int w = g.getFontMetrics().stringWidth(bands[k]);
                bx -= w + 2;
                g.setColor(bd[k] ? new Color(0x7FE3FF) : new Color(0x404858));
                g.drawString(bands[k], bx, y + 15);
            }
            g.setFont(fSmall);
            String nm = a.label();
            while (g.getFontMetrics().stringWidth(nm) > cw - 8 && nm.length() > 2) nm = nm.substring(0, nm.length() - 1);
            centered(g, nm, x + cw / 2, y + 36, ok ? Color.WHITE : new Color(0x707080));
            g.setFont(fTiny);
            String why = world.castBlock(a);
            centered(g, why == null ? Lang.t("可施放", "ready") : why, x + cw / 2, y + 51, why == null ? new Color(0x9FE8A0) : new Color(0x8A7AA0));
            x += cw + gap;
        }
    }

    void drawTitle(Graphics2D g) {
        int cx = World.MAPW / 2 - LW / 2;
        g.drawImage(map, 0, 0, LW * SCALE, LH * SCALE, cx, 0, cx + LW, LH, null);
        Graphics2D a = (Graphics2D) g.create();
        a.scale(SCALE, SCALE);
        a.translate(-cx, 0);
        Sprites.mapAnim(a, animT);
        a.dispose();
        g.setColor(new Color(5, 6, 18, 170));
        g.fillRect(0, 0, W, H);

        World.Player p = new World.Player();
        p.x = 20; p.y = 43;
        BufferedImage spr = figure(44, 46, sg -> Sprites.draco(sg, p, animT, null));
        World.Boss b = new World.Boss();
        b.x = 26; b.y = 57;
        BufferedImage spr2 = figure(46, 60, sg -> Sprites.voldemort(sg, b, animT, false, null, -1));
        g.drawImage(spr, 90, 160, 44 * 7, 46 * 7, null);
        g.drawImage(spr2, 920, 110, 46 * 6, 60 * 6, null);

        g.setFont(fTitle);
        centered(g, Lang.t("時光器對決", "Time-Turner Duel"), W / 2, 150, new Color(0xFFD86B));
        g.setFont(fMid);
        centered(g, Lang.t("DUEL OF THE TIME-TURNER  ·  馬爾福 vs 佛地魔  ·  魔法部中庭", "Malfoy vs Voldemort  ·  Ministry of Magic Atrium"), W / 2, 190, new Color(0xCFD8E8));

        g.setFont(fSmall);
        String[] lines = Lang.en ? new String[] {
            "Voldemort attacks wave after wave, without a pause.",
            "Before each one the Time-Turner freezes time, and you direct.",
            "Ghosts preview your plan; spells charge, store, then cast.",
            "Smooth, full plans score higher. Win fast for a bonus.",
        } : new String[] {
            "佛地魔一波接一波進攻、毫無間隔。每波出招前時光器凍結時間，換你當導演。",
            "計畫一演完就立刻進入下一次子彈時間，所有動作都在子彈時間安排。虛影會預演一切。",
            "遠程咒語先讀條儲存再施放，可以抵銷他的彈幕；近戰與閃避有冷卻。",
            "他會依你的距離換招、召喚食死人。動作越連貫、規劃越完整，分數越高。",
        };
        for (int i = 0; i < lines.length; i++) centered(g, lines[i], W / 2, 300 + i * 30, new Color(0xE0E6F0));
        String[][] modes = soloOnly ? new String[][] {
                                   { Lang.t("單人決鬥", "Solo duel"), Lang.t("對戰佛地魔　Enter／1", "vs Voldemort   Enter / 1") },
                                   { Lang.t("新手教程", "Tutorial"), Lang.t("第一次玩先點這裡　按 2", "New? Start here   2") } }
                           : new String[][] { { Lang.t("單人決鬥", "Solo duel"), Lang.t("對戰佛地魔　Enter／1", "vs Voldemort   Enter / 1") },
                             { Lang.t("雙人對戰 PvP", "Two players (PvP)"), Lang.t("同一台電腦輪流　按 2", "Take turns on one PC   2") },
                             { Lang.t("雙人：P2 當佛地魔", "2P: P2 is Voldemort"), Lang.t("只有馬爾福有子彈時間　按 3", "Only Malfoy has bullet time   3") } };
        for (int i = 0; i < modes.length; i++) {
            Rectangle r = soloOnly ? soloRects[i] : modeRects[i];
            boolean on = r.contains(mouseLX, mouseLY);
            Color c = i == 0 ? new Color(0xFFD86B) : soloOnly ? new Color(0x9FE8A0) : i == 1 ? new Color(0xFF8C8C) : new Color(0xC9A0FF);
            g.setColor(on ? new Color(58, 50, 36, 235) : new Color(20, 22, 40, 215));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setColor(c);
            g.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
            g.setFont(fMid);
            centered(g, modes[i][0], r.x + r.width / 2, r.y + 25, Color.WHITE);
            g.setFont(fTiny);
            centered(g, modes[i][1], r.x + r.width / 2, r.y + 45, c);
        }
        g.setFont(fSmall);
        centered(g, Lang.t("按 H 查看所有技能與佛地魔招式　·　M 靜音　·　O 浮出字開關　·　F11 全螢幕", "H: every skill and Voldemort's spells  ·  M: mute  ·  O: floating text  ·  F11: full screen"), W / 2, 505, new Color(0x9098B0));
        drawLanguageMenu(g);
    }

    /** The two language buttons on the title screen. */
    final Rectangle[] langRects = { new Rectangle(W / 2 - 150, 570, 140, 40), new Rectangle(W / 2 + 10, 570, 140, 40) };

    void drawLanguageMenu(Graphics2D g) {
        g.setFont(fSmall);
        centered(g, Lang.t("語言 Language　（L 切換）", "Language 語言  (L to switch)"), W / 2, 558, new Color(0x9098B0));
        String[] names = { "繁體中文", "English" };
        for (int i = 0; i < 2; i++) {
            Rectangle r = langRects[i];
            boolean on = Lang.en == (i == 1);
            g.setColor(on ? new Color(58, 50, 26, 230) : new Color(22, 24, 42, 200));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(on ? new Color(0xFFD86B) : new Color(0x606880));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setFont(fMid);
            centered(g, names[i], r.x + r.width / 2, r.y + 27, on ? Color.WHITE : new Color(0x9098B0));
        }
    }

    void setLanguage(boolean english) {
        if (english == Lang.en) return;
        Lang.set(english);
        Audio.play(Audio.Sfx.CLICK);
        java.awt.Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof java.awt.Frame) ((java.awt.Frame) w).setTitle(Lang.windowTitle());
    }

    /** A figure drawn alone: {@code draw} works in world units on a w x h canvas. */
    static BufferedImage figure(int w, int h, java.util.function.Consumer<Graphics2D> draw) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D world = img.createGraphics();
        draw.accept(world);
        world.dispose();
        return img;
    }

    void drawEnd(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, W, H);
        if (pvp) {
            g.setFont(fTitle);
            String head = world.winner < 0 ? Lang.t("同歸於盡！", "Both fall — a draw!") : sideName(world.winner) + Lang.t(" 獲勝！", " wins!");
            centered(g, head, W / 2, 250, world.winner < 0 ? Color.WHITE : sideColor(world.winner));
            g.setFont(fSmall);
            for (int side = 0; side < 2; side++) {
                World.Player pl = world.ps[side];
                String how = pl.ko ? Lang.t("中咒倒下", "felled by a spell") : pl.hp <= 0 ? Lang.t("近身血量歸零", "out of melee HP") : String.format(Lang.t("剩 %d 血", "%d HP left"), (int) pl.hp);
                centered(g, sideName(side) + "：" + how, W / 2, 320 + side * 30, sideColor(side));
            }
            centered(g, String.format(Lang.t("共 %d 回合　·　抵銷 %d 次", "%d rounds   ·   %d clashes"), round + 1, world.cancels), W / 2, 390, new Color(0xCFD8E8));
            g.setFont(fMid);
            centered(g, Lang.t("P 觀看整局回放（全地圖）   ·   R 再戰一次   ·   Esc 回標題", "P: replay (whole hall)   ·   R: fight again   ·   Esc: title"), W / 2, 500, new Color(0xBFE0FF));
            return;
        }
        g.setFont(fTitle);
        if (bossMode) centered(g, world.won ? Lang.t("馬爾福（P1）獲勝！", "Malfoy (P1) wins!") : Lang.t("佛地魔（P2）獲勝！", "Voldemort (P2) wins!"), W / 2, 230, world.won ? new Color(0xFFD86B) : new Color(0xC9A0FF));
        else centered(g, world.won ? Lang.t("佛地魔被擊退了！", "Voldemort is defeated!") : Lang.t("你倒下了……", "You have fallen…"), W / 2, 230, world.won ? new Color(0xFFD86B) : new Color(0xFF6060));
        g.setFont(fBig);
        centered(g, Lang.t("最終分數 ", "Final score ") + world.score, W / 2, 300, Color.WHITE);
        g.setFont(fSmall);
        String[] st = Lang.en ? new String[] {
            "Best combo  " + world.maxCombo,
            String.format("Longest plan  %.1f s", world.longestPlan),
            "Interrupts  " + world.interrupts + "    Barrage cancelled  " + world.cancels,
            "Hits taken  " + world.hitsTaken,
            String.format("Duel time  %.1f s", world.time) + (world.won ? "    Speed bonus +" + World.timeBonus(world.time) : "    (no speed bonus on a loss)"),
        } : new String[] {
            "最高連擊  " + world.maxCombo,
            String.format("最長一次規劃  %.1f 秒", world.longestPlan),
            "打斷施法  " + world.interrupts + " 次　抵銷彈幕  " + world.cancels + " 發",
            "被擊中  " + world.hitsTaken + " 次",
            String.format("決鬥時間  %.1f 秒", world.time) + (world.won ? "　速攻獎勵 +" + World.timeBonus(world.time) : "　（輸了沒有速攻獎勵）"),
        };
        for (int i = 0; i < st.length; i++) centered(g, st[i], W / 2, 350 + i * 28, new Color(0xCFD8E8));
        g.setFont(fMid);
        centered(g, Lang.t("按 P 觀看整局回放   ·   R 再戰一次   ·   Esc 回標題", "P: watch the replay   ·   R: fight again   ·   Esc: title"), W / 2, 520, new Color(0xBFE0FF));
    }

    void drawHelp(Graphics2D g) {
        g.setColor(new Color(4, 6, 16, 242));
        g.fillRect(30, 20, W - 60, H - 40);
        g.setColor(new Color(0xB89B4A));
        g.drawRect(30, 20, W - 60, H - 40);
        g.setFont(fBig);
        centered(g, Lang.t("技能圖鑑", "Spellbook"), W / 2, 56, new Color(0xFFD86B));
        g.setFont(fMid);
        shadowText(g, Lang.t("馬爾福", "Malfoy"), 50, 88, new Color(0xBFE0FF));
        shadowText(g, Lang.t("佛地魔（依距離出招・第二階段強化）", "Voldemort (spells by range · stronger in phase 2)"), 660, 88, new Color(0xD8B0FF));
        g.setFont(fTiny);
        String[] keys = { Lang.t("WASD/左鍵", "WASD/click"), Lang.t("空白/N", "Space/N"), "Q", "E", "Z", "C", Lang.t("1 免吟", "1 instant"), "2", "3", "4", "F", "G", "R", "T", "5" };
        ActionType[] acts = { ActionType.RUN, ActionType.WAIT, ActionType.ROLL, ActionType.DASH, ActionType.BACKFLIP, ActionType.APPARATE,
                              ActionType.DENSAUGEO, ActionType.EXPELLIARMUS, ActionType.STUPEFY, ActionType.SERPENSORTIA,
                              ActionType.KICK, ActionType.FLURRY, ActionType.SPIN, ActionType.THROW, ActionType.PROTEGO };
        int y = 110;
        for (int i = 0; i < acts.length; i++) {
            ActionType a = acts[i];
            double cool = a.cooldown(pvp);
            int mana = a.mana(pvp), sta = a.stamina(pvp);
            String name = "[" + keys[i] + "] " + a.label();
            String cost = ((cool > 0 ? "  CD " + trim(cool) + "s" : "") + (mana > 0 ? "  " + mana + Lang.t("魔", " mp") : "")
                        + (sta > 0 ? "  " + sta + Lang.t("體", " st") : "")).trim();
            // the name column ends where the description starts: costs that do not fit go on a second line
            boolean two = !cost.isEmpty() && g.getFontMetrics().stringWidth(name + "  " + cost) > 222 - 50 - 8;
            shadowText(g, two || cost.isEmpty() ? name : name + "  " + cost, 50, y, a.color().brighter());
            if (two) shadowText(g, cost, 62, y + 15, a.color().brighter());
            int y0 = y;
            for (String line : wrap(g, a.info(pvp), 400)) { shadowText(g, line, 222, y, new Color(0xD0D6E0)); y += 15; }
            if (two) y = Math.max(y, y0 + 30);
            y += Lang.en ? 4 : 6;
        }
        String[][] boss = Lang.en ? BOSS_HELP_EN : new String[][] {
            { "索命咒（遠）", "鎖定位置的綠線，石柱可擋；繳械咒可相撞抵銷。二段：再補兩道打在你左右閃的落點，主線過後回原位最安全。" },
            { "酷刑咒（中遠）", "追蹤紅球，昏擊咒可抵銷（暴牙咒兩發抵一顆）、鐵甲咒反彈（碎玻璃、綠光也會彈回）。二段：他身上再爆出一整圈直線紅球。" },
            { "火焰巨蛇（中）", "追著你爬並留下火焰地面。二段：燒盡時炸成一圈小火環（先用昏擊咒打散就不會炸）。" },
            { "黑魔標記（中遠）", "落雷一圈圈由內往外；狂怒時是安全格會交換的棋盤，最後再收一圈。" },
            { "娜吉妮（中遠）", "沿預警直線高速穿越全場；後空翻可越過，召蛇咒的黑蛇會撲上去攔下她。狂怒時四連衝。" },
            { "黑煙突襲（近遠）", "化煙連續直衝，二段：停下的落點炸開一圈衝擊，別站在他的落點旁。" },
            { "碎玻璃風暴（近中）", "碎片圈向外擴散、每圈留缺口；二段：遠處一圈碎片再往他收回。暴牙咒兩發抵銷一片。" },
            { "厲火之環（近）", "地面火環擴散，後空翻越過、躲在石柱後，或預先放好召蛇咒的黑蛇把整圈吞掉。" },
            { "陰屍（中遠）", "從四周爬出追你，包圍圈必留缺口。咒語、蛇、迴旋踢、鐵甲咒都能消滅。" },
            { "震退咒（近）", "扇形衝擊波；二段：接著一圈全方位衝擊，要拉開距離或用無敵幀穿過。" },
            { "召喚食死人（中遠）", "最多 3 名（10 血）繞著你射綠光，暴牙咒兩發抵銷一道綠光；陰屍另計，最多 3 隻。" },
        };
        y = 110;
        for (String[] s : boss) {
            shadowText(g, s[0], 660, y, new Color(0xE8C8FF));
            y += 15;
            for (String line : wrap(g, s[1], 560)) { shadowText(g, line, 672, y, new Color(0xC8CCD8)); y += 15; }
            y += Lang.en ? 4 : 7;
        }
        g.setFont(fSmall);
        String rules = Lang.t("一擊必死。計畫一演完就立刻進入下一次子彈時間；佛地魔會等你的計畫演完才出下一招，所以預覽＝結果。子彈時間＝他這波攻擊的長度，排滿就不扣分。近身 2 秒沒打中他，他會幻影移形拉開距離。計分：越快擊敗他速攻獎勵越高（(240-秒數)×60）；動作分×連擊；換類別×1.3；整段沒被打中加 15×秒數²；閃避穿過攻擊＋30；抵銷彈幕＋10；被擊中 -120；留白每秒 -20",
                              "One hit kills. Voldemort waits for your plan to finish, so the preview is the outcome; bullet time lasts as long as his attack, fill it to avoid penalties. Stay close 2 s without hitting him and he apparates away. Score: speed bonus (240 - seconds) × 60 on a win; move points × combo; category switch ×1.3; unhit stretch +15 × seconds²; dodge through +30; cancel +10; hit -120; idle -20/s");
        List<String> rl = wrap(g, rules, W - 120);
        for (int i = 0; i < rl.size(); i++) centered(g, rl.get(i), W / 2, H - 46 - (rl.size() - 1 - i) * 19, new Color(0xFFE38A));
        centered(g, Lang.t("按 H 關閉", "Press H to close"), W / 2, H - 26, new Color(0x9098B0));
    }

    static final String[][] BOSS_HELP_EN = {
        { "Avada Kedavra (far)", "A green line locked on your spot; pillars block it, Expelliarmus cancels it. Stage 2: two beams where you would sidestep, so step back to your old spot." },
        { "Cruciatus Curse (mid–far)", "Homing red orbs; Stupefy cancels them (or two Densaugeo per orb) and Protego reflects them. Stage 2: a full ring of straight orbs bursts out of him." },
        { "Fire Serpent (mid)", "Crawls after you, leaving burning ground. Stage 2: it burns out into a small fire ring, unless Stupefy dispersed it." },
        { "Dark Mark (mid–far)", "Rings of lightning from the inside out; when enraged, a checkerboard whose safe squares swap, then one last ring." },
        { "Nagini (mid–far)", "Dashes across the hall along a warning line; backflip over her or let your snake stop her. Four dashes when enraged." },
        { "Smoke Rush (close–far)", "He turns to smoke and charges again and again. Stage 2: a shockwave bursts where he lands." },
        { "Glass Storm (close–mid)", "Rings of shards spread outward, each with a gap. Stage 2: a far ring flies back in toward him. Two Densaugeo shots cancel a shard." },
        { "Fiendfyre Ring (close)", "A ring of fire spreads along the ground: backflip over it, hide behind a pillar, or have a snake waiting to swallow it." },
        { "Inferi (mid–far)", "They crawl out around you and give chase, always leaving a gap. Spells, the snake, Spin Kick and Protego all destroy them." },
        { "Depulso (close)", "A cone-shaped shockwave. Stage 2: a full ring follows; get distance or pass through it while invincible." },
        { "Summon Death Eaters (mid–far)", "Up to 3 (10 HP) circle you firing green bolts; two Densaugeo shots cancel one. Plus up to 3 Inferi." },
    };

    // ------------------------------------------------------------------ ui helpers

    static List<String> wrap(Graphics2D g, String s, int width) {
        ArrayList<String> out = new ArrayList<>();
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (fm.stringWidth(line.toString() + ch) > width && line.length() > 0) {
                // English breaks between words; Chinese can break anywhere
                int cut = !Lang.en || ch == ' ' ? line.length() : line.lastIndexOf(" ") + 1;
                if (cut <= 0) cut = line.length();
                out.add(line.substring(0, cut).trim());
                line.delete(0, cut);
            }
            if (line.length() == 0 && ch == ' ' && Lang.en) continue;
            line.append(ch);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Shrinks the current font until {@code s} fits in {@code width}. */
    /** Cuts a line to fit {@code width} pixels in the current font, ending it with an ellipsis. */
    static String ellipsize(Graphics2D g, String s, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) return s;
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n) + "…") > width) n--;
        return s.substring(0, n) + "…";
    }

    static void fit(Graphics2D g, String s, int width) {
        Font f = g.getFont();
        while (f.getSize2D() > 9 && g.getFontMetrics(f).stringWidth(s) > width) f = f.deriveFont(f.getSize2D() - 1);
        g.setFont(f);
    }

    void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(6, 8, 20, 170));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(new Color(184, 155, 74, 140));
        g.drawRoundRect(x, y, w, h, 12, 12);
    }

    /** During bullet time the bars play the plan with the preview: the duel as it stands at the timeline cursor. */
    World hudPreview() { return state == State.PLAN && !snaps.isEmpty() ? snapAt(cursorT) : null; }

    /**
     * A bar that shows a value now and where the previewed plan takes it: the solid fill is the previewed value,
     * the faint part what the plan spends (or wins back), and the label reads "now → then".
     */
    void bar(Graphics2D g, int x, int y, int w, int h, double now, double then, double max, Color c, String name, String suffix) {
        int a = (int) now, b = (int) then;
        if (a == b) { bar(g, x, y, w, h, then / max, c, name + b + suffix); return; }
        double hi = Math.max(0, Math.min(1, Math.max(now, then) / max));
        g.setColor(new Color(30, 30, 40));
        g.fillRect(x, y, w, h);
        g.setColor(then < now ? Sprites.alpha(c, 90) : Sprites.alpha(Color.WHITE, 110));
        g.fillRect(x, y, (int) (w * hi), h);
        bar(g, x, y, w, h, Math.min(now, then) / max, c, name + a + " → " + b + suffix, false);
    }

    void bar(Graphics2D g, int x, int y, int w, int h, double frac, Color c, String label) { bar(g, x, y, w, h, frac, c, label, true); }

    private void bar(Graphics2D g, int x, int y, int w, int h, double frac, Color c, String label, boolean back) {
        frac = Math.max(0, Math.min(1, frac));
        if (back) {
            g.setColor(new Color(30, 30, 40));
            g.fillRect(x, y, w, h);
        }
        g.setColor(c);
        g.fillRect(x, y, (int) (w * frac), h);
        g.setColor(Sprites.alpha(Color.WHITE, 60));
        g.fillRect(x, y, (int) (w * frac), 2);
        g.setColor(new Color(0, 0, 0, 160));
        g.drawRect(x, y, w, h);
        g.setFont(fTiny);
        shadowText(g, label, x + 6, y + h - 2, Color.WHITE);
    }

    static void shadowText(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(new Color(0, 0, 0, Math.min(200, c.getAlpha())));
        g.drawString(s, x + 1, y + 2);
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static void centered(Graphics2D g, String s, int x, int y, Color c) {
        shadowText(g, s, x - g.getFontMetrics().stringWidth(s) / 2, y, c);
    }
}
