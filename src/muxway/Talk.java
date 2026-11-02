package muxway;

import java.util.Vector;

/**
 * Who talked with whom, from the listing's talk column: panes that wrote
 * or mailed to each other lately form a group, partners of partners
 * included, and the list keeps a group's rows together behind an edge.
 * MIDP 2.0 build only: Ui.TALK is false in the 1.0 build and ProGuard
 * drops this class.
 */
final class Talk {
    private Talk() {
    }

    /**
     * The group of each row, numbered from 0 in listing order, or -1 for a
     * row that talked with nobody the list knows. partners holds each row's
     * column: addresses joined by |.
     */
    static int[] groups(Vector addresses, Vector partners) {
        int n = addresses.size();
        int[] root = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = i;
        }
        for (int i = 0; i < n; i++) {
            String[] named = Text.split((String) partners.elementAt(i), '|');
            for (int j = 0; j < named.length; j++) {
                int k = addresses.indexOf(named[j].trim());
                if (k >= 0 && k != i) {
                    root[find(root, k)] = find(root, i);
                }
            }
        }
        int[] group = new int[n];
        int[] number = new int[n];
        int[] size = new int[n];
        for (int i = 0; i < n; i++) {
            size[find(root, i)]++;
        }
        int next = 0;
        for (int i = 0; i < n; i++) {
            int r = find(root, i);
            if (size[r] < 2) {
                group[i] = -1;
            } else {
                if (number[r] == 0) {
                    number[r] = ++next;
                }
                group[i] = number[r] - 1;
            }
        }
        return group;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            i = root[i];
        }
        return i;
    }

    /** True when any row is in a group. */
    static boolean any(int[] group) {
        for (int i = 0; i < group.length; i++) {
            if (group[i] >= 0) {
                return true;
            }
        }
        return false;
    }

    /** The rows in the order shown: listing order, a group's rows one after another behind its first. */
    static int[] order(int[] group) {
        int n = group.length;
        int[] out = new int[n];
        boolean[] done = new boolean[n];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (done[i]) {
                continue;
            }
            out[p++] = i;
            done[i] = true;
            if (group[i] >= 0) {
                for (int j = i + 1; j < n; j++) {
                    if (group[j] == group[i]) {
                        out[p++] = j;
                        done[j] = true;
                    }
                }
            }
        }
        return out;
    }

    /** The frame's edge at a position of the order: [ a group's first row, ] its last, | between, a space outside. */
    static char edge(int[] group, int[] order, int p) {
        int g = group[order[p]];
        if (g < 0) {
            return ' ';
        }
        boolean first = p == 0 || group[order[p - 1]] != g;
        boolean last = p == order.length - 1 || group[order[p + 1]] != g;
        return first ? '[' : last ? ']' : '|';
    }
}
