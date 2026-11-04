package muxway;

import java.util.Vector;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/**
 * The hosts the token reads, from the hosts call in text mode: name, state
 * and the flags column, words joined by |. Also what each row of the pane
 * list is, and the box a session is named in. MIDP 2.0 build only:
 * Ui.HOSTS is false in the 1.0 build and ProGuard drops this class.
 */
final class Hosts {
    final Vector names = new Vector();
    final Vector online = new Vector();
    final Vector flags = new Vector();
    // What each list row is: a pane's index, or -1 minus the host's for a host line.
    final Vector rows = new Vector();
    final TextBox box = new TextBox("Session", "", 32, TextField.ANY);
    final Command start = new Command("Start", Command.OK, 1);
    // The host a session is started on.
    String session = "";

    /** Reads the hosts call's rows; a row needs a host name and a state, the rest may be missing. */
    void read(String body) {
        names.removeAllElements();
        online.removeAllElements();
        flags.removeAllElements();
        rows.removeAllElements();
        if (body == null) {
            return;
        }
        String[] lines = Text.split(body, '\n');
        for (int i = 0; i < lines.length; i++) {
            String[] f = Text.split(lines[i], '\t');
            if (f.length >= 2 && Text.address(f[0] + "/x") && names.indexOf(f[0]) < 0) {
                names.addElement(f[0]);
                online.addElement(new Boolean(f[1].trim().equals("online")));
                flags.addElement(f.length > 2 ? f[2] : "");
            }
        }
    }

    /** A host the rows name that the call did not: listed, online, without flags. */
    void add(String host) {
        if (names.indexOf(host) < 0) {
            names.addElement(host);
            online.addElement(new Boolean(true));
            flags.addElement("");
        }
    }

    /** True when the host's flags column carries the word. */
    boolean flag(int h, String word) {
        String[] words = Text.split((String) flags.elementAt(h), '|');
        for (int i = 0; i < words.length; i++) {
            if (words[i].trim().equals(word)) {
                return true;
            }
        }
        return false;
    }

    /** What list row r is: a pane's index, -1 minus a host's index, or Integer.MIN_VALUE for no row. */
    int at(int r) {
        return r >= 0 && r < rows.size() ? ((Integer) rows.elementAt(r)).intValue() : Integer.MIN_VALUE;
    }
}
