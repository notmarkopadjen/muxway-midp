package muxway;

import java.util.Vector;

/**
 * Claude Code ends its screen with chrome: a summary of the last turn,
 * notices on the right, a rule that may carry the session name, the
 * input box, a rule and a footer. On a phone only the conversation is
 * wanted: the chrome waits until asked for, and a box with text in it
 * stays at the bottom.
 */
final class ClaudeCode implements Lens {
    private static final String MARKS = "\u273b\u273d\u2736\u2722\u2726\u2727\u00b7*";

    public String name() {
        return "Claude Code";
    }

    public boolean runs(String command) {
        return command.equals("claude");
    }

    public String[][] shape(String[] rows, boolean reveal, boolean forced) {
        int below = -1, above = -1;
        for (int i = rows.length - 1; i >= 0 && above < 0; i--) {
            // The bottom rule is clean; the one above it may carry the session name.
            if (rule(rows[i], below >= 0)) {
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
        int first = chrome(rows, above);
        if (reveal) {
            return new String[][] {revealed(rows, first, above, below), new String[0]};
        }
        String[] body = transcript(rows, first);
        String[] pinned = new String[empty(rows, above + 1, below) ? 0 : below - above - 1];
        System.arraycopy(rows, above + 1, pinned, 0, pinned.length);
        return new String[][] {body, pinned};
    }

    /** Without rules, the last prompt row near the bottom is the box and what follows it is footer. */
    private static String[][] prompt(String[] rows, boolean reveal) {
        int box = -1;
        for (int i = rows.length - 1; i >= 0 && i >= rows.length - 8 && box < 0; i--) {
            if (rows[i].startsWith("\u276f") || rows[i].startsWith("> ") || rows[i].equals(">")) {
                box = i;
            }
        }
        if (box < 0) {
            return null;
        }
        int first = chrome(rows, box);
        if (reveal) {
            return new String[][] {revealed(rows, first, -1, -1), new String[0]};
        }
        String[] pinned = empty(rows, box, box + 1) ? new String[0] : new String[] {rows[box]};
        return new String[][] {transcript(rows, first), pinned};
    }

    /** Where the chrome starts above row at: blank rows and right-aligned notices, then the summary of the last turn. */
    private static int chrome(String[] rows, int at) {
        int first = at;
        while (first > 0 && (rows[first - 1].length() == 0 || rows[first - 1].startsWith("        "))) {
            first--;
        }
        if (first > 0 && summary(rows[first - 1])) {
            first--;
        }
        return first;
    }

    /** The rows before the chrome, without the blank rows Claude fills a tall terminal with. */
    private static String[] transcript(String[] rows, int first) {
        int end = first;
        while (end > 0 && rows[end - 1].length() == 0) {
            end--;
        }
        String[] body = new String[end];
        System.arraycopy(rows, 0, body, 0, end);
        return body;
    }

    /** Everything in place but the rules (-1 for none) and the blank rows around the chrome; notices lose their alignment. */
    private static String[] revealed(String[] rows, int first, int above, int below) {
        int end = first;
        while (end > 0 && rows[end - 1].length() == 0) {
            end--;
        }
        Vector out = new Vector();
        for (int i = 0; i < rows.length; i++) {
            if (i == above || i == below || (i >= end && rows[i].length() == 0)) {
                continue;
            }
            out.addElement(i >= first && rows[i].startsWith("        ") ? rows[i].trim() : rows[i]);
        }
        String[] a = new String[out.size()];
        out.copyInto(a);
        return a;
    }

    /** True when the box rows hold nothing but the prompt mark. */
    private static boolean empty(String[] rows, int from, int to) {
        for (int i = from; i < to; i++) {
            String r = rows[i].trim();
            if (r.startsWith("\u276f") || r.startsWith(">")) {
                r = r.substring(1).trim();
            }
            if (r.length() > 0) {
                return false;
            }
        }
        return true;
    }

    /** The summary of the last turn: a spinner mark, then a duration such as 58s or 5m 27s. */
    static boolean summary(String s) {
        if (s.length() < 3 || s.charAt(1) != ' ' || MARKS.indexOf(s.charAt(0)) < 0) {
            return false;
        }
        for (int i = 2; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                int j = i;
                while (j < s.length() && Character.isDigit(s.charAt(j))) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 's' || s.charAt(j) == 'm')
                    && (j + 1 == s.length() || !Character.isLowerCase(s.charAt(j + 1)))) {
                    return true;
                }
                i = j;
            }
        }
        return false;
    }

    static boolean rule(String s) {
        return rule(s, false);
    }

    /**
     * A row of horizontal lines: three or more line characters, or twenty
     * dashes once the hub spelt them in ASCII. Loose, text up to half as
     * long as the line is allowed, which is how Claude writes the session
     * name into its top rule, on narrow terminals too.
     */
    static boolean rule(String s, boolean loose) {
        int lines = 0, dashes = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u2500' || c == '\u2501' || c == '\u2550') {
                lines++;
            } else if (c == '-' || c == '=') {
                dashes++;
            } else if (c != ' ') {
                other++;
            }
        }
        if (other > 0 && (!loose || other * 2 > lines + dashes)) {
            return false;
        }
        return lines >= 3 || (lines == 0 && dashes >= 20);
    }
}
