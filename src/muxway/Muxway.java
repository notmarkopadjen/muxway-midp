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
        INBOX = 10, ANSWER = 11, LATER = 12, LISTQ = 13, SESSION = 14, TALK = 15, MAIL = 16, MAILTO = 17, SPLIT = 18,
        CLOSE = 19, DESK = 20;

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
    // The pane canvas's colours; MIDP 2.0 build only.
    private ChoiceGroup themeChoice;
    private final StringItem help = new StringItem("Keys",
        "1 refresh, 2/8 scroll, " + (Files.ANY ? "3 image, " : "") + "5 reply, " + (Ui.MAIL ? "7 messages, " : "")
            + (Ui.INBOX ? "9 inbox, " : "") + "0 keys, # title and position, * hidden rows, call key Enter"
            + (Ui.HOSTS ? ", left/right previous and next pane" : ""));

    // One pane's own view, picked from a list reached from its menu; made
    // afresh for each pane, since MIDP 1.0 cannot retitle a screen.
    private List pane;

    private final List panes = new List("muxway", List.IMPLICIT);
    private final Vector addresses = new Vector();
    private final Vector names = new Vector();
    private final Vector commands = new Vector();
    private final Vector writable = new Vector();
    // The pane id of each row, what a view knows its pane by; MIDP 2.0 build only.
    private Vector ids;
    // The kind's mark of each pane, as the list drew it; the overlay shows it. MIDP 2.0 build only.
    private Vector marks;
    // Whether each row's pane is parked for later; MIDP 2.0 build only.
    private Vector later;
    // The hosts, their flags and what each list row is; MIDP 2.0 build only.
    private Hosts hosts;
    // Messages and a pane's mail; MIDP 2.0 build only.
    private Mail mail;
    private Command messagesCmd;
    private Command mailCmd;
    // The Commands list and the program's entries in it; MIDP 2.0 build only.
    private Command commandsCmd;
    private List cmdList;
    private String[] cmdEntries;
    // + session and + pane on the list's menu, Close on the pane's, each behind a box or a confirm
    // list; MIDP 2.0 build only.
    private Command sessionCmd;
    private Command splitCmd;
    private Command closeCmd;
    private List confirm;
    private int confirming;
    // The pane whose window + pane splits, and the pane a job wants opened once it is done.
    private String splitting;
    private int openNext = -1;
    // Desk on the list's menu: the desk's focused pane opened from the hub's desk; MIDP 2.0 build only.
    private Command deskCmd;
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
    // The list re-read on the timer while it is shown; MIDP 2.0 build only.
    private boolean listAuto;
    private int listPollSerial;

    public Muxway() {
        setup.append(server);
        setup.append(token);
        setup.append(width);
        setup.append(refresh);
        setup.append(textChoice);
        if (Ui.THEME) {
            themeChoice = new ChoiceGroup("Theme", ChoiceGroup.EXCLUSIVE, Theme.NAMES, null);
            setup.append(themeChoice);
        }
        setup.append(help);
        setup.addCommand(ok);
        setup.addCommand(exit);
        setup.setCommandListener(this);

        if (Ui.DESK) {
            // First of the list's entries: the desk is the view every client shares.
            deskCmd = new Command("Desk", Command.SCREEN, 4);
            panes.addCommand(deskCmd);
        }
        if (Ui.INBOX) {
            panes.addCommand(inboxCmd);
        }
        if (Ui.NAMES) {
            marks = new Vector();
        }
        if (Ui.IDS) {
            ids = new Vector();
        }
        if (Ui.LATER) {
            later = new Vector();
            laterCmd = new Command("Later", Command.SCREEN, 5);
            wakeCmd = new Command("Wake", Command.SCREEN, 5);
        }
        if (Ui.HOSTS) {
            hosts = new Hosts();
            hosts.box.addCommand(hosts.start);
            hosts.box.addCommand(back);
            hosts.box.setCommandListener(this);
            sessionCmd = new Command("+ session", Command.SCREEN, 7);
            splitCmd = new Command("+ pane", Command.SCREEN, 7);
            closeCmd = new Command("Close", Command.SCREEN, 8);
            confirm = new List("", List.IMPLICIT);
            confirm.addCommand(back);
            confirm.setCommandListener(this);
        }
        if (Ui.MAIL) {
            messagesCmd = new Command("Messages", Command.SCREEN, 4);
            mailCmd = new Command("Mail", Command.SCREEN, 4);
            panes.addCommand(messagesCmd);
        }
        if (Ui.COMMANDS) {
            // Before Pane and Later, so Wake, re-added while parked, keeps Later's place on the menu.
            commandsCmd = new Command("Commands", Command.SCREEN, 4);
            cmdList = new List("Commands", List.IMPLICIT);
            cmdList.addCommand(back);
            cmdList.setCommandListener(this);
        }
        panes.addCommand(refreshCmd);
        if (Ui.HOSTS) {
            panes.addCommand(autoCmd);
        }
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
        if (Ui.MAIL) {
            mail = new Mail(this, display);
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
        if (Ui.HOSTS) {
            listAuto = false;
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exit) {
            auto = false;
            if (Ui.HOSTS) {
                listAuto = false;
            }
            notifyDestroyed();
        } else if (c == settings) {
            auto = false;
            if (Ui.HOSTS) {
                listAuto = false;
            }
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
            if (Ui.HOSTS) {
                i = hosts.at(i);
            }
            if (i >= 0 && i < addresses.size()) {
                open(i);
            }
        } else if (Ui.HOSTS && d == panes && c == sessionCmd) {
            // On the host of the row under the cursor, where its flags allow it.
            int h = hostAt(panes.getSelectedIndex());
            if (h >= 0 && hosts.flag(h, "sessions")) {
                hosts.session = (String) hosts.names.elementAt(h);
                hosts.box.setTitle("Session on " + hosts.session);
                hosts.box.setString("");
                Trace.show("Session " + hosts.session);
                display.setCurrent(hosts.box);
            } else {
                alert("This host does not start sessions.", panes);
            }
        } else if (Ui.HOSTS && d == panes && c == splitCmd) {
            int i = hosts.at(panes.getSelectedIndex());
            if (i >= 0 && i < addresses.size() && ((Boolean) writable.elementAt(i)).booleanValue()) {
                splitting = (String) addresses.elementAt(i);
                confirming = SPLIT;
                ask("New pane beside " + names.elementAt(i) + "?", new String[] {"Beside", "Below", "Cancel"});
            } else {
                alert("Pick a writable pane first.", panes);
            }
        } else if (Ui.HOSTS && d == hosts.box && c == hosts.start) {
            String name = hosts.box.getString().trim();
            if (!Text.session(name)) {
                alert("A name is a-z, 0-9, - and _, up to 32.", hosts.box);
                return;
            }
            display.setCurrent(panes);
            start(SESSION, name);
        } else if (Ui.HOSTS && d == hosts.box && c == back) {
            display.setCurrent(panes);
        } else if (Ui.MAIL && d == panes && c == messagesCmd) {
            messages(panes);
        } else if (Ui.MAIL && d == view && c == mailCmd) {
            mail(view.address, view);
        } else if (Ui.COMMANDS && d == view && c == commandsCmd) {
            for (int i = cmdList.size() - 1; i >= 0; i--) {
                cmdList.delete(i);
            }
            for (int i = 0; cmdEntries != null && i < cmdEntries.length; i++) {
                cmdList.append(cmdEntries[i], null);
            }
            Trace.show("Commands " + (cmdEntries == null ? 0 : cmdEntries.length));
            display.setCurrent(cmdList);
        } else if (Ui.COMMANDS && d == cmdList && c == List.SELECT_COMMAND) {
            int i = cmdList.getSelectedIndex();
            if (i < 0 || cmdEntries == null || i >= cmdEntries.length) {
                return;
            }
            String text = cmdEntries[i];
            if (text.equals("/rename")) {
                // Waits for the name, as on the web.
                reply.setString(text + " ");
                Trace.show("Reply");
                display.setCurrent(reply);
            } else {
                display.setCurrent(view);
                start(SEND, text);
            }
        } else if (Ui.HOSTS && d == view && c == closeCmd) {
            confirming = CLOSE;
            ask("Close the pane on " + hostOf(view.address) + "? What runs in it ends; a session ends with its last pane.",
                new String[] {"Close", "Keep"});
        } else if (Ui.HOSTS && d == confirm && (c == List.SELECT_COMMAND || c == back)) {
            int i = c == back ? -1 : confirm.getSelectedIndex();
            display.setCurrent(confirming == CLOSE ? (Displayable) view : panes);
            if (confirming == SPLIT && i >= 0 && i < 2) {
                start(SPLIT, i == 0 ? "h" : "v");
            } else if (confirming == CLOSE && i == 0) {
                start(CLOSE, null);
            }
        } else if (d == panes && c == refreshCmd) {
            start(LIST, null);
        } else if (Ui.HOSTS && d == panes && c == autoCmd) {
            listAuto = !listAuto;
            alert(listAuto ? "Auto refresh every " + interval() + " s." : "Auto refresh off.", panes);
            if (listAuto) {
                pollList();
            }
        } else if (Ui.DESK && d == panes && c == deskCmd) {
            start(DESK, null);
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
        } else if (Ui.MAIL && what == PaneView.MESSAGES) {
            messages(view);
        } else if (what == PaneView.PREVIOUS || what == PaneView.NEXT) {
            // The list's neighbour, round at either end.
            int i = addresses.indexOf(view.address);
            int n = addresses.size();
            if (i >= 0 && n > 1) {
                open((i + (what == PaneView.NEXT ? 1 : n - 1)) % n);
            }
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

    /** Reads the feed; Back on Messages returns to from. MIDP 2.0 build only. */
    void messages(Displayable from) {
        mail.from = from;
        start(TALK, null);
    }

    /** Reads a pane's mail; Back on the list returns to where. A pane the list does not know is not read. */
    void mail(String address, Displayable where) {
        int i = addresses.indexOf(address);
        if (i < 0) {
            return;
        }
        mail.address = address;
        mail.name = (String) names.elementAt(i);
        mail.writable = ((Boolean) writable.elementAt(i)).booleanValue();
        mail.to = where;
        start(MAIL, null);
    }

    /** Posts the box's text to the pane it is aimed at. */
    void mailTo(String text) {
        start(MAILTO, text);
    }

    /** The list's name for a pane it holds. */
    String nameOf(String address) {
        int i = addresses.indexOf(address);
        return i >= 0 ? (String) names.elementAt(i) : address;
    }

    /** A question with its answers as a list; the pick comes back through confirming. MIDP 2.0 build only. */
    private void ask(String question, String[] answers) {
        confirm.setTitle(question);
        for (int i = confirm.size() - 1; i >= 0; i--) {
            confirm.delete(i);
        }
        for (int i = 0; i < answers.length; i++) {
            confirm.append(answers[i], null);
        }
        Trace.show("Confirm " + question);
        display.setCurrent(confirm);
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

    /**
     * Opens the list's pane with that id where the list knows it, else the pane at the address: an
     * inbox row read after the host renumbered names the pane the phone's list has elsewhere.
     */
    void open(String address, String id) {
        int i = Ui.IDS && id.length() > 0 ? ids.indexOf(id) : -1;
        if (i >= 0) {
            open(i);
        } else {
            open(address);
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
        if (Ui.THEME) {
            prefs.theme = themeChoice.getSelectedIndex();
            Trace.show("Theme " + prefs.theme);
        }
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
        if (Ui.THEME) {
            themeChoice.setSelectedIndex(prefs.theme, true);
        }
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
        if (Ui.THEME) {
            view.theme = new Theme(prefs.theme);
            Trace.show("Theme " + prefs.theme);
        }
        view.address = address;
        view.title = address.substring(address.indexOf('/') + 1);
        view.name = (String) names.elementAt(i);
        view.command = (String) commands.elementAt(i);
        view.writable = canWrite;
        if (Ui.IDS) {
            view.id = (String) ids.elementAt(i);
        }
        if (Ui.NAMES) {
            view.mark = (String) marks.elementAt(i);
        }
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
        if (Ui.MAIL && hostHas(address, "mail")) {
            view.addCommand(mailCmd);
        }
        view.addCommand(paneCmd);
        if (Ui.LATER && canWrite) {
            view.addCommand(view.later ? wakeCmd : laterCmd);
        }
        if (Ui.COMMANDS && canWrite) {
            cmdEntries = Commands.of(view.command, view.mode == 2);
            if (cmdEntries != null) {
                view.addCommand(commandsCmd);
            }
        }
        view.addCommand(back);
        view.addCommand(settings);
        if (Ui.HOSTS && canWrite && hosts.flag(hosts.names.indexOf(hostOf(address)), "close")) {
            view.addCommand(closeCmd);
        }
        view.addCommand(exit);
        view.setCommandListener(this);
        auto = false;
        Trace.show("View " + address + (canWrite ? " writable" : " read-only") + (Ui.IDS && view.id.length() > 0 ? " " + view.id : ""));
        display.setCurrent(view);
        start(OPEN, null);
    }

    /** True when the host's flags say so, or say nothing at all (a hub older than the column). MIDP 2.0 build only. */
    private boolean hostHas(String address, String word) {
        int h = hosts.names.indexOf(hostOf(address));
        return h < 0 || ((String) hosts.flags.elementAt(h)).trim().length() == 0 || hosts.flag(h, word);
    }

    private int interval() {
        return prefs.refresh > 0 ? prefs.refresh : 10;
    }

    /**
     * Runs one job on its own thread; the UI thread must not touch the network.
     * MIDP has no request timeout, so the spinner gives up on a job that hangs
     * and the app stays usable; a late answer from it is then ignored.
     */
    private synchronized boolean start(int what, String with) {
        if (busy) {
            if (what != POLL && (!Ui.HOSTS || what != LISTQ)) {
                alert("Still waiting for the last answer.", display.getCurrent());
            }
            return false;
        }
        busy = true;
        job = what;
        arg = with;
        final int mine = ++serial;
        // The reads that have no screen yet show the wait form; the rest turn the mark on the pane, a poll nothing.
        boolean form = what == LIST;
        String note = "Loading panes...";
        if (Ui.INBOX && what == INBOX) {
            form = true;
            note = "Loading inbox...";
        }
        if (Ui.MAIL && (what == TALK || what == MAIL || what == MAILTO)) {
            form = true;
            note = what == TALK ? "Loading messages..." : what == MAILTO ? "Sending mail..." : "Loading mail...";
        }
        if (Ui.DESK && what == DESK) {
            form = true;
            note = "Reading the desk...";
        }
        if (form) {
            waitNote.setText(note);
            display.setCurrent(wait);
        }
        int where = form ? 0 : what == POLL ? 2 : 1;
        if (Ui.HOSTS && what == LISTQ) {
            where = 2;
        }
        spin(where, mine, what == UPLOAD ? UPLOAD_TIMEOUT : TIMEOUT);
        new Thread(this).start();
        return true;
    }

    private synchronized void giveUp(int which) {
        if (busy && serial == which) {
            busy = false;
            serial++;
            alert("No answer from the server.", where());
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

    /**
     * Turns the wait mark while the job runs (where: 0 the wait form's bar,
     * 1 the pane's corner, 2 nowhere), and gives the job up once it has run
     * for patience.
     */
    private void spin(final int where, final int mine, final long patience) {
        new Thread() {
            public void run() {
                int step = 0;
                while (busy) {
                    if (where == 0 && frames != null) {
                        waitBar.setImage(frames[step % frames.length]);
                    } else if (where == 1 && view != null) {
                        view.busy(step);
                    }
                    step++;
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                    }
                    if (step * 150L >= patience) {
                        giveUp(mine);
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
            default:
                if (Ui.HOSTS || Ui.MAIL || Ui.DESK) {
                    more();
                }
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
        if ((Ui.HOSTS || Ui.DESK) && openNext >= 0) {
            // A pane a job just made, or the desk's: opened after the job, since a job runs alone.
            int i = openNext;
            openNext = -1;
            open(i);
        }
    }

    private String etag = "";

    private void list() throws IOException {
        // The hosts first on the 2.0 build: their lines head the rows.
        String hostRows = null;
        if (Ui.HOSTS && api.call("hosts", null, null) == 200) {
            hostRows = api.body;
        }
        if (api.call("panes" + (charset().length() > 0 ? "?" + charset() : ""), null, null) != 200) {
            problem(api.status);
            return;
        }
        String[] rows = Text.split(api.body, '\n');
        String keep = Ui.HOSTS ? selected() : null;
        for (int i = panes.size() - 1; i >= 0; i--) {
            panes.delete(i);
        }
        addresses.removeAllElements();
        names.removeAllElements();
        commands.removeAllElements();
        writable.removeAllElements();
        if (Ui.IDS) {
            ids.removeAllElements();
        }
        if (Ui.NAMES) {
            marks.removeAllElements();
        }
        if (Ui.LATER) {
            later.removeAllElements();
        }
        // Hosts are named in the row only when there is more than one and no host lines.
        String host = null;
        boolean several = false;
        for (int i = 0; i < rows.length && !Ui.HOSTS; i++) {
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
            // The name, and the row with the unread mail after it, from the sixth column where the hub sends one.
            String name = Text.label(f, several, "", prefs.text != 2);
            String count = Ui.TALK && f.length > 5 ? Text.mail(f[5], prefs.text == 0) : "";
            String row = Ui.TALK && count.length() > 0 ? Text.label(f, several, count, prefs.text != 2) : name;
            // Then what the pane waits for, from the kind column: ? ! z v, zz for a parked pane,
            // and after zz what waits underneath, from the pending column.
            String kind = f.length > 4 ? f[4].trim() : "";
            String mark = Ui.NAMES ? Text.marks(kind, f.length > 9 ? f[9].trim() : "") : Text.mark(kind);
            String label = (w ? "*" : " ") + mark + (mark.length() > 1 ? " " : "") + row;
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
            if (Ui.IDS) {
                // The pane's id, the row's last column; a row without one, or with one that is no id, gives none.
                ids.addElement(f.length > 10 && Text.paneId(f[10].trim()) ? f[10].trim() : "");
            }
            if (Ui.NAMES) {
                marks.addElement(mark);
            }
            if (Ui.LATER) {
                later.addElement(new Boolean(kind.equals("later")));
            }
        }
        if (Ui.HOSTS) {
            fill(hostRows, labels, talk, shown);
            reselect(keep);
        } else if (api.call("hosts", null, null) == 200) {
            String[] lines = Text.split(api.body, '\n');
            for (int i = 0; i < lines.length; i++) {
                String[] f = Text.split(lines[i], '\t');
                if (f.length >= 2 && f[1].equals("offline")) {
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
        // A quiet re-read leaves a screen opened meanwhile alone.
        if (!Ui.HOSTS || job == LIST || display.getCurrent() == panes) {
            display.setCurrent(panes);
        }
    }

    /**
     * Fills the list host by host from the hosts call (name, state, flags;
     * a host the rows name that the call did not is online): a line per
     * host, then its rows, a group's rows together within the host behind
     * the frame's edge, and the parallel Vectors in the same order so a
     * select opens the row it names. MIDP 2.0 build only.
     */
    private void fill(String body, Vector labels, Vector talk, StringBuffer shown) {
        hosts.read(body);
        int n = addresses.size();
        for (int i = 0; i < n; i++) {
            hosts.add(hostOf((String) addresses.elementAt(i)));
        }
        int[] group = Talk.groups(addresses, talk);
        boolean framed = Talk.any(group);
        // The parallel vectors, the ids among them, so a view opened from a regrouped row gets its own pane's id.
        Vector[] kept = Ui.IDS ? new Vector[] {addresses, names, commands, writable, later, marks, ids}
            : new Vector[] {addresses, names, commands, writable, later, marks};
        Object[][] was = new Object[kept.length][];
        for (int k = 0; k < kept.length; k++) {
            was[k] = new Object[kept[k].size()];
            kept[k].copyInto(was[k]);
            kept[k].removeAllElements();
        }
        int first = -1;
        boolean sessions = false, writes = false;
        for (int h = 0; h < hosts.names.size(); h++) {
            String name = (String) hosts.names.elementAt(h);
            int[] member = new int[n];
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (hostOf((String) was[0][i]).equals(name)) {
                    member[m++] = i;
                }
            }
            String state = !((Boolean) hosts.online.elementAt(h)).booleanValue() ? "offline" : m > 0 ? "online" : "nothing exposed";
            String line = "= " + name + " " + state;
            panes.append(line, null);
            sessions |= hosts.flag(h, "sessions");
            shown.append(line).append('\n');
            hosts.rows.addElement(new Integer(-1 - h));
            int[] sub = new int[m];
            for (int p = 0; p < m; p++) {
                sub[p] = group[member[p]];
            }
            int[] order = Talk.order(sub);
            for (int p = 0; p < m; p++) {
                int i = member[order[p]];
                String label = (framed ? String.valueOf(Talk.edge(sub, order, p)) : "") + (String) labels.elementAt(i);
                panes.append(label, null);
                shown.append(label).append('\n');
                if (first < 0) {
                    first = panes.size() - 1;
                }
                hosts.rows.addElement(new Integer(addresses.size()));
                for (int k = 0; k < kept.length; k++) {
                    kept[k].addElement(was[k][i]);
                }
                writes |= ((Boolean) was[3][i]).booleanValue();
            }
        }
        if (first >= 0) {
            panes.setSelectedIndex(first, true);
        }
        // The menu offers what some host or row allows; the pick says when the one chosen does not.
        panes.removeCommand(sessionCmd);
        panes.removeCommand(splitCmd);
        if (sessions) {
            panes.addCommand(sessionCmd);
        }
        if (writes) {
            panes.addCommand(splitCmd);
        }
    }

    /** The host of list row r: a pane row's, or the line's; -1 for no row. */
    private int hostAt(int r) {
        int i = hosts.at(r);
        if (i == Integer.MIN_VALUE) {
            return -1;
        }
        return i >= 0 ? hosts.names.indexOf(hostOf((String) addresses.elementAt(i))) : -1 - i;
    }

    private static String hostOf(String address) {
        return address.substring(0, address.indexOf('/'));
    }

    /** The pane under the cursor, or the host of its line, so a re-read keeps the place. */
    private String selected() {
        int i = hosts.at(panes.getSelectedIndex());
        return i == Integer.MIN_VALUE ? null : i >= 0 ? (String) addresses.elementAt(i) : (String) hosts.names.elementAt(-1 - i);
    }

    private void reselect(String keep) {
        for (int r = 0; keep != null && r < hosts.rows.size(); r++) {
            int i = hosts.at(r);
            if (keep.equals(i >= 0 ? addresses.elementAt(i) : hosts.names.elementAt(-1 - i))) {
                panes.setSelectedIndex(r, true);
                return;
            }
        }
    }

    /** The query parameter for the Text setting; empty for Unicode. */
    private String charset() {
        return prefs.text == 1 ? "charset=latin1" : prefs.text == 2 ? "charset=ascii" : "";
    }

    private void read(String ifNoneMatch) throws IOException {
        // The view asked for: an answer that comes after another pane was opened is nobody's and is dropped.
        PaneView v = view;
        if (v == null) {
            return;
        }
        // The rows as they are on the terminal; the phone wraps them itself.
        String path = "panes/" + v.address + "?lines=" + Ui.LINES + "&width=400"
            + (charset().length() > 0 ? "&" + charset() : "");
        int status = api.call(path, null, ifNoneMatch);
        if (v != view) {
            return;
        }
        if (Ui.IDS && (status == 200 || status == 304) && v.id.length() > 0 && Text.paneId(api.pane) && !api.pane.equals(v.id)) {
            // Another pane answered at the view's address: the host renumbered when a pane closed. Nothing of
            // it is drawn; the list says where the view's pane is now. A header that is no id is unknown.
            Trace.show("Stranger " + api.pane + " at " + v.address);
            moved(v);
            return;
        }
        if (status == 304) {
            return;
        }
        if (status != 200) {
            problem(status);
            return;
        }
        etag = api.etag;
        v.setText(api.body);
    }

    /**
     * The view's pane is not at its address any more. The list again, quietly; then the view follows its
     * pane to the address the list names and reads once more, or, listed nowhere, is gone. A stranger
     * answering the second read too ends it the same way, so a hub or a relay that never agrees cannot
     * send the phone round in a circle. MIDP 2.0 build only.
     */
    private void moved(PaneView v) throws IOException {
        list();
        int k = v == view ? ids.indexOf(v.id) : -1;
        if (k >= 0) {
            String address = (String) addresses.elementAt(k);
            Trace.show("Follow " + v.id + " " + v.address + " " + address);
            v.address = address;
            v.title = address.substring(address.indexOf('/') + 1);
            v.name = (String) names.elementAt(k);
            v.command = (String) commands.elementAt(k);
            if (Ui.NAMES) {
                v.mark = (String) marks.elementAt(k);
            }
            if (Ui.LATER) {
                v.later = ((Boolean) later.elementAt(k)).booleanValue();
            }
            // One more read, checked once: the pane's own rows, or a stranger again, which is the end.
            int status = api.call("panes/" + address + "?lines=" + Ui.LINES + "&width=400"
                + (charset().length() > 0 ? "&" + charset() : ""), null, null);
            if (v != view) {
                return;
            }
            boolean stranger = (status == 200 || status == 304) && Text.paneId(api.pane) && !api.pane.equals(v.id);
            if (!stranger) {
                if (status == 200) {
                    etag = api.etag;
                    v.setText(api.body);
                } else if (status != 304) {
                    problem(status);
                }
                return;
            }
            Trace.show("Stranger " + api.pane + " at " + address);
        }
        auto = false;
        view = null;
        display.setCurrent(panes);
        alert("This pane is gone.", panes);
    }

    private void readInbox() throws IOException {
        if (api.call("inbox" + (charset().length() > 0 ? "?" + charset() : ""), null, null) != 200) {
            problem(api.status);
            return;
        }
        inbox.fill(api.body, addresses, names, writable);
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

    /** Starts a session on the host the line named; its first pane opens from the re-read list. */
    private void session() throws IOException {
        int status = api.call("hosts/" + hosts.session + "/sessions", Api.json("name", arg), null);
        Trace.show("Session " + status);
        if (status == 404) {
            alert("This host does not start sessions.", panes);
        } else if (status == 409) {
            alert("exists".equals(Text.jsonString(api.body, "error")) ? "That name exists." : "Too many sessions.", panes);
        } else if (status != 201) {
            problem(status);
        } else {
            opened(api.body);
        }
    }

    /** The jobs of the 2.0 build's screens: the list's timer, sessions, messages, mail, splits and closes. */
    private void more() throws IOException {
        switch (job) {
        case LISTQ:
            list();
            break;
        case SESSION:
            session();
            break;
        case TALK:
            readMessages();
            break;
        case MAIL:
            readMail();
            break;
        case MAILTO:
            sendMail();
            break;
        case SPLIT:
            split();
            break;
        case CLOSE:
            close();
            break;
        case DESK:
            desk();
            break;
        }
    }

    /**
     * The desk, the view the owner's clients share, read from the hub and
     * never written: its focused pane opens, else the first of its panes,
     * else the first pane of its window in the list. Only a pane the list
     * holds opens, so a desk can name nothing the hub did not list for
     * this token. MIDP 2.0 build only.
     */
    private void desk() throws IOException {
        int status = api.call("desk", null, null);
        if (status == 404) {
            alert("This hub has no desk.", panes);
            return;
        }
        if (status != 200) {
            problem(status);
            return;
        }
        String want = Text.deskFocus(api.body);
        Trace.show("Desk " + (want == null ? "empty" : want));
        if (want == null) {
            alert("The desk is empty.", panes);
            return;
        }
        int i = deskRow(want);
        if (i < 0) {
            // The list may be stale: once more from the hub.
            display.setCurrent(panes);
            list();
            i = deskRow(want);
        }
        if (i < 0) {
            alert("The desk's pane is not in the list.", panes);
            return;
        }
        reselect((String) addresses.elementAt(i));
        openNext = i;
    }

    /** The list's row for the desk's pick: the address, or for a window (a trailing dot) its first pane. */
    private int deskRow(String want) {
        if (!want.endsWith(".")) {
            return addresses.indexOf(want);
        }
        for (int i = 0; i < addresses.size(); i++) {
            if (((String) addresses.elementAt(i)).startsWith(want)) {
                return i;
            }
        }
        return -1;
    }

    private void readMessages() throws IOException {
        if (api.call("talk?limit=100", null, null) != 200) {
            problem(api.status);
            return;
        }
        mail.unicode = prefs.text == 0;
        mail.fillMessages(api.body, addresses, names);
    }

    /** The pane's mailbox, then the feed for what it sent; a host without mail says so. */
    private void readMail() throws IOException {
        int status = api.call("panes/" + mail.address + "/mail" + (charset().length() > 0 ? "?" + charset() : ""), null, null);
        if (status == 404) {
            alert("This host holds no mail.", mail.to);
            return;
        }
        if (status != 200) {
            problem(status);
            return;
        }
        String mailbox = api.body;
        String feed = api.call("talk?limit=100", null, null) == 200 ? api.body : "";
        mail.unicode = prefs.text == 0;
        mail.fillMail(mailbox, feed, addresses, names, writable);
    }

    /** The box's text to the pane it is aimed at, as a mail: never typed, and from no pane. */
    private void sendMail() throws IOException {
        int status = api.call("panes/" + mail.target + "/mail", Api.json("text", arg), null);
        Trace.show("Mailed " + mail.target + " " + status);
        if (status == 201) {
            readMail();
            alert("Mailed.", mail.list);
        } else if (status == 404) {
            alert("This host holds no mail.", mail.list);
        } else if (status == 409) {
            alert("The mailbox is full.", mail.list);
        } else {
            problem(status);
        }
    }

    /** One more pane in the chosen pane's window, beside or below; the new pane opens from the re-read list. */
    private void split() throws IOException {
        String a = splitting;
        String rest = a.substring(a.indexOf('/') + 1);
        int dot = rest.lastIndexOf('.');
        if (dot < 0) {
            alert("Not a window.", panes);
            return;
        }
        int status = api.call("windows/" + hostOf(a) + "/" + rest.substring(0, dot) + "/panes", "{\"direction\":\"" + arg + "\"}", null);
        Trace.show("Split " + status);
        if (status == 201) {
            opened(api.body);
        } else if (status == 409) {
            alert("The window is full.", panes);
        } else {
            problem(status);
        }
    }

    /** Closes the open pane on its host; the list is read again without it. */
    private void close() throws IOException {
        int status = api.call("panes/" + view.address + "/close", "{}", null);
        Trace.show("Close " + status);
        if (status == 204 || status == 200) {
            auto = false;
            display.setCurrent(panes);
            list();
        } else if (status == 404) {
            alert("This host does not close panes.", view);
        } else {
            problem(status);
        }
    }

    /** A pane the hub just made, named in its JSON answer: the list is read again and the pane opened. */
    private void opened(String body) throws IOException {
        String address = Text.jsonString(body, "address");
        list();
        if (address != null && Text.address(address) && addresses.indexOf(address) >= 0) {
            // The cursor moves to it, so the list's next action is on the pane just made.
            reselect(address);
            openNext = addresses.indexOf(address);
        }
    }

    private void parked(boolean on) {
        view.later = on;
        view.removeCommand(on ? laterCmd : wakeCmd);
        view.addCommand(on ? wakeCmd : laterCmd);
        if (Ui.NAMES) {
            // The overlay's mark follows: what waited stays under zz, and comes back when woken.
            String m = view.mark.trim();
            if (on && !m.startsWith("zz")) {
                view.mark = "zz" + (m.length() > 0 ? " " + m : "");
            } else if (!on && m.startsWith("zz")) {
                view.mark = m.length() > 3 ? m.substring(3) : " ";
            }
        }
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

    /** Re-reads the list every interval while Auto is on there and it is the screen shown. MIDP 2.0 build only. */
    private void pollList() {
        final int serial = ++listPollSerial;
        new Thread() {
            public void run() {
                while (listAuto && serial == listPollSerial) {
                    try {
                        Thread.sleep(interval() * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (listAuto && serial == listPollSerial && display.getCurrent() == panes) {
                        Muxway.this.start(LISTQ, null);
                    }
                }
            }
        }.start();
    }

    private void problem(int status) throws IOException {
        if (status == 401) {
            auto = false;
            prefs.token = "";
            prefs.save();
            alert(Api.problem(status) + " Enter a new one.", setup);
            fillSetup();
            return;
        }
        if (status == 404 && view != null && job >= OPEN && job <= KEY) {
            // The open pane is gone: its last rows are nobody's. The list again, then the word over it.
            auto = false;
            view = null;
            if (Ui.HOSTS) {
                // The 2.0 list shows itself only when it is the screen already.
                display.setCurrent(panes);
            }
            list();
            alert("This pane is gone.", panes);
            return;
        }
        Displayable next = view != null && display.getCurrent() != panes && job != LIST ? (Displayable) view : panes;
        if (Ui.HOSTS && (job == LISTQ || job == SESSION || job == SPLIT)) {
            next = panes;
        }
        if (Ui.DESK && job == DESK) {
            next = panes;
        }
        if (Ui.MAIL && (job == TALK || job == MAIL || job == MAILTO)) {
            next = where();
        }
        if (job == LIST && panes.size() == 0) {
            next = setup;
        }
        if (Ui.INBOX && (job == INBOX || job == ANSWER)) {
            next = job == ANSWER || inbox.list.size() > 0 ? (Displayable) inbox.list : inbox.from;
        }
        alert(Api.problem(status), next);
    }

    /** The screen a job that got no answer returns to. */
    private Displayable where() {
        Displayable next = job == LIST ? (Displayable) setup : view;
        if (Ui.INBOX && (job == INBOX || job == ANSWER)) {
            next = inbox.from;
        }
        if (Ui.HOSTS && (job == LISTQ || job == SESSION || job == SPLIT)) {
            next = panes;
        }
        if (Ui.DESK && job == DESK) {
            next = panes;
        }
        if (Ui.MAIL && (job == TALK || job == MAIL || job == MAILTO)) {
            next = job == TALK ? mail.from : job == MAIL ? mail.to : mail.list;
        }
        return next == null ? setup : next;
    }

    private void fail(String message) {
        Displayable next = where();
        if (next == setup) {
            fillSetup();
        }
        alert(message, next);
    }

    void alert(final String text, final Displayable next) {
        final Alert a = new Alert("muxway", text, null, AlertType.INFO);
        a.setTimeout(Alert.FOREVER);
        if (!Ui.ALERT_SERIAL) {
            Trace.show("Alert: " + text);
            display.setCurrent(a, next);
            return;
        }
        // In its turn, after the screen changes asked for before it: an emulator that shows an
        // alert at once and every other screen in turn put the screen behind this one on top of it.
        display.callSerially(new Runnable() {
            public void run() {
                display.setCurrent(a, next);
                if (Trace.on) {
                    traceAlert(a, text);
                }
            }
        });
    }

    /**
     * Traces the alert on screen, and once more when it has left it, so a test dismisses it no
     * earlier than it is there and moves on no earlier than the screen behind it is back: the
     * emulator switches screens on a thread of its own. Trace mode of the MIDP 2.0 build only.
     */
    private void traceAlert(final Alert a, final String text) {
        Trace.show("Alert: " + text);
        new Thread() {
            public void run() {
                for (int i = 0; display.getCurrent() == a && i < 30000; i++) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                    }
                }
                Trace.show("Alert gone: " + text);
            }
        }.start();
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
