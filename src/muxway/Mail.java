package muxway;

import java.util.Vector;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/**
 * Messages and mail, from the hub's text mode: the feed of what panes
 * said to panes (GET /v1/talk), oldest at the top, and one pane's mail
 * (GET /v1/panes/{address}/mail) merged with what it sent, from the feed.
 * A box mails the pane, or mails back whoever wrote to it. MIDP 2.0
 * build only: Ui.MAIL is false in the 1.0 build and ProGuard drops this
 * class.
 */
final class Mail implements CommandListener {
    private final Muxway app;
    private final Display display;
    final List messages = new List("Messages", List.IMPLICIT);
    final List list = new List("Mail", List.IMPLICIT);
    final TextBox box = new TextBox("Mail", "", 1000, TextField.ANY);
    // Refresh takes the soft key; the rest sit in the menu in this order whatever was added when.
    private final Command refresh = new Command("Refresh", Command.SCREEN, 1);
    private final Command mailCmd = new Command("Mail pane", Command.SCREEN, 2);
    private final Command back = new Command("Back", Command.BACK, 3);
    private final Command backCmd = new Command("Mail back", Command.SCREEN, 4);
    private final Command send = new Command("Send", Command.OK, 1);

    // Where Messages returns to, and where the mail list does: the pane, or Messages.
    Displayable from;
    Displayable to;
    // The pane whose mail is listed, its name, and whether the token writes it.
    String address = "";
    String name = "";
    boolean writable;
    // Where the box posts to.
    String target = "";
    // The arrow and the envelope, or the forms a smaller charset has.
    boolean unicode = true;

    // Per message row: the pane it was said to when the list holds it, else empty, and whether it was a mail.
    private final Vector msgTo = new Vector();
    private final Vector msgMail = new Vector();
    // Per mail row: the sender's address when the list may mail it back, else empty.
    private final Vector senders = new Vector();

    Mail(Muxway app, Display display) {
        this.app = app;
        this.display = display;
        messages.setFitPolicy(Choice.TEXT_WRAP_ON);
        messages.addCommand(refresh);
        messages.addCommand(back);
        messages.setCommandListener(this);
        list.setFitPolicy(Choice.TEXT_WRAP_ON);
        list.addCommand(refresh);
        list.addCommand(backCmd);
        list.addCommand(back);
        list.setCommandListener(this);
        box.addCommand(send);
        box.addCommand(back);
        box.setCommandListener(this);
    }

    /** The feed's kinds; anything else is not a row. */
    private static boolean kind(String k) {
        return k.equals("text") || k.equals("key") || k.equals("answer") || k.equals("mail");
    }

    /** A feed row the screens read: a number, addresses by the grammar, one of the kinds. */
    private static boolean entry(String[] f) {
        return f.length >= 6 && digits(f[0]) && Text.address(f[3]) && (f[2].length() == 0 || Text.address(f[2])) && kind(f[4]);
    }

    /** An id or a seq: digits, one to twenty of them (the hub's ids outgrow an int). */
    private static boolean digits(String s) {
        if (s.length() < 1 || s.length() > 20) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** The list's name for a pane, else its place after the host; client for a mail from no pane. */
    private static String who(String address, Vector known, Vector names) {
        if (address.length() == 0) {
            return "client";
        }
        int k = known.indexOf(address);
        return k >= 0 ? (String) names.elementAt(k) : address.substring(address.indexOf('/') + 1);
    }

    private static String cut(String s) {
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private static void clear(List l) {
        for (int i = l.size() - 1; i >= 0; i--) {
            l.delete(i);
        }
    }

    /** Fills Messages from the feed's rows (seq, at, from, to, kind, text); the next row and anything else is skipped. */
    void fillMessages(String body, Vector known, Vector names) {
        clear(messages);
        msgTo.removeAllElements();
        msgMail.removeAllElements();
        String[] rows = Text.split(body, '\n');
        StringBuffer shown = new StringBuffer();
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (!entry(f)) {
                continue;
            }
            boolean mail = f[4].equals("mail");
            String label = cut(who(f[2], known, names) + (unicode ? " → " : " > ") + who(f[3], known, names) + " " + Text.hhmm(f[1])
                + ": " + (mail ? (unicode ? "✉ " : "M ") : "") + f[5]);
            messages.append(label, null);
            msgTo.addElement(known.indexOf(f[3]) >= 0 ? f[3] : "");
            msgMail.addElement(new Boolean(mail));
            shown.append(label).append('\n');
        }
        if (messages.size() == 0) {
            messages.append("Nothing said.", null);
        }
        Trace.show("Messages: " + shown.toString());
        display.setCurrent(messages);
    }

    /**
     * Fills the pane's mail from its mailbox rows (id, at, from, actor,
     * read_at, text, replied) and the feed's mail from it, merged by time,
     * oldest at the top.
     */
    void fillMail(String mailbox, String feed, Vector known, Vector names, Vector writable) {
        Vector at = new Vector();
        Vector labels = new Vector();
        senders.removeAllElements();
        String[] rows = Text.split(mailbox, '\n');
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (f.length < 6 || !digits(f[0]) || (f[2].length() > 0 && !Text.address(f[2]))) {
                continue;
            }
            String actor = f[3].trim();
            String sender = f[2].length() > 0 ? who(f[2], known, names) : actor.length() > 0 ? (actor.length() > 32 ? actor.substring(0, 32) : actor) : "client";
            boolean unread = f[4].trim().equals("0") || f[4].trim().length() == 0;
            boolean replied = f.length > 6 && f[6].trim().equals("1");
            String label = cut("< " + sender + " " + Text.hhmm(f[1]) + (unread ? " unread" : "") + (replied ? " replied" : "") + ": " + f[5]);
            int k = known.indexOf(f[2]);
            insert(at, labels, Text.toInt(f[1], 0), label, k >= 0 && ((Boolean) writable.elementAt(k)).booleanValue() ? f[2] : "");
        }
        rows = Text.split(feed, '\n');
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (!entry(f) || !f[4].equals("mail") || !f[2].equals(address)) {
                continue;
            }
            insert(at, labels, Text.toInt(f[1], 0), cut("> " + who(f[3], known, names) + " " + Text.hhmm(f[1]) + ": " + f[5]), "");
        }
        clear(list);
        list.removeCommand(mailCmd);
        if (writable != null && this.writable) {
            list.addCommand(mailCmd);
        }
        StringBuffer shown = new StringBuffer();
        for (int i = 0; i < labels.size(); i++) {
            list.append((String) labels.elementAt(i), null);
            shown.append((String) labels.elementAt(i)).append('\n');
        }
        if (list.size() == 0) {
            list.append("No mail.", null);
        }
        Trace.show("Mail: " + shown.toString());
        display.setCurrent(list);
    }

    /** Puts a row after every row of its time or earlier, so the mailbox's order holds among equals. */
    private void insert(Vector at, Vector labels, int when, String label, String sender) {
        int p = at.size();
        while (p > 0 && ((Integer) at.elementAt(p - 1)).intValue() > when) {
            p--;
        }
        at.insertElementAt(new Integer(when), p);
        labels.insertElementAt(label, p);
        senders.insertElementAt(sender, p);
    }

    /** The box, aimed at a pane. */
    private void open(String to, String toName) {
        target = to;
        box.setTitle("Mail " + toName);
        box.setString("");
        Trace.show("Mail box " + to);
        display.setCurrent(box);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == messages && c == List.SELECT_COMMAND) {
            int i = messages.getSelectedIndex();
            if (i < 0 || i >= msgTo.size()) {
                return;
            }
            String to = (String) msgTo.elementAt(i);
            if (to.length() == 0) {
                return;
            }
            if (((Boolean) msgMail.elementAt(i)).booleanValue()) {
                app.mail(to, messages);
            } else {
                app.open(to);
            }
        } else if (d == messages && c == refresh) {
            app.messages(from);
        } else if (d == messages && c == back) {
            display.setCurrent(from);
        } else if (d == list && (c == List.SELECT_COMMAND || c == backCmd)) {
            int i = list.getSelectedIndex();
            String sender = i >= 0 && i < senders.size() ? (String) senders.elementAt(i) : "";
            if (sender.length() > 0) {
                open(sender, app.nameOf(sender));
            } else {
                app.alert("No sender to mail back.", list);
            }
        } else if (d == list && c == mailCmd) {
            open(address, name);
        } else if (d == list && c == refresh) {
            app.mail(address, to);
        } else if (d == list && c == back) {
            display.setCurrent(to);
        } else if (d == box && c == send) {
            String text = Text.printable(box.getString()).trim();
            if (text.length() > 0) {
                display.setCurrent(list);
                app.mailTo(text);
            }
        } else if (d == box && c == back) {
            display.setCurrent(list);
        }
    }
}
