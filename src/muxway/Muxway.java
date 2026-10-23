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
    private static final int LIST = 1, OPEN = 2, REFRESH = 3, POLL = 4, SEND = 5, TYPE = 6, KEY = 7;

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

    private final Form setup = new Form("Settings");
    private final TextField server = new TextField("Server", "", 120, TextField.URL);
    private final TextField token = new TextField("Token", "", 64, TextField.PASSWORD);
    private final TextField width = new TextField("Width, 0 = fit", "", 3, TextField.NUMERIC);
    private final TextField refresh = new TextField("Auto refresh, s", "", 3, TextField.NUMERIC);
    private final ChoiceGroup textChoice = new ChoiceGroup("Text", ChoiceGroup.EXCLUSIVE,
        new String[] {"Unicode", "Latin-1", "ASCII"}, null);
    private final StringItem help = new StringItem("Keys",
        "2/8 scroll, 5 reply, 0 keys, # title and position, * hidden rows, call key Enter");

    // One pane's own view, picked from a list reached from its menu; made
    // afresh for each pane, since MIDP 1.0 cannot retitle a screen.
    private List pane;

    private final List panes = new List("muxway", List.IMPLICIT);
    private final Vector addresses = new Vector();
    private final Vector writable = new Vector();
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
    private final Command refreshCmd = new Command("Refresh", Command.SCREEN, 3);
    private final Command autoCmd = new Command("Auto", Command.SCREEN, 4);
    private final Command back = new Command("Back", Command.BACK, 2);
    private final Command paneCmd = new Command("Pane", Command.SCREEN, 5);
    private final Command settings = new Command("Settings", Command.SCREEN, 6);
    private final Command exit = new Command("Exit", Command.EXIT, 9);

    private static final long TIMEOUT = 45000;

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
        String version = getAppProperty("MIDlet-Version");
        api = new Api(prefs, "muxway-midp/" + (version == null ? "?" : version) + " " + Ui.PROFILE);
        prefs.load();
        if (!prefs.ready()) {
            String s = getAppProperty("Muxway-Server");
            String t = getAppProperty("Muxway-Token");
            if (s != null && t != null && s.trim().length() > 0 && t.trim().length() > 0) {
                prefs.server = s.trim();
                prefs.token = t.trim();
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
            pane = new List(view.title, List.IMPLICIT, viewNames(), null);
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
        } else if (c == replyCmd) {
            shortcut(PaneView.REPLY);
        } else if (c == keysCmd) {
            shortcut(PaneView.KEYS);
        } else if (d == reply && (c == send || c == type)) {
            String text = Text.printable(reply.getString());
            if (text.length() > 0) {
                start(c == send ? SEND : TYPE, text);
            } else if (c == send) {
                start(KEY, "Enter");
            } else {
                display.setCurrent(view);
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
        }
    }

    private void saveSetup() {
        prefs.server = trimSlash(server.getString().trim());
        String t = token.getString().trim();
        if (t.length() > 0) {
            prefs.token = t;
        }
        prefs.width = Math.max(0, Math.min(400, Text.toInt(width.getString(), 0)));
        prefs.refresh = Math.max(0, Math.min(600, Text.toInt(refresh.getString(), 0)));
        prefs.text = textChoice.getSelectedIndex();
        prefs.save();
        if (prefs.ready()) {
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
        view.writable = canWrite;
        view.cols = prefs.width;
        view.lenses = LENSES;
        view.mode = prefs.view(address);
        if (canWrite) {
            view.addCommand(replyCmd);
            view.addCommand(keysCmd);
        }
        view.addCommand(refreshCmd);
        view.addCommand(autoCmd);
        view.addCommand(paneCmd);
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
        if (what == LIST) {
            waitNote.setText("Loading panes...");
            display.setCurrent(wait);
        }
        spin(what);
        new Thread(this).start();
        new Thread() {
            public void run() {
                try {
                    Thread.sleep(TIMEOUT);
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
            alert("No answer from the server.", job == LIST ? (Displayable) setup : view);
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
                    } else if (what != LIST && what != POLL && view != null) {
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
                if (input(Api.text(arg)) && (job == TYPE || input(Api.key("Enter")))) {
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
            }
        } catch (IOException e) {
            if (current(mine)) {
                fail("No connection: " + e.getMessage());
            }
        } catch (Exception e) {
            if (current(mine)) {
                fail(e.toString());
            }
        } finally {
            finish(mine);
        }
    }

    private String etag = "";

    private void list() throws IOException {
        if (api.call("panes", null, null) != 200) {
            problem(api.status);
            return;
        }
        String[] rows = Text.split(api.body, '\n');
        for (int i = panes.size() - 1; i >= 0; i--) {
            panes.delete(i);
        }
        addresses.removeAllElements();
        writable.removeAllElements();
        StringBuffer shown = new StringBuffer();
        for (int i = 0; i < rows.length; i++) {
            String[] f = Text.split(rows[i], '\t');
            if (f.length < 3) {
                continue;
            }
            boolean w = f[1].equals("write");
            String label = (w ? "* " : "  ") + f[0] + " " + f[2];
            panes.append(label, null);
            addresses.addElement(f[0]);
            writable.addElement(new Boolean(w));
            shown.append(label).append('\n');
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

    private void read(String ifNoneMatch) throws IOException {
        if (view == null) {
            return;
        }
        // The rows as they are on the terminal; the phone wraps them itself.
        String path = "panes/" + view.address + "?lines=" + Ui.LINES + "&width=400"
            + (prefs.text == 1 ? "&charset=latin1" : prefs.text == 2 ? "&charset=ascii" : "");
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

    private boolean input(String json) throws IOException {
        int status = api.call("panes/" + view.address + "/input", json, null);
        // 204 from a hub; 200 from a relay that cannot pass an empty answer on.
        if (status == 204 || status == 200) {
            return true;
        }
        problem(status);
        return false;
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

    private void alert(String text, Displayable next) {
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
