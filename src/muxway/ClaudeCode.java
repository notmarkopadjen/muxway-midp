package muxway;

/**
 * Claude Code keeps its input box between two rules, with a status line
 * above and a footer below. The rules are noise on a phone, status and
 * footer wait until asked for, and the box stays at the bottom.
 */
final class ClaudeCode implements Lens {
    public String name() {
        return "Claude Code";
    }

    public String[][] shape(String[] rows, boolean reveal, boolean forced) {
        int below = -1, above = -1;
        for (int i = rows.length - 1; i >= 0 && above < 0; i--) {
            if (rule(rows[i])) {
                if (below < 0) {
                    below = i;
                } else {
                    above = i;
                }
            }
        }
        if (above < 0) {
            return forced ? prompt(rows, reveal) : null;
        }
        // The status is right-aligned, so it starts with spaces.
        int status = above > 0 && rows[above - 1].startsWith("        ") ? above - 1 : above;
        int end = status;
        while (end > 0 && rows[end - 1].length() == 0) {
            end--;
        }
        String[] body = new String[end];
        System.arraycopy(rows, 0, body, 0, end);
        int from = reveal ? status : above + 1;
        int to = reveal ? rows.length : below;
        int n = 0;
        for (int i = from; i < to; i++) {
            if (!rule(rows[i])) {
                n++;
            }
        }
        String[] pinned = new String[n];
        n = 0;
        for (int i = from; i < to; i++) {
            if (!rule(rows[i])) {
                // The status loses its right alignment: on a phone that is rows of blanks.
                pinned[n++] = i == status && status < above ? rows[i].trim() : rows[i];
            }
        }
        return new String[][] {body, pinned};
    }

    /** Without rules, the last prompt row near the bottom is the box and what follows it is footer. */
    private static String[][] prompt(String[] rows, boolean reveal) {
        int box = -1;
        for (int i = rows.length - 1; i >= 0 && i >= rows.length - 8 && box < 0; i--) {
            if (rows[i].startsWith("❯") || rows[i].startsWith("> ") || rows[i].equals(">")) {
                box = i;
            }
        }
        if (box < 0) {
            return null;
        }
        int end = box;
        while (end > 0 && rows[end - 1].length() == 0) {
            end--;
        }
        String[] body = new String[end];
        System.arraycopy(rows, 0, body, 0, end);
        String[] pinned = new String[reveal ? rows.length - box : 1];
        System.arraycopy(rows, box, pinned, 0, pinned.length);
        return new String[][] {body, pinned};
    }

    /** A row of nothing but horizontal lines: three or more line characters, or twenty dashes once the hub spelt them in ASCII. */
    static boolean rule(String s) {
        int lines = 0, dashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '─' || c == '━' || c == '═') {
                lines++;
            } else if (c == '-' || c == '=') {
                dashes++;
            } else if (c != ' ') {
                return false;
            }
        }
        return lines >= 3 || (lines == 0 && dashes >= 20);
    }
}
