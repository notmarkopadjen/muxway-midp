package muxway;

/** String helpers CLDC 1.0 does not have. */
final class Text {
    private Text() {
    }

    static String[] split(String s, char at) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == at) {
                n++;
            }
        }
        String[] out = new String[n];
        int from = 0;
        for (int i = 0; i < n; i++) {
            int to = s.indexOf(at, from);
            if (to < 0) {
                to = s.length();
            }
            out[i] = s.substring(from, to);
            from = to + 1;
        }
        return out;
    }

    static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Keeps printable characters, newlines and tabs (list columns). */
    static String screen(String s) {
        StringBuffer out = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f && (c < 0x80 || c > 0x9f))) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Drops control characters (C0, DEL, C1); the hub refuses them in input. */
    static String printable(String s) {
        StringBuffer out = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f && (c < 0x80 || c > 0x9f)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
