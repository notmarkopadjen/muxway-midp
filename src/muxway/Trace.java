package muxway;

/**
 * Writes what the app puts on screen to standard output, for tests in an
 * emulator. Off unless the JAD sets Muxway-Trace: 1; on a phone standard
 * output goes nowhere anyway.
 */
final class Trace {
    static boolean on;

    private Trace() {
    }

    static void show(String s) {
        if (on) {
            System.out.println("SHOW " + s);
        }
    }
}
