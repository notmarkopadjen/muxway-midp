package muxway;

import java.util.Vector;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * What waits for its owner, across hosts, oldest first: the hub's inbox in
 * text mode, one row per pane. A row with choices offers them; picking one
 * answers the question without typing into the pane. MIDP 2.0 build only:
 * Ui.INBOX is false in the 1.0 build and ProGuard drops this class.
 */
final class Inbox implements CommandListener {
    private final Muxway app;
    private final Display display;
    final List list = new List("Inbox", List.IMPLICIT);
    private final List answers = new List("Answer", List.IMPLICIT);
    private final Command back = new Command("Back", Command.BACK, 2);
    // Refresh takes the soft key, as on the pane list; Back is in the menu.
    private final Command refresh = new Command("Refresh", Command.SCREEN, 1);

    private final Vector addresses = new Vector();
    // The pane id of each row, from its seventh column; empty for a hub without ids.
    private final Vector ids = new Vector();
    // String[] of a row's choices, or null; none on a pane the token cannot write, an answer is a write.
    private final Vector choices = new Vector();
    // The screen Back returns to: the pane list or a pane.
    Displayable from;
    // The row being answered, its pane's id, and the choice picked.
    String address = "";
    String id = "";
    String choice = "";

    Inbox(Muxway app, Display display) {
        this.app = app;
        this.display = display;
        list.addCommand(refresh);
        list.addCommand(back);
        list.setCommandListener(this);
        answers.addCommand(back);
        answers.setCommandListener(this);
    }

    /** Fills the list from the hub's rows; names and the write mark come from the pane list where the address is known. */
    void fill(String body, Vector known, Vector names, Vector writable) {
        String[] rows = Text.split(body, '\n');
        for (int i = list.size() - 1; i >= 0; i--) {
            list.delete(i);
        }
        addresses.removeAllElements();
        ids.removeAllElements();
        choices.removeAllElements();
        StringBuffer shown = new StringBuffer();
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (f.length < 4 || !Text.address(f[0])) {
                continue;
            }
            int k = known.indexOf(f[0]);
            String name = k >= 0 ? (String) names.elementAt(k) : f[0].substring(f[0].indexOf('/') + 1);
            boolean w = k >= 0 && ((Boolean) writable.elementAt(k)).booleanValue();
            String label = Text.inboxLabel(f, name);
            list.append(label, null);
            addresses.addElement(f[0]);
            ids.addElement(f.length > 6 && Text.paneId(f[6].trim()) ? f[6].trim() : "");
            choices.addElement(w && f.length > 4 ? Text.choices(f[4]) : null);
            shown.append(label).append('\n');
        }
        if (list.size() == 0) {
            list.append("Nothing waits.", null);
        }
        Trace.show("Inbox: " + shown.toString());
        display.setCurrent(list);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == list && c == List.SELECT_COMMAND) {
            int i = list.getSelectedIndex();
            if (i < 0 || i >= addresses.size()) {
                return;
            }
            address = (String) addresses.elementAt(i);
            id = (String) ids.elementAt(i);
            String[] offered = (String[]) choices.elementAt(i);
            if (offered == null) {
                app.open(address, id);
                return;
            }
            for (int j = answers.size() - 1; j >= 0; j--) {
                answers.delete(j);
            }
            StringBuffer shown = new StringBuffer();
            for (int j = 0; j < offered.length; j++) {
                answers.append(offered[j], null);
                shown.append(offered[j]).append('\n');
            }
            answers.append("Open the pane", null);
            Trace.show("Answer: " + shown.toString());
            display.setCurrent(answers);
        } else if (d == answers && c == List.SELECT_COMMAND) {
            int i = answers.getSelectedIndex();
            if (i == answers.size() - 1) {
                app.open(address, id);
            } else if (i >= 0) {
                choice = answers.getString(i);
                display.setCurrent(list);
                app.answer();
            }
        } else if (d == answers && c == back) {
            display.setCurrent(list);
        } else if (c == refresh) {
            app.inbox(from);
        } else if (c == back) {
            display.setCurrent(from);
        }
    }
}
