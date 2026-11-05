import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Synthesizes everything, checks levels, opens the real device, and writes a few WAVs to listen to. */
public class AudioTest {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        long t0 = System.nanoTime();
        Method build = Audio.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(null);
        double ms = (System.nanoTime() - t0) / 1e6;
        Field fs = Audio.class.getDeclaredField("sfx"); fs.setAccessible(true);
        Field ft = Audio.class.getDeclaredField("tracks"); ft.setAccessible(true);
        short[][] sfx = (short[][]) fs.get(null), tracks = (short[][]) ft.get(null);
        int silent = 0, clipped = 0;
        StringBuilder sb = new StringBuilder();
        for (Audio.Sfx s : Audio.Sfx.values()) {
            short[] d = sfx[s.ordinal()];
            int peak = 0; for (short v : d) peak = Math.max(peak, Math.abs(v));
            if (peak < 2000) silent++;
            if (peak >= 32767) clipped++;
            sb.append(String.format("%s %.2fs  ", s, d.length / (double) Audio.RATE));
        }
        System.out.println(sb);
        System.out.printf("built %d effects + 2 tracks in %.0f ms; duel loop %.1fs, rage loop %.1fs; silent=%d clipped=%d%n",
                sfx.length, ms, tracks[1].length / (double) Audio.RATE, tracks[2].length / (double) Audio.RATE, silent, clipped);
        wav(tracks[1], out + "/bgm_duel.wav");
        wav(tracks[2], out + "/bgm_rage.wav");
        for (Audio.Sfx s : new Audio.Sfx[] { Audio.Sfx.EXP, Audio.Sfx.STUP, Audio.Sfx.CLASH, Audio.Sfx.SLAM, Audio.Sfx.AVADA, Audio.Sfx.BULLET_TIME })
            wav(sfx[s.ordinal()], out + "/sfx_" + s.name().toLowerCase() + ".wav");
        // the real output device
        Audio.start();
        Thread.sleep(2500);
        Field fr = Audio.class.getDeclaredField("ready"); fr.setAccessible(true);
        boolean ready = (Boolean) fr.get(null);
        Audio.play(Audio.Sfx.CLASH);
        Thread.sleep(600);
        System.out.println("audio device opened and mixing: " + ready);
        System.exit(0);
    }

    static void wav(short[] d, String path) throws Exception {
        byte[] b = new byte[d.length * 2];
        for (int i = 0; i < d.length; i++) { b[i * 2] = (byte) d[i]; b[i * 2 + 1] = (byte) (d[i] >> 8); }
        AudioFormat f = new AudioFormat(Audio.RATE, 16, 1, true, false);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(b), f, d.length), AudioFileFormat.Type.WAVE, new File(path));
    }
}
