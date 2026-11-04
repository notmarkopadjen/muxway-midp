package muxway;

import java.io.IOException;
import java.util.Vector;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.ImageItem;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/** muxway for Java ME phones: pick a pane, read it, reply, send a key. */
public final class Muxway extends MIDlet implements CommandListener, Runnable {
    private static final int LIST = 1, OPEN = 2, REFRESH = 3, POLL = 4, SEND = 5, TYPE = 6, KEY = 7, PROBE = 8, UPLOAD = 9,
        INBOX = 10, ANSWER = 11, LATER = 12;

    // Labels on the phone, names in the protocol.
    private static final String[][] KEYS = {
        {"Enter", "Enter"}, {"Ctrl-C", "C-c"}, {"Escape", "Escape"}, {"Tab", "Tab"}, {"Shift-Tab", "BTab"},
        {"Up", "Up"}, {"Down", "Down"}, {"Left", "Left"}, {"Right", "Right"},
        {"Page up", "PageUp"}, {"Page down", "PageDown"}, {"Backspace", "BSpace"},
        {"Ctrl-D", "C-d"}, {"Ctrl-L", "C-l"},
    };

    // Every lens looks at a pane's rows; the first that recognises its program shapes them.
    private static final Lens[] LENSES = {new ClaudeCode()};

    private final Prefs prefs = new Prefs();
    private Api api;
    private Display display;
    private Files files;
    private Inbox inbox;
    // The image on its way up, while the UPLOAD job runs.
    private Picture picture;

    private final Form setup = new Form("Settings");
    private final TextField server = new TextField("Server", "", 120, TextField.URL);
    private final TextField token = new TextField("Token", "", 64, TextField.PASSWORD);
    private final TextField width = new TextField("Width, 0 = fit", "", 3, TextField.NUMERIC);
    private final TextField refresh = new TextField("Auto refresh, s", "", 3, TextField.NUMERIC);
    private final ChoiceGroup textChoice = new ChoiceGroup("Text", ChoiceGroup.EXCLUSIVE,
        new String[] {"Unicode", "Latin-1", "ASCII"}, null);
    private final StringItem help = new StringItem("Keys",
        "1 refresh, 2/8 scroll, " + (Files.ANY ? "3 image, " : "") + "5 reply, " + (Ui.INBOX ? "9 inbox, " : "")
            + "0 keys, # title and position, * hidden rows, call key Enter");

    // One pane's own view, picked from a list reached from its menu; made
    // afresh for each pane, since MIDP 1.0 cannot retitle a screen.
    private List pane;

    private final List panes = new List("muxway", List.IMPLICIT);
    private final Vector addresses = new Vector();
    private final Vector names = new Vector();
    private final Vector commands = new Vector();
    private final Vector writable = new Vector();
    // Whether each row's pane is parked for later; MIDP 2.0 build only.
    private Vector later;
    private PaneView view;
    private final TextBox reply = new TextBox("Reply", "", 500, TextField.ANY);
    private final List keys = new List("Keys", List.IMPLICIT);

    private final Form wait = new Form("muxway");
    private final StringItem waitNote = new StringItem(null, "");
    private final ImageItem waitBar = new ImageItem(null, null, ImageItem.LAYOUT_CENTER, "");
    private Image[] frames;

    private final Command ok = new Command("OK", Command.OK, 1);
    private final Command send = new Command("Send", Command.OK, 1);
    private final Command type = new Command("Type", Command.SCREEN, 2);
    private final Command replyCmd = new Command("Reply", Command.SCREEN, 1);
    private final Command keysCmd = new Command("Keys", Command.SCREEN, 2);
    private final Command imageCmd = new Command("Image", Command.SCREEN, 3);
    private final Command inboxCmd = Ui.INBOX ? new Command("Inbox", Command.SCREEN, 4) : null;
    // One of the two is on a writable pane's menu, by the pane's state; MIDP 2.0 build only.
    private Command laterCmd;
    private Command wakeCmd;
    private final Command refreshCmd = new Command("Refresh", Command.SCREEN, 3);
    private final Command autoCmd = new Command("Auto", Command.SCREEN, 4);
    private final Command back = new Command("Back", Command.BACK, 2);
    private final Command paneCmd = new Command("Pane", Command.SCREEN, 5);
    private final Command settings = new Command("Settings", Command.SCREEN, 6);
    private final Command exit = new Command("Exit", Command.EXIT, 9);

    private static final long TIMEOUT = 45000;
    // A 1 MiB upload takes a minute over EDGE and three over GPRS.
    private static final long UPLOAD_TIMEOUT = 240000;
    private static final String NO_INBOX = "The host takes no files: its agent needs --inbox.";

    private int job;
    private String arg;
    private boolean busy;
    private int serial;
    private boolean auto;
    private int pollSerial;

    public Muxway() {
        setup.append(server);
        setup.append(token);
        setup.append(width);
        setup.append(refresh);
        setup.append(textChoice);
        setup.append(help);
        setup.addCommand(ok);
        setup.addCommand(exit);
        setup.setCommandListener(this);

        if (Ui.INBOX) {
            panes.addCommand(inboxCmd);
        }
        if (Ui.LATER) {
            later = new Vector();
            laterCmd = new Command("Later", Command.SCREEN, 5);
            wakeCmd = new Command("Wake", Command.SCREEN, 5);
        }
        panes.addCommand(refreshCmd);
        panes.addCommand(settings);
        panes.addCommand(exit);
        panes.setCommandListener(this);

        reply.addCommand(send);
        reply.addCommand(type);
        reply.addCommand(back);
        reply.setCommandListener(this);

        for (int i = 0; i < KEYS.length; i++) {
            keys.append(KEYS[i][0], null);
        }
        keys.addCommand(back);
        keys.setCommandListener(this);

        frames = loadFrames();
        wait.append(waitNote);
        wait.append(waitBar);
    }

    protected void startApp() {
        if (display != null) {
            return;
        }
        display = Display.getDisplay(this);
        Trace.on = "1".equals(getAppProperty("Muxway-Trace"));
        files = new Files(this, display);
        if (Ui.INBOX) {
            inbox = new Inbox(this, display);
        }
        String version = getAppProperty("MIDlet-Version");
        api = new Api(prefs, "muxway-midp/" + (version == null ? "?" : version) + " " + Ui.PROFILE);
        prefs.load();
        String s = getAppProperty("Muxway-Server");
        String t = getAppProperty("Muxway-Token");
        boolean badUrl = false;
        if (s != null && t != null && s.trim().length() > 0 && t.trim().length() > 0) {
            // A new install file replaces what is saved, once; Settings may change it
            // after. The stamp stays when the hub refuses the token, so the same
            // install file is not taken again.
            String stamp = Integer.toString(t.trim().hashCode());
            if (!Text.url(s.trim())) {
                badUrl = true;
            } else if (!stamp.equals(prefs.installed)) {
                prefs.server = s.trim();
                prefs.token = t.trim();
                prefs.installed = stamp;
                String r = getAppProperty("Muxway-Refresh");
                if (r != null) {
                    prefs.refresh = Math.max(0, Math.min(600, Text.toInt(r, 0)));
                }
                String x = getAppProperty("Muxway-Text");
                if (x != null) {
                    prefs.text = x.equals("ascii") ? 2 : x.equals("latin1") ? 1 : x.equals("unicode") ? 0 : prefs.text;
                }
                prefs.save();
            }
        }
        if (prefs.ready()) {
            start(LIST, null);
        } else {
            showSetup();
            if (badUrl) {
                alert(BAD_URL, setup);
            }
        }
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
        auto = false;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exit) {
            auto = false;
            notifyDestroyed();
        } else if (c == settings) {
            auto = false;
            showSetup();
        } else if (d == setup && c == ok) {
            saveSetup();
        } else if (d == view && c == paneCmd) {
            pane = new List(view.name, List.IMPLICIT, viewNames(), null);
            pane.setSelectedIndex(Math.min(prefs.view(view.address), pane.size() - 1), true);
            pane.addCommand(back);
            pane.setCommandListener(this);
            display.setCurrent(pane);
        } else if (d == pane && c == List.SELECT_COMMAND) {
            int v = pane.getSelectedIndex();
            prefs.setView(view.address, v);
            prefs.save();
            Trace.show("Pane view " + v);
            view.setMode(v);
            display.setCurrent(view);
        } else if (d == panes && c == List.SELECT_COMMAND) {
            int i = panes.getSelectedIndex();
            if (i >= 0 && i < addresses.size()) {
                open(i);
            }
        } else if (d == panes && c == refreshCmd) {
            start(LIST, null);
        } else if (Ui.INBOX && c == inboxCmd) {
            inbox(panes);
        } else if (d == view && c == back) {
            auto = false;
            start(LIST, null);
        } else if (d == view && c == refreshCmd) {
            start(REFRESH, null);
        } else if (d == view && c == autoCmd) {
            auto = !auto;
            alert(auto ? "Auto refresh every " + interval() + " s." : "Auto refresh off.", view);
            if (auto) {
                poll();
            }
        } else if (Ui.LATER && d == view && (c == laterCmd || c == wakeCmd)) {
            start(LATER, null);
        } else if (c == replyCmd) {
            shortcut(PaneView.REPLY);
        } else if (c == keysCmd) {
            shortcut(PaneView.KEYS);
        } else if (c == imageCmd && Files.ANY) {
            shortcut(PaneView.IMAGE);
        } else if (d == reply && (c == send || c == type)) {
            String text = Text.printable(reply.getString());
            // Back to the pane at once, where the wait mark runs; a refusal is shown over it.
            display.setCurrent(view);
            if (text.length() > 0) {
                start(c == send ? SEND : TYPE, text);
            } else if (c == send) {
                start(KEY, "Enter");
            }
        } else if (d == keys && c == List.SELECT_COMMAND) {
            start(KEY, KEYS[keys.getSelectedIndex()][1]);
        } else if (c == back) {
            display.setCurrent(view);
        }
    }

    /** Keypad shortcuts of the pane view and the commands that do the same. */
    void shortcut(int what) {
        if (what == PaneView.ENTER) {
            start(KEY, "Enter");
        } else if (what == PaneView.REPLY) {
            reply.setString("");
            Trace.show("Reply");
            display.setCurrent(reply);
        } else if (what == PaneView.KEYS) {
            Trace.show("Keys");
            display.setCurrent(keys);
        } else if (what == PaneView.REFRESH) {
            start(REFRESH, null);
        } else if (Ui.INBOX && what == PaneView.INBOX) {
            inbox(view);
        } else if (what == PaneView.IMAGE) {
            // The 1.0 build compiles none of this: Files.ANY is a constant there.
            if (Files.ANY && view != null && view.writable) {
                // Asked once per pane whether the host takes files, so no shot is wasted.
                if (view.inbox) {
                    files.open();
                } else {
                    start(PROBE, null);
                }
            }
        }
    }

    void showPane() {
        display.setCurrent(view);
    }

    /** Reads the inbox; Back on it returns to from. */
    void inbox(Displayable from) {
        inbox.from = from;
        start(INBOX, null);
    }

    /** Sends the choice the inbox picked for its row. */
    void answer() {
        start(ANSWER, null);
    }

    /** Opens a pane the inbox named; one the pane list does not know yet means the list is stale. */
    void open(String address) {
        int i = addresses.indexOf(address);
        if (i >= 0) {
            open(i);
        } else {
            start(LIST, null);
        }
    }

    /** An image chosen on the phone goes up to the pane's host; Reply then holds its path. */
    void picked(Picture p) {
        display.setCurrent(view);
        picture = p;
        if (!start(UPLOAD, null)) {
            p.close();
            picture = null;
        }
    }

    private static final String BAD_URL = "Server must start with http:// or https:// and have no ?, # or @.";

    private void saveSetup() {
        String s = trimSlash(server.getString().trim());
        if (s.length() > 0 && !Text.url(s)) {
            alert(BAD_URL, setup);
            return;
        }
        // Said once per server: plain HTTP past the phone's own network shows the token to the path.
        boolean open = !s.equals(prefs.server) && s.startsWith("http://") && !Text.local(s);
        prefs.server = s;
        String t = token.getString().trim();
        if (t.length() > 0) {
            prefs.token = t;
        }
        prefs.width = Math.max(0, Math.min(400, Text.toInt(width.getString(), 0)));
        prefs.refresh = Math.max(0, Math.min(600, Text.toInt(refresh.getString(), 0)));
        prefs.text = textChoice.getSelectedIndex();
        prefs.save();
        if (open) {
            alert("Unencrypted: the token travels in the clear. Use a relay and a token scoped to this phone (docs/features/relay.md).", setup);
        } else if (prefs.ready()) {
            start(LIST, null);
        } else {
            alert("Server and token are needed.", setup);
        }
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void showSetup() {
        fillSetup();
        Trace.show("Settings: " + prefs.server);
        display.setCurrent(setup);
    }

    private void fillSetup() {
        server.setString(prefs.server);
        // The stored token is never shown again; leaving the field empty keeps it.
        token.setString("");
        width.setString(Integer.toString(prefs.width));
        refresh.setString(Integer.toString(prefs.refresh));
        textChoice.setSelectedIndex(prefs.text, true);
    }

    /** Auto, Plain, then every lens by name. */
    private static String[] viewNames() {
        String[] names = new String[2 + LENSES.length];
        names[0] = "Auto";
        names[1] = "Plain";
        for (int i = 0; i < LENSES.length; i++) {
            names[2 + i] = LENSES[i].name();
        }
        return names;
    }

    private void open(int i) {
        String address = (String) addresses.elementAt(i);
        boolean canWrite = ((Boolean) writable.elementAt(i)).booleanValue();
        view = new PaneView(frames, this);
        view.address = address;
        view.title = address.substring(address.indexOf('/') + 1);
        view.name = (String) names.elementAt(i);
        view.command = (String) commands.elementAt(i);
        view.writable = canWrite;
        if (Ui.LATER) {
            view.later = ((Boolean) later.elementAt(i)).booleanValue();
        }
        view.cols = prefs.width;
        view.lenses = LENSES;
        view.mode = prefs.view(address);
        if (canWrite) {
            view.addCommand(replyCmd);
            view.addCommand(keysCmd);
            if (Files.ANY) {
                view.addCommand(imageCmd);
            }
        }
        view.addCommand(refreshCmd);
        view.addCommand(autoCmd);
        view.addCommand(paneCmd);
        if (Ui.LATER && canWrite) {
            view.addCommand(view.later ? wakeCmd : laterCmd);
        }
        view.addCommand(back);
        view.addCommand(settings);
        view.addCommand(exit);
        view.setCommandListener(this);
        auto = false;
        Trace.show("View " + address + (canWrite ? " writable" : " read-only"));
        display.setCurrent(view);
        start(OPEN, null);
    }

    private int interval() {
        return prefs.refresh > 0 ? prefs.refresh : 10;
    }

    /**
     * Runs one job on its own thread; the UI thread must not touch the network.
     * MIDP has no request timeout, so a watchdog gives up on a job that hangs
     * and the app stays usable; a late answer from it is then ignored.
     */
    private synchronized boolean start(int what, String with) {
        if (busy) {
            if (what != POLL) {
                alert("Still waiting for the last answer.", display.getCurrent());
            }
            return false;
        }
        busy = true;
        job = what;
        arg = with;
        final int mine = ++serial;
        if (what == LIST || (Ui.INBOX && what == INBOX)) {
            waitNote.setText(Ui.INBOX && what == INBOX ? "Loading inbox..." : "Loading panes...");
            display.setCurrent(wait);
        }
        spin(what);
        new Thread(this).start();
        final long patience = what == UPLOAD ? UPLOAD_TIMEOUT : TIMEOUT;
        new Thread() {
            public void run() {
                try {
                    Thread.sleep(patience);
                } catch (InterruptedException e) {
                }
                giveUp(mine);
            }
        }.start();
        return true;
    }

    private synchronized void giveUp(int which) {
        if (busy && serial == which) {
            busy = false;
            serial++;
            Displayable next = job == LIST ? (Displayable) setup : view;
            if (Ui.INBOX && (job == INBOX || job == ANSWER)) {
                next = inbox.from;
            }
            alert("No answer from the server.", next);
        }
    }

    /** True while the job that is running is still the current one. */
    private synchronized boolean current(int which) {
        return serial == which;
    }

    private synchronized void finish(int which) {
        if (serial == which) {
            busy = false;
        }
    }

    private void spin(final int what) {
        new Thread() {
            public void run() {
                int step = 0;
                while (busy) {
                    if (what == LIST && frames != null) {
                        waitBar.setImage(frames[step % frames.length]);
                    } else if (what != LIST && what != POLL && (!Ui.INBOX || what != INBOX) && view != null) {
                        view.busy(step);
                    }
                    step++;
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                    }
                }
                if (view != null) {
                    view.busy(-1);
                }
            }
        }.start();
    }

    public void run() {
        int mine;
        synchronized (this) {
            mine = serial;
        }
        try {
            switch (job) {
            case LIST:
                list();
                break;
            case OPEN:
                if (Ui.LATER && view.later && view.writable) {
                    wake();
                }
                for (int i = 0; i < 20 && !view.ready(); i++) {
                    Thread.sleep(50);
                }
                read(null);
                break;
            case REFRESH:
                read(null);
                break;
            case POLL:
                read(view.address.equals(arg) ? etag : null);
                break;
            case SEND:
            case TYPE:
                if (input(Api.json("text", arg)) && (job == TYPE || input(Api.key("Enter")))) {
                    display.setCurrent(view);
                    Thread.sleep(500);
                    read(null);
                }
                break;
            case KEY:
                if (input(Api.key(arg))) {
                    display.setCurrent(view);
                    Thread.sleep(300);
                    read(null);
                }
                break;
            case PROBE:
                if (Files.ANY) {
                    probe();
                }
                break;
            case UPLOAD:
                if (Files.ANY) {
                    upload();
                }
                break;
            case INBOX:
                if (Ui.INBOX) {
                    readInbox();
                }
                break;
            case ANSWER:
                if (Ui.INBOX && answer(inbox.address, inbox.choice)) {
                    Thread.sleep(300);
                    readInbox();
                }
                break;
            case LATER:
                if (Ui.LATER) {
                    later(!view.later);
                }
                break;
            }
        } catch (IOException e) {
            if (current(mine)) {
                fail("No connection: " + e.getMessage());
            }
        } catch (Exception e) {
            if (current(mine)) {
                Trace.show("Error: " + e);
                fail("Something went wrong. Try again.");
            }
        } finally {
            finish(mine);
        }
    }

    private String etag = "";

    private void list() throws IOException {
        if (api.call("panes" + (charset().length() > 0 ? "?" + charset() : ""), null, null) != 200) {
            problem(api.status);
            return;
        }
        String[] rows = Text.split(api.body, '\n');
        for (int i = panes.size() - 1; i >= 0; i--) {
            panes.delete(i);
        }
        addresses.removeAllElements();
        names.removeAllElements();
        commands.removeAllElements();
        writable.removeAllElements();
        if (Ui.LATER) {
            later.removeAllElements();
        }
        // Hosts are named in the list only when there is more than one.
        String host = null;
        boolean several = false;
        for (int i = 0; i < rows.length; i++) {
            int slash = rows[i].indexOf('/');
            int tab = rows[i].indexOf('\t');
            if (slash > 0 && tab > slash) {
                String h = rows[i].substring(0, slash);
                several |= host != null && !host.equals(h);
                host = h;
            }
        }
        StringBuffer shown = new StringBuffer();
        // The labels and the talk column, until the rows' order is known.
        Vector labels = Ui.TALK ? new Vector() : null;
        Vector talk = Ui.TALK ? new Vector() : null;
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (f.length < 3 || !Text.address(f[0])) {
                continue;
            }
            // The mark comes from the hub's mode column, never from the title.
            boolean w = f[1].equals("write");
            // The unread mail after the name, from the sixth column where the hub sends one.
            String name = Text.label(f, several, Ui.TALK && f.length > 5 ? Text.mail(f[5], prefs.text == 0) : "");
            // Then what the pane waits for, from the kind column: ? ! z v, zz for a parked pane.
            String kind = f.length > 4 ? f[4].trim() : "";
            String mark = Text.mark(kind);
            String label = (w ? "*" : " ") + mark + (mark.length() > 1 ? " " : "") + name;
            if (Ui.TALK) {
                labels.addElement(label);
                talk.addElement(f.length > 6 ? f[6] : "");
            } else {
                panes.append(label, null);
                shown.append(label).append('\n');
            }
            addresses.addElement(f[0]);
            names.addElement(name);
            commands.addElement(f[2].trim());
            writable.addElement(new Boolean(w));
            if (Ui.LATER) {
                later.addElement(new Boolean(kind.equals("later")));
            }
        }
        if (Ui.TALK) {
            arrange(labels, talk, shown);
        }
        if (api.call("hosts", null, null) == 200) {
            String[] hosts = Text.split(api.body, '\n');
            for (int i = 0; i < hosts.length; i++) {
                String[] f = Text.split(hosts[i], '\t');
                if (f.length == 2 && f[1].equals("offline")) {
                    String label = "- " + f[0] + " offline";
                    panes.append(label, null);
                    shown.append(label).append('\n');
                }
            }
        }
        if (panes.size() == 0) {
            panes.append("No panes. Expose one on a host.", null);
        }
        Trace.show("Panes: " + shown.toString());
        display.setCurrent(panes);
    }

    /**
     * Fills the list from the labels, a group's rows together behind the
     * frame's edge, and puts the parallel Vectors in the same order so a
     * select opens the row it names. MIDP 2.0 build only.
     */
    private void arrange(Vector labels, Vector talk, StringBuffer shown) {
        int[] group = Talk.groups(addresses, talk);
        int[] order = Talk.order(group);
        boolean framed = Talk.any(group);
        Vector[] kept = {addresses, names, commands, writable, later};
        Object[][] was = new Object[kept.length][];
        for (int k = 0; k < kept.length; k++) {
            was[k] = new Object[kept[k].size()];
            kept[k].copyInto(was[k]);
            kept[k].removeAllElements();
        }
        for (int p = 0; p < order.length; p++) {
            int i = order[p];
            String label = (framed ? String.valueOf(Talk.edge(group, order, p)) : "") + (String) labels.elementAt(i);
            panes.append(label, null);
            shown.append(label).append('\n');
            for (int k = 0; k < kept.length; k++) {
                kept[k].addElement(was[k][i]);
            }
        }
    }

    /** The query parameter for the Text setting; empty for Unicode. */
    private String charset() {
        return prefs.text == 1 ? "charset=latin1" : prefs.text == 2 ? "charset=ascii" : "";
    }

    private void read(String ifNoneMatch) throws IOException {
        if (view == null) {
            return;
        }
        // The rows as they are on the terminal; the phone wraps them itself.
        String path = "panes/" + view.address + "?lines=" + Ui.LINES + "&width=400"
            + (charset().length() > 0 ? "&" + charset() : "");
        int status = api.call(path, null, ifNoneMatch);
        if (status == 304) {
            return;
        }
        if (status != 200) {
            problem(status);
            return;
        }
        etag = api.etag;
        view.setText(api.body);
    }

    private void readInbox() throws IOException {
        if (api.call("inbox" + (charset().length() > 0 ? "?" + charset() : ""), null, null) != 200) {
            problem(api.status);
            return;
        }
        inbox.fill(api.body, addresses, names);
    }

    /** The choice goes to the process that asked; nothing is typed into the pane. */
    private boolean answer(String address, String choice) throws IOException {
        int status = api.call("panes/" + address + "/answer", Api.json("choice", choice), null);
        Trace.show("Answered " + status);
        if (status == 204 || status == 200) {
            return true;
        }
        if (status == 400) {
            alert("Not a choice.", inbox.list);
        } else if (status == 409) {
            alert("No question now.", inbox.list);
        } else {
            problem(status);
        }
        return false;
    }

    /** Opening a parked pane is the owner coming back: the hub's answer is traced, the read goes on either way. */
    private void wake() throws IOException {
        int status = api.call("panes/" + view.address + "/later", Api.later(false), null);
        Trace.show("Later false " + status);
        if (status == 204 || status == 200) {
            parked(false);
        }
    }

    /** Parks the open pane or wakes it: the owner's mark on the host, seen by every client. */
    private void later(boolean on) throws IOException {
        int status = api.call("panes/" + view.address + "/later", Api.later(on), null);
        Trace.show("Later " + on + " " + status);
        if (status == 204 || status == 200) {
            parked(on);
            alert(on ? "Parked for later." : "Awake.", view);
        } else if (status == 404) {
            alert("This host does not park panes.", view);
        } else {
            problem(status);
        }
    }

    private void parked(boolean on) {
        view.later = on;
        view.removeCommand(on ? laterCmd : wakeCmd);
        view.addCommand(on ? wakeCmd : laterCmd);
    }

    private boolean input(String json) throws IOException {
        int status = api.call("panes/" + view.address + "/input", json, null);
        // 204 from a hub; 200 from a relay that cannot pass an empty answer on.
        if (status == 204 || status == 200) {
            return true;
        }
        problem(status);
        return false;
    }

    private void probe() throws IOException {
        int status = api.call("panes/" + view.address + "/files", null, null);
        // 204 from a hub; 200 from a relay that cannot pass an empty answer on.
        if (status == 204 || status == 200) {
            view.inbox = true;
            files.open();
        } else if (status == 404) {
            alert(NO_INBOX, view);
        } else {
            problem(status);
        }
    }

    private void upload() throws IOException {
        Picture p = picture;
        int status;
        try {
            status = api.upload("panes/" + view.address + "/files", p);
        } finally {
            p.close();
            picture = null;
        }
        Trace.show("Image: " + p.length() + " bytes -> " + status);
        if (status != 201) {
            if (status == 404) {
                alert(NO_INBOX, view);
            } else if (status == 413) {
                alert("Too big (8 MiB at most).", view);
            } else if (status == 415) {
                alert("Not an image the host takes.", view);
            } else if (status == 507) {
                alert("The host's inbox is full.", view);
            } else {
                problem(status);
            }
            return;
        }
        // The path is text for the Reply box and nothing else; the phone never opens it.
        String path = Text.jsonString(api.body, "path");
        if (path == null || path.length() == 0) {
            fail("Unexpected answer.");
            return;
        }
        path = Text.printable(path);
        if (path.length() > 256) {
            path = path.substring(0, 256);
        }
        Trace.show("Reply " + path);
        reply.setString(path + " ");
        display.setCurrent(reply);
    }

    /** Re-reads the open pane every interval while Auto is on and it is shown. */
    private void poll() {
        final int serial = ++pollSerial;
        final PaneView polled = view;
        new Thread() {
            public void run() {
                while (auto && serial == pollSerial && view == polled) {
                    try {
                        Thread.sleep(interval() * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (auto && serial == pollSerial && view == polled && display.getCurrent() == polled) {
                        Muxway.this.start(POLL, polled.address);
                    }
                }
            }
        }.start();
    }

    private void problem(int status) {
        if (status == 401) {
            auto = false;
            prefs.token = "";
            prefs.save();
            alert(Api.problem(status) + " Enter a new one.", setup);
            fillSetup();
            return;
        }
        Displayable next = view != null && display.getCurrent() != panes && job != LIST ? (Displayable) view : panes;
        if (job == LIST && panes.size() == 0) {
            next = setup;
        }
        if (Ui.INBOX && (job == INBOX || job == ANSWER)) {
            next = job == ANSWER || inbox.list.size() > 0 ? (Displayable) inbox.list : inbox.from;
        }
        alert(Api.problem(status), next);
    }

    private void fail(String message) {
        Displayable next = job == LIST ? (Displayable) setup : view;
        if (next == null) {
            next = setup;
        }
        if (next == setup) {
            fillSetup();
        }
        alert(message, next);
    }

    void alert(String text, Displayable next) {
        Trace.show("Alert: " + text);
        Alert a = new Alert("muxway", text, null, AlertType.INFO);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, next);
    }

    private static Image[] loadFrames() {
        Vector found = new Vector();
        try {
            for (int i = 0; ; i++) {
                found.addElement(Image.createImage("/wait" + i + ".png"));
            }
        } catch (Exception e) {
        }
        if (found.size() == 0) {
            return null;
        }
        Image[] out = new Image[found.size()];
        found.copyInto(out);
        return out;
    }
}
