import java.util.prefs.Preferences;

/** The game's two languages, Traditional Chinese and English. The choice is remembered between runs. */
final class Lang {
    private Lang() {}

    static boolean en = load();

    /** The text for the current language. */
    static String t(String zh, String en) { return Lang.en ? en : zh; }

    static String windowTitle() { return t("時光器對決 — 馬爾福 vs 佛地魔", "Duel of the Time-Turner — Malfoy vs Voldemort"); }

    static void set(boolean english) {
        en = english;
        try { Preferences.userRoot().node("draco-duel").putBoolean("english", english); } catch (Throwable ignored) { }
    }

    private static boolean load() {
        // -Dlang=zh or -Dlang=en overrides the saved choice (the tests run in Chinese)
        String forced = System.getProperty("lang");
        if (forced != null) return forced.equals("en");
        try { return Preferences.userRoot().node("draco-duel").getBoolean("english", false); } catch (Throwable e) { return false; }
    }
}
