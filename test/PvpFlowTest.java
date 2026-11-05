import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** The two-player turn order on one computer, driven through the game itself; saves screenshots to args[0]. */
public class PvpFlowTest {
    static int fails = 0;

    static void check(String name, boolean ok, String info) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + "  (" + info + ")");
        if (!ok) fails++;
    }

    public static void main(String[] args) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { try { body(args.length > 0 ? args[0] : null); minTurn(); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println(fails == 0 ? "PVP FLOW OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void body(String out) throws Exception {
        Game g = new Game(1.0);
        g.setSize(1280, 720);
        g.seed = 11;
        g.newPvp();
        World w = g.world;
        w.ps[0].x = 250; w.ps[0].y = 240; w.ps[1].x = 560; w.ps[1].y = 250;
        g.rebuildPreview();
        check("round 1: player 1 plans first with 5 s", g.state == Game.State.PLAN && w.me == 0 && g.pvpPhase == 0 && g.planWindow == Game.PVP_TURN,
              "me=" + w.me + " window=" + g.planWindow);

        // player 1: a short run, then charges and releases Stupefy; an attack does not hand over
        g.addItem(World.PlanItem.move(ActionType.RUN, 1, 0, 24));
        g.addItem(World.PlanItem.aimed(ActionType.STUPEFY, false, 1, 0));
        shot(g, out, "pvp_p1_plan.png");
        g.addItem(World.PlanItem.aimed(ActionType.STUPEFY, true, 1, 0));
        check("an attack does not hand over: only Enter does", g.pvpPhase == 0 && w.me == 0, "phase=" + g.pvpPhase + " me=" + w.me);
        g.execute();
        int first = 24 + ActionType.STUPEFY.steps + ActionType.RELEASE_STEPS;
        check("Enter hands over: player 2's turn is as long as player 1's first turn", g.state == Game.State.PLAN && w.me == 1
                  && g.allowSteps == first && g.roomSteps() == first, "state=" + g.state + " me=" + w.me + " allow=" + g.allowSteps + " room=" + g.roomSteps());
        check("player 2 sees player 1's plan in the preview", !g.foeGhosts.isEmpty(), "foeGhosts=" + g.foeGhosts.size());

        // player 2 waits 80 steps; Protego (60) no longer fits in this turn
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 80, false, false, 0, 0));
        g.addItem(World.PlanItem.of(ActionType.PROTEGO));
        check("an order longer than the turn is refused", g.draft.size() == 1 && g.roomSteps() == first - 80, "draft=" + g.draft.size() + " room=" + g.roomSteps());
        shot(g, out, "pvp_p2_plan.png");
        g.execute();
        check("Enter spends the unplanned rest of the turn waiting: player 2 used all of player 1's turn", Math.abs(g.sideTotal(1) - first * World.DT) < 1e-6,
              "total1=" + g.sideTotal(1));
        check("then player 1 (first side) plans again with no limit", g.state == Game.State.PLAN && w.me == 0 && g.allowSteps == -1 && g.draft.size() == 3,
              "state=" + g.state + " me=" + w.me + " allow=" + g.allowSteps + " draft=" + g.draft.size());
        g.undo();
        check("what the other player has seen cannot be undone", g.draft.size() == 3, "draft=" + g.draft.size());
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 90, false, false, 0, 0));
        g.execute();
        check("the second side's turn is as long as the first side's last one", w.me == 1 && g.allowSteps == 90
                  && Math.abs(g.sideTotal(0) - (first + 90) * World.DT) < 1e-6, "allow=" + g.allowSteps + " total0=" + g.sideTotal(0) + " me=" + w.me);
        g.togglePreviewRange();
        g.updateCamera(1 / 60.0);
        check("the turn preview starts where the other side's last turn began", Math.abs(g.previewFrom() - first * World.DT) < 1e-6
                  && g.cursorT >= g.previewFrom() - 1e-9 && g.viewWorld() != w && g.viewWorld().steps == w.steps + first,
              "from=" + g.previewFrom() + " cursor=" + g.cursorT + " baseSteps=" + (g.viewWorld().steps - w.steps));
        shot(g, out, "pvp_preview_turn.png");
        g.togglePreviewRange();
        check("...and back to the whole plan", g.previewFrom() == 0 && g.viewWorld() == w, "from=" + g.previewFrom());
        // player 2 raises Protego just as the bolt arrives
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 30, false, false, 0, 0));
        g.addItem(World.PlanItem.of(ActionType.PROTEGO));
        g.execute();
        check("no playback until both sides have the full 5 s", g.state == Game.State.PLAN, "state=" + g.state);
        for (int i = 0; i < 40 && g.state == Game.State.PLAN; i++) g.execute();
        check("once both have planned the shared 5 s, the round plays", g.state == Game.State.RUN, "state=" + g.state);
        g.centerPanOn(700, 400);
        g.updateCamera(1 / 60.0);
        double cx = g.camX;
        g.runFrame(0.5);
        g.updateCamera(1 / 60.0);
        check("while the round plays the camera can be moved and stays put", g.camLocked && g.camX == cx, "locked=" + g.camLocked + " camX " + cx + " -> " + g.camX);
        g.panKey(java.awt.event.KeyEvent.VK_TAB, true);
        g.updateCamera(1 / 60.0);
        boolean shotRun = false;
        for (int i = 0; i < 900 && g.state == Game.State.RUN; i++) {
            g.runFrame(1 / 60.0);
            g.updateFx(1 / 60.0);
            if (!shotRun && w.steps - g.runStart >= 75) { g.shake = 0; shot(g, out, "pvp_run.png"); shotRun = true; }
        }
        check("Protego bounces the Stupefy back and it knocks player 1 out in the same round", g.state == Game.State.END && w.winner == 1
                  && w.steps - g.runStart > 3 * 60, "state=" + g.state + " winner=" + w.winner + " steps=" + (w.steps - g.runStart));
        g.endDelay = 0;
        shot(g, out, "pvp_end.png");

        // the replay plays the whole duel again from the recording, on the whole-hall view
        World fin = g.world;
        g.startReplay();
        check("the replay opens on the whole hall", g.state == Game.State.REPLAY && g.fullView, "state=" + g.state + " full=" + g.fullView);
        boolean shotReplay = false;
        for (int i = 0; i < 6000 && !g.replayPaused; i++) {
            g.replayFrame(1 / 60.0);
            if (!shotReplay && g.world.steps > 120) { shot(g, out, "pvp_replay.png"); shotReplay = true; }
        }
        World rw = g.world;
        check("the replay ends exactly like the duel", rw.over && rw.winner == fin.winner && rw.steps == fin.steps
                  && rw.ps[0].x == fin.ps[0].x && rw.ps[1].y == fin.ps[1].y,
              "winner " + rw.winner + "/" + fin.winner + " steps " + rw.steps + "/" + fin.steps);
        g.exitReplay();
        check("leaving the replay goes back to the result", g.state == Game.State.END && !g.fullView && g.world == fin, "state=" + g.state);

        // a fall already settled inside what both have planned plays at once, before the 5 s are full
        g.newPvp();
        w = g.world;
        w.ps[0].x = 250; w.ps[0].y = 240; w.ps[1].x = 560; w.ps[1].y = 250;
        g.rebuildPreview();
        g.addItem(World.PlanItem.aimed(ActionType.STUPEFY, false, 1, 0));
        g.addItem(World.PlanItem.aimed(ActionType.STUPEFY, true, 1, 0));
        g.execute();                                        // player 1: Stupefy, flying from about 1 s
        g.execute();                                        // player 2 stands still for the same time
        check("a hit that player 2 could still dodge does not end the planning", g.state == Game.State.PLAN && w.me == 0, "state=" + g.state);
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 120, false, false, 0, 0));
        g.execute();
        check("...nor while only player 1 has planned past it", g.state == Game.State.PLAN && w.me == 1, "state=" + g.state);
        g.execute();                                        // player 2 lets the turn pass without a dodge
        check("once both have planned past the hit, the round plays at once", g.state == Game.State.RUN && g.doomedRun
                  && g.sideTotal(0) < Game.PVP_TURN, "state=" + g.state + " doomed=" + g.doomedRun);
        for (int i = 0; i < 900 && g.state == Game.State.RUN; i++) g.runFrame(1 / 60.0);
        check("...and plays until player 2 falls", g.state == Game.State.END && w.winner == 0, "state=" + g.state + " winner=" + w.winner);

        // a quiet round: both press Enter without planning, the full 5 s plays, then player 2 goes first
        g.newPvp();
        w = g.world;
        g.execute();
        check("an empty Enter fills player 1 and hands over", g.sideDone[0] && w.me == 1 && g.state == Game.State.PLAN, "me=" + w.me);
        g.execute();
        check("...and the second empty Enter plays the round", g.state == Game.State.RUN, "state=" + g.state);
        int start = w.steps;
        for (int i = 0; i < 900 && g.state == Game.State.RUN; i++) g.runFrame(1 / 60.0);
        check("the playback runs the whole 5 s, then round 2 opens with player 2 first", g.state == Game.State.PLAN && g.round == 1 && w.me == 1
                  && w.steps - start >= Game.PVP_TURN * 60, "state=" + g.state + " round=" + g.round + " me=" + w.me + " steps=" + (w.steps - start));
    }

    /** A bullet time is at least 0.3 s, though the actions in it may be shorter. */
    static void minTurn() {
        Game g = new Game(1.0);
        g.setSize(1280, 720);
        g.seed = 12;
        g.newPvp();
        g.addItem(new World.PlanItem(ActionType.DASH, 1, 0, ActionType.DASH.steps, false, false, 0, 0));
        check("an action shorter than 0.3 s can be planned", g.draft.size() == 1 && ActionType.DASH.steps < Game.PVP_MIN_TURN, "draft=" + g.draft.size());
        g.execute();
        check("but the turn (one bullet time) lasts at least 0.3 s: the rest is waited out", g.world.me == 1 && g.allowSteps == Game.PVP_MIN_TURN
                  && Math.abs(g.sideTotal(0) - Game.PVP_MIN_TURN * World.DT) < 1e-6, "allow=" + g.allowSteps + " total0=" + g.sideTotal(0));
        g.addItem(new World.PlanItem(ActionType.WAIT, 0, 0, 10, false, false, 0, 0));
        g.execute();
        check("a 0.3 s first turn leaves the second side 0.3 s too", Math.abs(g.sideTotal(1) - Game.PVP_MIN_TURN * World.DT) < 1e-6, "total1=" + g.sideTotal(1));
    }

    static void shot(Game g, String out, String name) throws Exception {
        if (out == null) return;
        g.updateCamera(1 / 60.0);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = img.createGraphics();
        g.paint(gg);
        gg.dispose();
        ImageIO.write(img, "png", new File(out, name));
    }
}
