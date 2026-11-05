import java.awt.Color;

/**
 * Every move Malfoy can be directed to perform. Durations are fixed (moves carry their own), so a plan is fully
 * predictable. Ranged spells are charged first (dur = charge time) and stored; a second order releases them.
 */
enum ActionType {
    RUN         ("移動",     "Move", "Move",             0.4,  0, Cat.MOVE,    10, 0,   "跑步 75 / 漫步 40 像素每秒（V 切換）；左鍵點地圖＝走到那裡",
                    "Run 75 / walk 40 px/s (V toggles); left-click the map to go there"),
    WAIT        ("等待",     "Wait", "Wait",             0.3,  0, Cat.WAIT,     2, 0,   "原地等 0.3 秒，用來卡準時機",
                    "Stand still for 0.3 s to fine-tune your timing"),

    ROLL        ("翻滾",     "Roll", "Roll",             0.5,  0, Cat.DODGE,   20, 2.0, "朝滑鼠方向翻滾 56 像素，0.03~0.47 秒無敵；右鍵點地圖＝朝那裡翻滾",
                    "Roll 56 px to the mouse, invincible 0.03–0.47 s (right-click map)"),
    DASH        ("側閃",     "Sidestep", "Dash",         0.25, 0, Cat.DODGE,   15, 1.4, "往佛地魔的側面急閃 36 像素，全程無敵（滑鼠決定左右）",
                    "Dart 36 px to his side, fully invincible; the mouse picks the side"),
    BACKFLIP    ("後空翻",   "Backflip", "Flip",         0.6,  0, Cat.DODGE,   20, 2.5, "向後空翻 26 像素，0.05~0.57 秒無敵並騰空越過地面攻擊",
                    "Flip back 26 px, invincible 0.05–0.57 s, airborne over ground attacks"),
    APPARATE    ("幻影移形", "Apparate", "Apparate",         0.35, 0, Cat.DODGE,   30, 10.0, "瞬間移動到滑鼠方向最多 90 像素處，消失期間無敵",
                    "Teleport up to 90 px toward the mouse; invincible while vanished"),

    DENSAUGEO   ("暴牙咒",   "Densaugeo", "Dens",        0.3, 12, Cat.RANGED,  10, 0,   "免吟唱：0.3 秒射出、自動追蹤最近的敵人，傷害 4，沒有冷卻。兩發抵銷一個彈幕（碎玻璃、食死人綠光、酷刑咒光球）；每發 12 魔力",
                    "Wordless, 0.3 s, homes on the nearest enemy, 4 damage, no cooldown, 12 mana. Two shots cancel any barrage shot (glass shard, Death Eater bolt, Crucio orb)"),
    EXPELLIARMUS("繳械咒",   "Expelliarmus", "Expel",     0.6, 15, Cat.RANGED,  20, 0,   "讀條 0.6 秒（15 魔力）。朝滑鼠方向直射的紅光，傷害 8；打中索命咒的射線兩咒相撞抵銷；命中蓄力中的佛地魔可打斷施法（第二階段無效）",
                    "Charge 0.6 s, 15 mana. Red bolt toward the mouse, 8 damage; cancels the Avada beam it meets; interrupts a cast (not in phase 2)"),
    STUPEFY     ("昏擊咒",   "Stupefy", "Stupefy",          0.9, 25, Cat.RANGED,  25, 0,   "讀條 0.9 秒（25 魔力）。朝滑鼠方向直射，傷害 12，打斷施法並昏迷 1.5 秒；貫穿抵銷酷刑咒光球與火焰巨蛇（最多 3 個）",
                    "Charge 0.9 s, 25 mana. Bolt toward the mouse, 12 damage, interrupts and stuns 1.5 s; pierces Crucio orbs and the Fire Serpent (up to 3)"),
    SERPENSORTIA("召蛇咒",   "Serpensortia", "Snake",     1.2, 35, Cat.RANGED,  30, 0,   "讀條 1.2 秒（35 魔力）。黑蛇爬到滑鼠指定的地點埋伏：吞掉掃過的厲火之環、攔下衝過的娜吉妮，咬碎碰到的陰屍與敵人（傷害 18）",
                    "Charge 1.2 s, 35 mana. A snake lurks at the marked spot: swallows Fiendfyre rings, stops Nagini, bites Inferi and enemies (18 damage)"),

    PROTEGO     ("鐵甲咒",   "Protego", "Protego",          1.0, 20, Cat.DEFENSE, 25, 0,   "常駐防禦：把酷刑咒光球、碎玻璃、食死人綠光反彈回敵人身上，擋下黑魔標記與陰屍；擋不住索命咒與地面火焰",
                    "Standing shield: bounces Crucio orbs, glass shards and Death Eater bolts back at the enemy; blocks the Dark Mark and Inferi, but not Avada or ground fire"),

    KICK        ("跳踢",     "Jump Kick", "Kick",       0.7,  0, Cat.MELEE,   25, 2.5, "跳向最近的敵人（最多 44 像素），空中翻轉半圈朝頭頂砸踢，圈內敵人受 11 傷害；第一階段可打斷施法。騰空時躲過彈幕與地面攻擊，但躲不開從天而降的落雷",
                    "Leap at the nearest enemy (up to 44 px), tumble half a turn and axe-kick its head: 11 damage in the circle, interrupts in phase 1. Airborne: barrages and ground attacks miss, lightning from the sky does not"),
    FLURRY      ("連拳",     "Flurry", "Flurry",          1.0,  0, Cat.MELEE,   20, 2.0, "身體微壓低，緊追最近的敵人狂風暴雨般連揮八拳，圓圈內敵人每拳受 1 傷害（共 8）",
                    "Crouch a little and unleash a storm of eight punches while pressing in on the nearest enemy: 1 damage a punch in the circle (8 in all)"),
    SPIN        ("迴旋踢",   "Spin Kick", "Spin",        0.6,  0, Cat.MELEE,   25, 3.0, "轉身一圈的迴旋踢：較大圓圈內全部敵人受 14 傷害並擊退；第一階段可打斷施法",
                    "A full-turn spin kick: 14 damage and knockback in a bigger circle; interrupts in phase 1"),
    THROW       ("過肩摔",   "Shoulder Throw", "Throw",   1.4,  0, Cat.MELEE,   45, 6.0, "小範圍圈內抓住佛地魔，連續三次過肩摔（10＋10＋14）：必定打斷施法、最後摔倒在地昏迷 1.2 秒；抓住後全程無敵。他的身體砸到食死人或陰屍造成該次 30% 傷害，碰到他的彈幕會被反彈",
                    "Grab him in a small circle and throw him over your shoulder three times (10+10+14): always interrupts, 1.2 s stun on the floor; invincible meanwhile. His body deals 30% of each throw to servants it hits, and bounces his barrage back"),

    HURT        ("受創",     "Hurt", "Hurt",             0.45, 0, Cat.HURT,     0, 0,   "",
                    "");

    enum Cat { MOVE, WAIT, DODGE, RANGED, DEFENSE, MELEE, HURT }

    /** Releasing a stored spell takes this many steps; the bolt leaves at RELEASE_FIRE. */
    static final int RELEASE_STEPS = 12, RELEASE_FIRE = 4;
    static final double WALK_SPEED = 40;
    static final ActionType[] SPELLS = { DENSAUGEO, EXPELLIARMUS, STUPEFY, SERPENSORTIA };

    final String zh, latin, abbr, desc, descEn;
    final double dur, cooldown;
    final int steps, mana, score;
    final Cat cat;

    ActionType(String zh, String latin, String abbr, double dur, int mana, Cat cat, int score, double cooldown, String desc, String descEn) {
        this.zh = zh; this.latin = latin; this.abbr = abbr; this.dur = dur; this.mana = mana; this.cat = cat;
        this.score = score; this.cooldown = cooldown; this.desc = desc; this.descEn = descEn;
        this.steps = (int) Math.round(dur * 60);
    }

    /** Name, short name for cards and the timeline, and description in the current language. */
    String label() { return Lang.en ? latin : zh; }
    String shortName() { return Lang.en ? abbr : zh; }
    String info() { return Lang.en ? descEn : desc; }
    /** The description with the mana cost of the mode being played (a duel charges more). */
    String info(boolean pvp) {
        if (!pvp || mana == 0 || mana(true) == mana) return info();
        return info().replace(mana + " 魔力", mana(true) + " 魔力").replace(mana + " mana", mana(true) + " mana");
    }

    boolean isSpell() { return cat == Cat.RANGED; }
    /** Charged spells are read, stored, then released by a second order; the light attack just fires. */
    boolean charged() { return isSpell() && this != DENSAUGEO; }
    /** Step at which the light attack leaves the wand. */
    static final int LIGHT_FIRE = 7;
    /** Flight speed of a spell's bolt, px per second. */
    double speed() {
        switch (this) {
            case DENSAUGEO: return 300;
            case EXPELLIARMUS: return 320;
            case STUPEFY: return 240;
            case SERPENSORTIA: return 110;
            default: return 0;
        }
    }

    // two-player duel costs: spells take more mana and need time to recover, dodges and melee spend stamina
    int mana(boolean pvp) {
        if (!pvp) return mana;
        switch (this) {
            case DENSAUGEO: return 18;
            case EXPELLIARMUS: return 25;
            case STUPEFY: return 38;
            case SERPENSORTIA: return 50;
            case PROTEGO: return 40;
            default: return mana;
        }
    }
    /** Cooldown; a charged spell's starts when it is released, and blocks the next charge. */
    double cooldown(boolean pvp) {
        if (!pvp) return cooldown;
        switch (this) {
            case EXPELLIARMUS: return 1.0;
            case STUPEFY: return 1.5;
            case SERPENSORTIA: return 2.0;
            default: return cooldown;
        }
    }
    int stamina(boolean pvp) {
        if (!pvp) return 0;
        switch (this) {
            case ROLL: return 25;
            case DASH: return 20;
            case BACKFLIP: return 25;
            case APPARATE: return 35;
            case KICK: return 25;
            case FLURRY: return 30;
            case SPIN: return 25;
            case THROW: return 40;
            default: return 0;
        }
    }

    boolean isMelee() { return cat == Cat.MELEE; }
    boolean isDodge() { return cat == Cat.DODGE; }
    int spellIndex() { return ordinal() - DENSAUGEO.ordinal(); }

    /** Radius of a melee move's hit circle around Malfoy. */
    double meleeRadius() { return this == SPIN ? 34 : this == THROW ? 18 : this == KICK ? 24 : 26; }

    /** The steps at which each punch of the flurry lands. */
    static final int[] FLURRY_HITS = { 10, 16, 22, 28, 34, 40, 46, 52 };
    /** The steps during which the blur of fists is on screen. */
    static final int FLURRY_RUSH_FROM = 6, FLURRY_RUSH_TO = 56;

    /** Shoulder throw: steps per lift-and-slam, and how many slams. */
    static final int SLAM_STEPS = 22, SLAMS = 3;

    /** Step at which a melee move connects. */
    int hitStep() {
        switch (this) {
            case KICK: return 32;
            case FLURRY: return FLURRY_HITS[0];
            case SPIN: return 18;
            case THROW: return 12;
            default: return -1;
        }
    }

    /** Steps [from, to] during which this dodge cannot be hit, or null. */
    int[] iframes() {
        switch (this) {
            case ROLL: return new int[] { 2, 27 };
            case DASH: return new int[] { 0, 15 };
            case APPARATE: return new int[] { 3, 18 };
            case BACKFLIP: return new int[] { 3, 33 };
            case THROW: return new int[] { 12, 12 + SLAM_STEPS * SLAMS };
            default: return null;
        }
    }

    /** Steps [from, to] spent in the air, clear of ground attacks, or null. */
    int[] airtime() { return this == BACKFLIP ? new int[] { 3, 33 } : this == KICK ? new int[] { 6, 33 } : null; }

    /** Step shown as this action's ghost in the preview: the moment that matters most. */
    int keyStep(int itemSteps, boolean release) {
        if (this == DENSAUGEO) return LIGHT_FIRE;
        if (isSpell()) return release ? RELEASE_FIRE : itemSteps;
        switch (this) {
            case ROLL: case DASH: case BACKFLIP: case PROTEGO: return itemSteps / 2;
            case APPARATE: return itemSteps;
            case KICK: case FLURRY: case SPIN: return hitStep();
            case THROW: return 12 + SLAM_STEPS / 2;
            case HURT: return -1;
            default: return itemSteps;
        }
    }

    Color color() {
        switch (cat) {
            case MOVE: return new Color(0x4FA3E0);
            case WAIT: return new Color(0x8A8F99);
            case DODGE: return new Color(0x58D0D8);
            case RANGED: return new Color(0xE0504F);
            case DEFENSE: return new Color(0x4FD08A);
            case MELEE: return new Color(0xF0A040);
            default: return new Color(0x804040);
        }
    }
}
