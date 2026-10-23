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
            StringBuffer line = new StringBuffer("SHOW ");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                line.append(c == '\n' ? '|' : c);
            }
            System.out.println(line.toString());
        }
    }
}
