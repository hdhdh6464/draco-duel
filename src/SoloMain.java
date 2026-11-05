/** The edition for sharing: only the duel against Voldemort, plus the beginner's tutorial. */
public final class SoloMain {
    public static void main(String[] args) {
        Game.soloOnly = true;
        Main.main(args);
    }
}
