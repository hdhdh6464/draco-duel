import java.lang.reflect.Field;
import java.lang.reflect.Method;
public class Loudness {
    public static void main(String[] a) throws Exception {
        Method build = Audio.class.getDeclaredMethod("build"); build.setAccessible(true); build.invoke(null);
        Field ft = Audio.class.getDeclaredField("tracks"); ft.setAccessible(true);
        short[][] tr = (short[][]) ft.get(null);
        for (int k = 1; k <= 2; k++) {
            short[] d = tr[k];
            double sum = 0; int peak = 0;
            for (short v : d) { sum += (double) v * v; peak = Math.max(peak, Math.abs(v)); }
            double rms = Math.sqrt(sum / d.length);
            // crest factor: how much the peaks (drum hits) stick out above the body of the mix
            System.out.printf("%s: rms %.0f (%.1f%% of full scale), peak %d, crest %.1f dB%n", k == 1 ? "duel" : "rage", rms, rms / 327.67, peak, 20 * Math.log10(peak / rms));
        }
    }
}
