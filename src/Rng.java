/** Tiny copyable PRNG so a world snapshot replays Voldemort's choices exactly. */
final class Rng {
    private long s;

    Rng(long seed) { s = seed ^ 0x5DEECE66DL; }
    Rng(Rng o) { s = o.s; }

    private long next() {
        s = s * 6364136223846793005L + 1442695040888963407L;
        return s;
    }

    int nextInt(int n) { return (int) ((next() >>> 33) % n); }
    double nextDouble() { return (next() >>> 11) * 0x1.0p-53; }
}
