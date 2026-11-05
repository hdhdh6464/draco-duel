import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;

/**
 * The beginner's tutorial: a real duel against Voldemort in which Malfoy cannot fall, with a lesson card that
 * says what to try next. Lessons that only explain wait for Enter; the others wait until the player has done it.
 */
final class Tutorial {
    /** What a lesson waits for. */
    enum Need { ENTER, MOVE, ROLL, RELEASE, RUN, PLAN, MELEE_OR_RUN }

    static final class Lesson {
        final String zh, en; final Need need;
        Lesson(Need need, String zh, String en) { this.need = need; this.zh = zh; this.en = en; }
    }

    static final Lesson[] LESSONS = {
        new Lesson(Need.ENTER,
            "歡迎！時間已經停止了 — 這就是「子彈時間」。佛地魔每次施法前，時光器都會凍結時間，換你安排接下來的每個動作。",
            "Welcome! Time has stopped: this is bullet time. Before each of Voldemort's spells the Time-Turner freezes time and you plan every move to come."),
        new Lesson(Need.MOVE,
            "移動：按 W A S D 排一小段移動，或用滑鼠左鍵點地圖，直接跑到那裡。",
            "Moving: press W A S D for a short move, or left-click the map to run there."),
        new Lesson(Need.ENTER,
            "半透明的虛影是「預演」：照你的計畫會發生的事，一定準確。下方時間軸可以拖曳看任何時刻，紅色標記代表那時你會被打中。排錯了按 Backspace 復原。",
            "The see-through ghosts are the preview: exactly what your plan will do. Drag the timeline below to see any moment; a red mark means you get hit then. Backspace undoes a mistake."),
        new Lesson(Need.ROLL,
            "閃避：按 Q 翻滾（朝滑鼠方向），翻滾途中無敵。E 側閃、Z 後空翻、C 幻影移形也都能躲攻擊，用完要冷卻。",
            "Dodging: Q rolls toward the mouse and is invincible on the way. E sidestep, Z backflip and C apparate dodge too; each cools down after use."),
        new Lesson(Need.RELEASE,
            "遠程咒語：按 2 讀條繳械咒，讀完會先「儲存」起來；再按一次 2 才朝滑鼠方向施放。咒語可以抵銷他的彈幕。",
            "Spells: press 2 to charge Expelliarmus; once charged it is stored. Press 2 again to cast it toward the mouse. Spells can cancel his barrage."),
        new Lesson(Need.RUN,
            "按 Enter 開始演出，看你的計畫實際上演。",
            "Press Enter to play your plan out."),
        new Lesson(Need.PLAN,
            "演出中…計畫一演完，或佛地魔再次施法，就會再進入子彈時間。",
            "Playing… once the plan has played out, or Voldemort casts again, bullet time comes back."),
        new Lesson(Need.ENTER,
            "近戰：F 跳踢、G 連拳、R 迴旋踢、T 過肩摔，要在圓圈範圍內才打得到。5 是鐵甲咒，能把部分彈幕反彈回去。長按任何卡片可以看詳細說明。",
            "Melee: F jump kick, G flurry, R spin kick, T shoulder throw; they hit inside their circle. 5 is Protego: it bounces some of his barrage back. Hold any card for its details."),
        new Lesson(Need.MELEE_OR_RUN,
            "試著把計畫排滿佛地魔整段施法（時間軸上的範圍），再按 Enter。動作越連貫、規劃越完整，分數越高。",
            "Try to fill his whole cast with your plan (the span on the timeline), then press Enter. The smoother and fuller the plan, the higher the score."),
        new Lesson(Need.ENTER,
            "教學完成！正式決鬥時，被任何攻擊打中一次就會倒下。按 Enter 開始正式決鬥，Esc 回標題，H 隨時查看所有招式。",
            "Tutorial complete! In a real duel any hit brings Malfoy down. Enter: start a real duel   Esc: title   H: every move, any time"),
    };

    int step;

    Lesson lesson() { return LESSONS[step]; }

    void next(Game g) {
        step++;
        Audio.play(Audio.Sfx.CLICK);
        if (step >= LESSONS.length) { g.tutorial = null; g.newGame(); }
    }

    /** A key while the tutorial runs; true when the tutorial used it. */
    boolean key(Game g, int k) {
        if (k != KeyEvent.VK_ENTER || lesson().need != Need.ENTER) return false;
        // the lesson cards that only explain are read in bullet time; the last one starts a real duel
        if (g.state != Game.State.PLAN && step < LESSONS.length - 1) return false;
        next(g);
        return true;
    }

    /** Moves on once the player has done what the lesson asks. */
    void tick(Game g) {
        if (g.state == Game.State.END) return;
        switch (lesson().need) {
            case MOVE: if (has(g.draft, ActionType.RUN, false)) next(g); break;
            case ROLL: if (has(g.draft, ActionType.ROLL, false)) next(g); break;
            case RELEASE: if (has(g.draft, ActionType.EXPELLIARMUS, true)) next(g); break;
            case RUN: if (g.state == Game.State.RUN) next(g); break;
            case PLAN: if (g.state == Game.State.PLAN) next(g); break;
            case MELEE_OR_RUN: if (g.state == Game.State.RUN) next(g); break;
            default: break;
        }
    }

    static boolean has(ArrayList<World.PlanItem> draft, ActionType t, boolean release) {
        for (World.PlanItem it : draft) if (it.type == t && (!release || it.alt)) return true;
        return false;
    }

    /** The lesson card, under the score at the top of the screen. */
    void draw(Game g, Graphics2D gr) {
        Lesson l = lesson();
        String text = Lang.t(l.zh, l.en);
        int w = 700, x = Game.W / 2 - w / 2, y = 98;
        gr.setFont(g.fSmall);
        FontMetrics fm = gr.getFontMetrics();
        ArrayList<String> lines = wrap(text, fm, w - 32);
        String foot = l.need == Need.ENTER ? (step == LESSONS.length - 1 ? "" : Lang.t("按 Enter 繼續", "Press Enter to go on"))
                    : l.need == Need.PLAN ? "" : Lang.t("做到就會自動進入下一步", "Do it to go on");
        int h = 40 + lines.size() * (fm.getHeight() + 2) + (foot.isEmpty() ? 0 : 20);
        gr.setColor(new Color(10, 14, 34, 228));
        gr.fillRoundRect(x, y, w, h, 14, 14);
        gr.setColor(new Color(0xFFD86B));
        gr.drawRoundRect(x, y, w, h, 14, 14);
        gr.setFont(g.fTiny);
        Game.shadowText(gr, String.format(Lang.t("新手教程 %d / %d　（教學中不會倒下，每次子彈時間至少 5 秒）", "Tutorial %d / %d   (Malfoy cannot fall; every bullet time is at least 5 s)"), step + 1, LESSONS.length), x + 16, y + 20, new Color(0xFFD86B));
        gr.setFont(g.fSmall);
        int ty = y + 26 + fm.getAscent();
        for (String s : lines) { Game.shadowText(gr, s, x + 16, ty, Color.WHITE); ty += fm.getHeight() + 2; }
        if (!foot.isEmpty()) {
            gr.setFont(g.fTiny);
            Game.shadowText(gr, foot, x + w - 16 - gr.getFontMetrics().stringWidth(foot), y + h - 10, new Color(0x9FE8A0));
        }
    }

    /** Breaks text into lines that fit; Chinese wraps anywhere, English at spaces. */
    static ArrayList<String> wrap(String text, FontMetrics fm, int width) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = Lang.en ? text.split(" ") : text.split("");
        for (String wd : words) {
            String add = Lang.en && line.length() > 0 ? " " + wd : wd;
            if (fm.stringWidth(line + add) > width && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(wd); }
            else line.append(add);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
