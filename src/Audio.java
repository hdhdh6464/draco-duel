import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Music and sound effects, all synthesized at start-up (no asset files). A daemon thread mixes the
 * background track and every playing effect into one output line. If no audio device can be opened
 * the game simply runs silent.
 */
final class Audio {
    private Audio() {}

    static final int RATE = 22050;

    enum Sfx {
        BOSS_CAST, AVADA, DENS, EXP, STUP, SNAKE, STORED, IMPACT, BOSS_HIT, HEAVY_HIT, MELEE, SWISH, SLAM,
        PLAYER_HIT, LOSE, CANCEL, CLASH, BLOCK, WHOOSH, APPARATE, BULLET_TIME, GO, CLICK, ERROR, FIRE,
        THUNDER, GLASS, MINION, WIN, PHASE2, INTERRUPT, STUN, HISS,
        /** One sound for each kind of cancel: what was destroyed, and by what. */
        X_CHIP, X_GLASS, X_BOLT, X_CRUCIO, X_REFLECT, X_FIRE, X_SWALLOW, X_NAGINI, X_INFERI,
        /** A fist into a truck: a deep boom, a dented-steel clang ringing out, the crunch of metal. */
        TRUCK
    }

    /** Background tracks: the duel, and the faster one once Voldemort is enraged. */
    static final int MUSIC_NONE = 0, MUSIC_DUEL = 1, MUSIC_RAGE = 2;

    private static final class Voice { final short[] data; int pos; final float vol; Voice(short[] d, float v) { data = d; vol = v; } }

    private static short[][] sfx;
    private static short[][] tracks = new short[3][];
    private static volatile int music = MUSIC_NONE, wanted = MUSIC_NONE;
    private static int musicPos;
    private static float musicGain;
    private static final ArrayList<Voice> voices = new ArrayList<>();
    private static final long[] lastPlayed = new long[Sfx.values().length];
    private static volatile boolean ready;
    static volatile boolean muted;

    /** Builds every sound and starts the mixer thread. Safe to call once; failures leave the game silent. */
    static void start() {
        Thread t = new Thread(() -> {
            try {
                build();
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                line.open(fmt, 4096);
                line.start();
                ready = true;
                mix(line);
            } catch (Throwable e) {
                ready = false;
            }
        }, "audio");
        t.setDaemon(true);
        t.start();
    }

    static void play(Sfx s) { play(s, 1f); }

    static void play(Sfx s, float vol) {
        if (!ready || muted) return;
        long now = System.nanoTime();
        // the same effect fired many times in one instant (a ring of shards) is heard once
        if (now - lastPlayed[s.ordinal()] < 45_000_000L) return;
        lastPlayed[s.ordinal()] = now;
        synchronized (voices) {
            if (voices.size() >= 18) voices.remove(0);
            voices.add(new Voice(sfx[s.ordinal()], vol));
        }
    }

    static void music(int track) { wanted = track; }

    static void toggleMute() {
        muted = !muted;
        if (muted) synchronized (voices) { voices.clear(); }
    }

    private static void mix(SourceDataLine line) {
        int n = 512;
        float[] acc = new float[n];
        byte[] out = new byte[n * 2];
        while (true) {
            Arrays.fill(acc, 0);
            // music crossfades: fade out, switch, fade back in
            for (int i = 0; i < n; i++) {
                float target = muted ? 0 : (music == wanted ? 1 : 0);
                musicGain += (target - musicGain) * 0.0004f;
                if (musicGain < 0.002f && music != wanted) { music = wanted; musicPos = 0; }
                short[] tr = tracks[music];
                if (tr != null) {
                    acc[i] += tr[musicPos] * 0.32f * musicGain;
                    musicPos = (musicPos + 1) % tr.length;
                }
            }
            synchronized (voices) {
                for (Iterator<Voice> it = voices.iterator(); it.hasNext();) {
                    Voice v = it.next();
                    int k = Math.min(n, v.data.length - v.pos);
                    for (int i = 0; i < k; i++) acc[i] += v.data[v.pos + i] * v.vol * 0.8f;
                    v.pos += k;
                    if (v.pos >= v.data.length) it.remove();
                }
            }
            for (int i = 0; i < n; i++) {
                // gentle soft clip so stacked effects never crack
                float x = acc[i] / 32768f;
                x = x / (1 + Math.abs(x) * 0.6f);
                int s = (int) (x * 32767);
                out[i * 2] = (byte) s;
                out[i * 2 + 1] = (byte) (s >> 8);
            }
            line.write(out, 0, out.length);
        }
    }

    // ------------------------------------------------------------------ synthesis

    private static final int SINE = 0, SQUARE = 1, SAW = 2, TRI = 3;
    private static long noiseSeed = 12345;

    private static float rnd() { noiseSeed = noiseSeed * 6364136223846793005L + 1442695040888963407L; return ((noiseSeed >>> 40) / (float) (1 << 24)) * 2 - 1; }

    private static double wave(int w, double ph) {
        double f = ph / (2 * Math.PI);
        f -= Math.floor(f);
        switch (w) {
            case SQUARE: return f < 0.5 ? 0.6 : -0.6;
            case SAW: return (f * 2 - 1) * 0.6;
            case TRI: return f < 0.5 ? f * 4 - 1 : 3 - f * 4;
            default: return Math.sin(ph);
        }
    }

    /** A tone gliding exponentially from f0 to f1 with a short attack and exponential decay. */
    private static void tone(float[] b, double t0, double dur, double f0, double f1, int w, double vol, double att, double decay) {
        int s0 = (int) (t0 * RATE), len = (int) (dur * RATE);
        double ph = 0;
        for (int i = 0; i < len && s0 + i < b.length; i++) {
            double t = i / (double) RATE, u = t / dur;
            double f = f0 * Math.pow(f1 / f0, u);
            ph += 2 * Math.PI * f / RATE;
            double env = (t < att ? t / att : Math.exp(-(t - att) * decay)) * Math.min(1, (dur - t) * 60);
            b[s0 + i] += (float) (wave(w, ph) * env * vol);
        }
    }

    /** Noise through a one-pole low-pass (lp: 0 = dark rumble .. 1 = bright hiss). */
    private static void noise(float[] b, double t0, double dur, double vol, double lp, double att, double decay) {
        int s0 = (int) (t0 * RATE), len = (int) (dur * RATE);
        double y = 0;
        for (int i = 0; i < len && s0 + i < b.length; i++) {
            double t = i / (double) RATE;
            y += (rnd() - y) * lp;
            double env = (t < att ? t / att : Math.exp(-(t - att) * decay)) * Math.min(1, (dur - t) * 60);
            b[s0 + i] += (float) (y * env * vol);
        }
    }

    private static short[] pcm(float[] b, double peak) {
        float m = 1e-6f;
        for (float v : b) m = Math.max(m, Math.abs(v));
        short[] s = new short[b.length];
        double k = peak * 32767 / m;
        for (int i = 0; i < b.length; i++) s[i] = (short) Math.max(-32767, Math.min(32767, b[i] * k));
        return s;
    }

    private static float[] buf(double sec) { return new float[(int) (sec * RATE)]; }

    private static double midi(double n) { return 440 * Math.pow(2, (n - 69) / 12.0); }

    private static void build() {
        sfx = new short[Sfx.values().length][];
        for (Sfx s : Sfx.values()) sfx[s.ordinal()] = pcm(effect(s), peak(s));
        tracks[MUSIC_DUEL] = track(140, false);
        tracks[MUSIC_RAGE] = track(165, true);
    }

    private static double peak(Sfx s) {
        switch (s) {
            case CLICK: case GO: return 0.35;
            case MINION: case DENS: case HISS: return 0.45;
            case SLAM: case CLASH: case PHASE2: case HEAVY_HIT: case LOSE: case WIN: return 0.95;
            case X_GLASS: case X_CRUCIO: case X_FIRE: case X_NAGINI: case X_SWALLOW: case TRUCK: return 0.95;
            case X_CHIP: return 0.5;
            default: return 0.7;
        }
    }

    private static float[] effect(Sfx s) {
        float[] b;
        switch (s) {
            case DENS:
                b = buf(0.14); tone(b, 0, 0.1, 880, 1500, SQUARE, 0.5, 0.003, 25); tone(b, 0.02, 0.1, 1760, 2200, SINE, 0.4, 0.002, 30); break;
            case EXP:
                b = buf(0.32); noise(b, 0, 0.05, 0.9, 0.9, 0.001, 60); tone(b, 0.01, 0.25, 500, 1600, SAW, 0.5, 0.005, 8); tone(b, 0.02, 0.25, 1100, 2600, SINE, 0.4, 0.005, 9); break;
            case STUP:
                b = buf(0.4);
                for (int i = 0; i < 6; i++) tone(b, i * 0.035, 0.05, 1400 - i * 120, 300, SQUARE, 0.35, 0.002, 20);
                tone(b, 0, 0.35, 180, 120, SQUARE, 0.35, 0.005, 6); noise(b, 0, 0.3, 0.4, 0.6, 0.002, 10); break;
            case SNAKE:
                b = buf(0.6); noise(b, 0, 0.55, 0.8, 0.95, 0.05, 4); tone(b, 0, 0.4, 140, 90, SINE, 0.4, 0.02, 5); break;
            case HISS:
                b = buf(0.5); noise(b, 0, 0.45, 0.7, 0.98, 0.08, 5); break;
            case STORED:
                b = buf(0.55); tone(b, 0, 0.5, 1320, 1320, SINE, 0.45, 0.003, 6); tone(b, 0.06, 0.45, 1760, 1760, SINE, 0.35, 0.003, 6); tone(b, 0.12, 0.4, 2640, 2640, SINE, 0.2, 0.003, 8); break;
            case IMPACT:
                b = buf(0.3); noise(b, 0, 0.15, 0.8, 0.35, 0.001, 22); tone(b, 0, 0.22, 170, 60, SINE, 0.8, 0.002, 12); tone(b, 0.01, 0.12, 2200, 1400, SINE, 0.2, 0.002, 25); break;
            case BOSS_HIT:
                b = buf(0.3); tone(b, 0, 0.25, 130, 50, SINE, 0.9, 0.002, 10); noise(b, 0, 0.1, 0.6, 0.4, 0.001, 30); break;
            case HEAVY_HIT:
                b = buf(0.5); tone(b, 0, 0.45, 95, 32, SINE, 1.0, 0.002, 6); tone(b, 0, 0.2, 62, 40, SQUARE, 0.4, 0.002, 12); noise(b, 0, 0.2, 0.8, 0.3, 0.001, 14); break;
            case MELEE:
                b = buf(0.22); noise(b, 0, 0.07, 1.0, 0.7, 0.001, 45); tone(b, 0, 0.15, 320, 90, SINE, 0.8, 0.002, 16); break;
            case SWISH: {
                b = buf(0.22);
                double y = 0;
                for (int i = 0; i < b.length; i++) {
                    double u = i / (double) b.length;
                    y += (rnd() - y) * (0.15 + 0.6 * Math.sin(Math.PI * u));
                    b[i] += (float) (y * Math.sin(Math.PI * u) * 0.8);
                }
                break;
            }
            case WHOOSH: {
                b = buf(0.32);
                double y = 0;
                for (int i = 0; i < b.length; i++) {
                    double u = i / (double) b.length;
                    y += (rnd() - y) * (0.05 + 0.35 * u);
                    b[i] += (float) (y * Math.sin(Math.PI * u) * 0.9);
                }
                break;
            }
            case SLAM:
                b = buf(0.8); tone(b, 0, 0.7, 75, 28, SINE, 1.0, 0.002, 4); noise(b, 0, 0.45, 0.9, 0.2, 0.001, 6); noise(b, 0, 0.08, 0.6, 0.8, 0.001, 40); break;
            case PLAYER_HIT:
                b = buf(0.5); tone(b, 0, 0.4, 420, 70, SQUARE, 0.6, 0.002, 6); noise(b, 0, 0.2, 0.8, 0.5, 0.001, 15); tone(b, 0, 0.45, 110, 40, SINE, 0.7, 0.002, 5); break;
            case LOSE:
                b = buf(1.9);
                double[] lose = { 62, 57, 53, 50 };
                for (int i = 0; i < lose.length; i++) { tone(b, i * 0.35, 0.8, midi(lose[i]), midi(lose[i]), TRI, 0.5, 0.01, 3); tone(b, i * 0.35, 0.8, midi(lose[i] - 12), midi(lose[i] - 12), SINE, 0.4, 0.01, 3); }
                break;
            case CANCEL:
                b = buf(0.4); tone(b, 0, 0.35, 1500, 1500, SINE, 0.5, 0.001, 9); tone(b, 0, 0.3, 1990, 1990, SINE, 0.4, 0.001, 11); noise(b, 0, 0.04, 0.6, 0.9, 0.001, 60); break;
            case TRUCK: {
                b = buf(0.7);
                // the body blow: a huge low thump
                tone(b, 0, 0.45, 95, 30, SINE, 1.0, 0.001, 7);
                tone(b, 0, 0.12, 180, 60, SQUARE, 0.5, 0.001, 22);
                // the steel panel: inharmonic partials that ring and fade, like a struck sheet of metal
                double[] f = { 211, 347, 529, 788, 1163, 1617, 2290 };
                for (int i = 0; i < f.length; i++) tone(b, 0, 0.55, f[i], f[i] * 0.985, SINE, 0.34 - i * 0.03, 0.001, 5 + i * 1.6);
                // the crunch of the dent
                noise(b, 0, 0.09, 1.0, 0.55, 0.001, 30);
                noise(b, 0.01, 0.25, 0.5, 0.2, 0.002, 10);
                break;
            }
            case X_CHIP:
                // a crack in the projectile: a short tick and a ping
                b = buf(0.18); noise(b, 0, 0.03, 0.8, 0.9, 0.001, 70); tone(b, 0, 0.14, 2600, 2400, SINE, 0.4, 0.001, 22); break;
            case X_GLASS: {
                // glass blown to splinters: a bright burst, then a rain of tinkling shards
                b = buf(0.9);
                noise(b, 0, 0.09, 1.0, 0.95, 0.001, 35); tone(b, 0, 0.18, 220, 90, SINE, 0.6, 0.001, 18);
                double[] f = { 3400, 4300, 2900, 5100, 3800, 4700, 3100, 5600, 4000 };
                for (int i = 0; i < f.length; i++) tone(b, 0.02 + i * 0.045 + (i % 3) * 0.01, 0.22, f[i], f[i] * 0.97, SINE, 0.32 - i * 0.02, 0.001, 18);
                break;
            }
            case X_BOLT:
                // a green bolt shorted out: a falling electric zap and a soft pop
                b = buf(0.45); tone(b, 0, 0.25, 1900, 160, SQUARE, 0.45, 0.001, 9); tone(b, 0.01, 0.2, 2400, 300, SAW, 0.25, 0.001, 12);
                noise(b, 0, 0.12, 0.7, 0.7, 0.001, 25); tone(b, 0, 0.25, 140, 60, SINE, 0.7, 0.001, 14); break;
            case X_CRUCIO:
                // the curse burst open by Stupefy: a deep boom under crackling blue lightning
                b = buf(0.8); tone(b, 0, 0.6, 110, 34, SINE, 1.0, 0.001, 6); tone(b, 0, 0.35, 420, 90, SAW, 0.4, 0.001, 9);
                for (int i = 0; i < 7; i++) tone(b, 0.02 + i * 0.04, 0.03, 2200 - i * 200, 900, SQUARE, 0.3, 0.001, 50);
                noise(b, 0, 0.3, 0.6, 0.5, 0.001, 9); break;
            case X_REFLECT:
                // a curse bounced off the shield meets another: a bell chime and a pop
                b = buf(0.7); tone(b, 0, 0.6, 1046, 1046, SINE, 0.45, 0.001, 5); tone(b, 0, 0.55, 1568, 1568, SINE, 0.35, 0.001, 6);
                tone(b, 0, 0.5, 2093, 2093, TRI, 0.2, 0.001, 8); tone(b, 0, 0.2, 260, 80, SINE, 0.7, 0.001, 14); noise(b, 0, 0.05, 0.6, 0.8, 0.001, 50); break;
            case X_FIRE:
                // the fire serpent blown apart: a roaring whoomph and a long sizzle
                b = buf(1.0); noise(b, 0, 0.5, 1.0, 0.1, 0.005, 5); tone(b, 0, 0.5, 90, 40, SINE, 0.9, 0.002, 6);
                noise(b, 0.05, 0.9, 0.45, 0.97, 0.05, 4); tone(b, 0, 0.3, 600, 150, SAW, 0.25, 0.002, 10); break;
            case X_SWALLOW: {
                // the snake gulps the whole ring of fire: a rising suck, a gulp, a satisfied hiss
                b = buf(0.9);
                double y = 0;
                for (int i = 0; i < (int) (0.35 * RATE); i++) {
                    double u = i / (0.35 * RATE);
                    y += (rnd() - y) * (0.05 + 0.6 * u);
                    b[i] += (float) (y * u * 0.9);
                }
                tone(b, 0.33, 0.25, 340, 70, SINE, 1.0, 0.002, 10); tone(b, 0.33, 0.2, 170, 60, TRI, 0.5, 0.002, 12);
                noise(b, 0.5, 0.4, 0.4, 0.98, 0.05, 6); break;
            }
            case X_NAGINI:
                // snake against snake: a hiss, a heavy body blow, the snap of jaws
                b = buf(0.9); noise(b, 0, 0.25, 0.7, 0.98, 0.01, 8); tone(b, 0.05, 0.55, 80, 30, SINE, 1.0, 0.002, 6);
                noise(b, 0.05, 0.3, 0.9, 0.25, 0.001, 10); noise(b, 0.12, 0.03, 0.9, 0.9, 0.001, 80); tone(b, 0.12, 0.08, 900, 400, SQUARE, 0.3, 0.001, 40); break;
            case X_INFERI:
                // the corpse shattered: wet crunching bones and a dull thud
                b = buf(0.6);
                for (int i = 0; i < 5; i++) noise(b, i * 0.035, 0.03, 0.9, 0.55 + (i % 2) * 0.3, 0.001, 70);
                tone(b, 0, 0.3, 150, 55, SINE, 0.8, 0.002, 10); noise(b, 0.05, 0.3, 0.4, 0.15, 0.01, 9); break;
            case CLASH:
                b = buf(1.0); tone(b, 0, 0.9, 220, 210, SAW, 0.35, 0.002, 3); tone(b, 0, 0.9, 330, 320, SAW, 0.3, 0.002, 3);
                tone(b, 0, 0.8, 1500, 1480, SINE, 0.4, 0.001, 4); tone(b, 0, 0.7, 2250, 2230, SINE, 0.3, 0.001, 5); noise(b, 0, 0.15, 0.9, 0.7, 0.001, 18); break;
            case BLOCK:
                b = buf(0.45); tone(b, 0, 0.4, 900, 900, SINE, 0.5, 0.001, 8); tone(b, 0, 0.4, 1350, 1350, SINE, 0.35, 0.001, 9); tone(b, 0, 0.3, 2700, 2700, TRI, 0.2, 0.001, 14); break;
            case APPARATE:
                b = buf(0.3); noise(b, 0, 0.06, 1.0, 0.9, 0.001, 50); tone(b, 0, 0.18, 700, 140, SINE, 0.7, 0.002, 14); break;
            case BULLET_TIME:
                b = buf(0.9); tone(b, 0, 0.8, 520, 70, SINE, 0.6, 0.01, 3); noise(b, 0, 0.6, 0.5, 0.15, 0.3, 4);
                for (int i = 0; i < 3; i++) tone(b, 0.25 + i * 0.18, 0.03, 2400, 2400, SQUARE, 0.25, 0.001, 80);
                break;
            case GO:
                b = buf(0.18); tone(b, 0, 0.15, 300, 900, SINE, 0.6, 0.003, 10); break;
            case CLICK:
                b = buf(0.04); tone(b, 0, 0.03, 1300, 1100, SQUARE, 0.5, 0.001, 90); break;
            case ERROR:
                b = buf(0.28); tone(b, 0, 0.1, 150, 140, SQUARE, 0.5, 0.002, 10); tone(b, 0.13, 0.1, 140, 130, SQUARE, 0.5, 0.002, 10); break;
            case BOSS_CAST:
                b = buf(0.9); tone(b, 0, 0.85, 55, 52, SAW, 0.45, 0.05, 2.5); tone(b, 0, 0.85, 82, 78, SAW, 0.35, 0.05, 2.5);
                tone(b, 0, 0.5, 240, 110, SINE, 0.35, 0.01, 4); noise(b, 0, 0.7, 0.4, 0.08, 0.3, 4); break;
            case AVADA:
                b = buf(0.6); tone(b, 0, 0.45, 950, 180, SAW, 0.55, 0.002, 5); tone(b, 0, 0.5, 1900, 380, SINE, 0.3, 0.002, 5); noise(b, 0, 0.4, 0.7, 0.6, 0.002, 7); break;
            case FIRE:
                b = buf(0.8); noise(b, 0, 0.75, 1.0, 0.12, 0.08, 3); tone(b, 0, 0.7, 72, 60, SAW, 0.4, 0.05, 3); break;
            case THUNDER:
                b = buf(0.7); noise(b, 0, 0.08, 1.0, 0.95, 0.001, 30); noise(b, 0.04, 0.6, 0.8, 0.1, 0.01, 5); tone(b, 0, 0.5, 85, 38, SINE, 0.6, 0.002, 5); break;
            case GLASS: {
                b = buf(0.6);
                double[] f = { 2300, 3100, 2700, 3700, 2500, 4100 };
                for (int i = 0; i < f.length; i++) tone(b, i * 0.05, 0.25, f[i], f[i] * 0.98, SINE, 0.35, 0.001, 16);
                noise(b, 0, 0.05, 0.4, 0.95, 0.001, 50);
                break;
            }
            case MINION:
                b = buf(0.3); tone(b, 0, 0.25, 300, 900, SQUARE, 0.35, 0.02, 6); noise(b, 0, 0.2, 0.3, 0.7, 0.05, 8); break;
            case WIN: {
                b = buf(2.2);
                double[] up = { 62, 66, 69, 74 };
                for (int i = 0; i < up.length; i++) tone(b, i * 0.16, 0.5, midi(up[i]), midi(up[i]), TRI, 0.45, 0.005, 4);
                for (double n : new double[] { 62, 66, 69, 74, 78 }) tone(b, 0.7, 1.4, midi(n), midi(n), SINE, 0.3, 0.02, 1.8);
                tone(b, 0.7, 0.8, 70, 45, SINE, 0.6, 0.002, 4);
                break;
            }
            case PHASE2:
                b = buf(2.0); for (double f : new double[] { 98, 147, 196, 233 }) tone(b, 0, 1.9, f, f * 0.99, SINE, 0.35, 0.005, 1.6);
                noise(b, 0, 0.25, 0.7, 0.3, 0.001, 8); break;
            case INTERRUPT:
                b = buf(0.4); tone(b, 0, 0.3, 600, 1300, SQUARE, 0.4, 0.003, 6); tone(b, 0.05, 0.3, 900, 1800, SINE, 0.4, 0.003, 6); break;
            case STUN: {
                b = buf(0.6);
                int len = b.length;
                double ph = 0;
                for (int i = 0; i < len; i++) {
                    double t = i / (double) RATE, f = 1200 * Math.pow(0.5, t / 0.6) * (1 + 0.08 * Math.sin(t * 40));
                    ph += 2 * Math.PI * f / RATE;
                    b[i] += (float) (Math.sin(ph) * Math.exp(-t * 4) * 0.5);
                }
                break;
            }
            default: b = buf(0.05);
        }
        return b;
    }

    // ------------------------------------------------------------------ the background music (an original battle theme)

    /** Chords of the loop, as MIDI notes: Dm Bb F C | Dm Bb C A, played twice. */
    private static final int[][] CHORDS = {
        { 50, 53, 57 }, { 46, 50, 53 }, { 41, 45, 48 }, { 48, 52, 55 },
        { 50, 53, 57 }, { 46, 50, 53 }, { 48, 52, 55 }, { 45, 49, 52 },
    };

    /** The heroic lead over bars 5-8 and 13-16: {bar, beat, midi, beats}. */
    private static final double[][] MELODY = {
        { 4, 0, 74, 1.5 }, { 4, 1.5, 76, 0.5 }, { 4, 2, 77, 1 }, { 4, 3, 81, 1 },
        { 5, 0, 79, 2 }, { 5, 2, 77, 1 }, { 5, 3, 74, 1 },
        { 6, 0, 76, 1.5 }, { 6, 1.5, 77, 0.5 }, { 6, 2, 79, 1 }, { 6, 3, 76, 1 },
        { 7, 0, 73, 2 }, { 7, 2, 76, 1 }, { 7, 3, 81, 1 },
        { 12, 0, 81, 1 }, { 12, 1, 79, 0.5 }, { 12, 1.5, 77, 0.5 }, { 12, 2, 76, 1 }, { 12, 3, 74, 1 },
        { 13, 0, 77, 1 }, { 13, 1, 79, 1 }, { 13, 2, 81, 2 },
        { 14, 0, 79, 1 }, { 14, 1, 76, 1 }, { 14, 2, 72, 1 }, { 14, 3, 76, 1 },
        { 15, 0, 73, 3 }, { 15, 3, 69, 1 },
    };

    private static void kick(float[] b, double t, double vol) {
        tone(b, t, 0.28, 130, 42, SINE, vol, 0.001, 11);
        noise(b, t, 0.012, vol * 0.5, 0.9, 0.0005, 200);
    }

    private static void snare(float[] b, double t, double vol) {
        noise(b, t, 0.2, vol, 0.65, 0.001, 22);
        tone(b, t, 0.1, 240, 170, TRI, vol * 0.6, 0.001, 30);
    }

    private static void hat(float[] b, double t, double vol) { noise(b, t, 0.035, vol, 0.99, 0.0005, 110); }

    private static void tom(float[] b, double t, double f, double vol) { tone(b, t, 0.22, f, f * 0.6, SINE, vol, 0.001, 12); }

    /** A brass-like stab: saw and square stacked, a quick swell and a punchy fall. */
    private static void stab(float[] b, double t, double dur, int[] chord, double vol) {
        for (int n : chord) {
            double f = midi(n + 12);
            tone(b, t, dur, f, f, SAW, vol, 0.012, 5);
            tone(b, t, dur, f * 1.003, f * 1.003, SQUARE, vol * 0.5, 0.012, 5);
        }
    }

    private static short[] track(double bpm, boolean rage) {
        double beat = 60 / bpm, bar = beat * 4, e8 = beat / 2, e16 = beat / 4;
        int bars = 16;
        float[] b = new float[(int) (bars * bar * RATE) + RATE / 2];
        for (int k = 0; k < bars; k++) {
            int[] ch = CHORDS[k % 8];
            double t = k * bar;
            // choir-like pad underneath everything
            for (int n : ch) { tone(b, t, bar, midi(n + 12), midi(n + 12), SINE, 0.035, 0.2, 0.4); tone(b, t, bar, midi(n + 24), midi(n + 24), SINE, 0.015, 0.3, 0.6); }
            // driving bass: root eighths, jumping the octave on the off-beats
            double root = midi(ch[0] - 12);
            for (int e = 0; e < 8; e++) {
                double f = e % 2 == 1 && (e == 3 || e == 7) ? root * 2 : root;
                tone(b, t + e * e8, e8 * 0.9, f, f, SAW, 0.16, 0.003, 7);
                tone(b, t + e * e8, e8 * 0.9, f / 2, f / 2, SINE, 0.18, 0.003, 6);
            }
            // string ostinato: sixteenths turning around the chord
            int[] pat = { 0, 2, 1, 2, 0, 2, 1, 2, 0, 2, 1, 2, 0, 1, 2, 1 };
            for (int e = 0; e < 16; e++) {
                double f = midi(ch[pat[e] % ch.length] + 12 + (pat[e] == 2 ? 12 : 0));
                tone(b, t + e * e16, e16 * 0.85, f, f, SAW, rage ? 0.075 : 0.06, 0.002, 18);
            }
            // brass stabs on the downbeat (and the "and" of 3 when enraged)
            stab(b, t, beat * 0.9, ch, 0.05);
            if (rage) stab(b, t + beat * 2.5, beat * 0.6, ch, 0.04);
            // drums
            double[] kicks = rage ? new double[] { 0, 1, 1.5, 2, 3, 3.5 } : new double[] { 0, 1.5, 2, 3.5 };
            for (double kb : kicks) kick(b, t + kb * beat, rage ? 0.55 : 0.5);
            snare(b, t + beat, 0.3); snare(b, t + beat * 3, 0.3);
            int hats = rage ? 16 : 8;
            for (int e = 0; e < hats; e++) hat(b, t + e * bar / hats, (e % 2 == 0 ? 0.09 : 0.05));
            // a tom fill closes every fourth bar
            if (k % 4 == 3) for (int e = 0; e < 4; e++) tom(b, t + beat * 3 + e * e16, 220 - e * 35, 0.32);
            // a big crash at the top of each eight-bar phrase
            if (k % 8 == 0) noise(b, t, 1.2, 0.18, 0.95, 0.002, 3);
        }
        // the lead: bright saw-and-square with vibrato, an octave higher when enraged
        int up = rage ? 12 : 0;
        for (double[] m : MELODY) {
            double t0 = m[0] * bar + m[1] * beat, dur = m[3] * beat * 0.95, f = midi(m[2] + up);
            int s0 = (int) (t0 * RATE), len = (int) (dur * RATE);
            double ph = 0;
            for (int i = 0; i < len && s0 + i < b.length; i++) {
                double tt = i / (double) RATE;
                double vib = tt > 0.12 ? 0.008 * Math.sin(tt * 36) : 0;
                ph += 2 * Math.PI * f * (1 + vib) / RATE;
                double env = Math.min(1, tt / 0.015) * Math.min(1, (dur - tt) / 0.04) * (0.75 + 0.25 * Math.exp(-tt * 4));
                b[s0 + i] += (float) ((wave(SAW, ph) * 0.6 + wave(SQUARE, ph) * 0.3 + Math.sin(ph * 2) * 0.15) * env * 0.11);
            }
        }
        // enraged: a rising sweep into the loop point
        if (rage) {
            double t0 = (bars - 1) * bar + beat * 2;
            tone(b, t0, beat * 2, 200, 1600, SAW, 0.05, 0.3, 0.1);
            noise(b, t0, beat * 2, 0.1, 0.9, beat * 2, 0.01);
        }
        float[] loop = Arrays.copyOf(b, (int) (bars * bar * RATE));
        // fold the overhanging tails back onto the start so the loop is seamless
        for (int i = loop.length; i < b.length; i++) loop[i - loop.length] += b[i];
        // glue the mix: a soft saturating compressor tames the drum peaks so the whole band hits harder
        float m = 1e-6f;
        for (float v : loop) m = Math.max(m, Math.abs(v));
        double drive = 3.0, norm = Math.tanh(drive);
        for (int i = 0; i < loop.length; i++) loop[i] = (float) (Math.tanh(loop[i] / m * drive) / norm);
        return pcm(loop, 0.8);
    }
}
