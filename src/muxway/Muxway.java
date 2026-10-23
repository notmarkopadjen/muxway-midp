package muxway;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/** muxway for Java ME phones. */
public final class Muxway extends MIDlet implements CommandListener {
    private final Prefs prefs = new Prefs(Ui.WIDTH);
    private Display display;

    private final Form setup = new Form("Settings");
    private final TextField server = new TextField("Server", "", 120, TextField.URL);
    private final TextField token = new TextField("Token", "", 64, TextField.PASSWORD);
    private final TextField width = new TextField("Width", "", 3, TextField.NUMERIC);
    private final TextField refresh = new TextField("Auto refresh, s", "", 3, TextField.NUMERIC);
    private final Form home = new Form("muxway");
    private final Command ok = new Command("OK", Command.OK, 1);
    private final Command settings = new Command("Settings", Command.SCREEN, 5);
    private final Command exit = new Command("Exit", Command.EXIT, 9);

    public Muxway() {
        setup.append(server);
        setup.append(token);
        setup.append(width);
        setup.append(refresh);
        setup.addCommand(ok);
        setup.addCommand(exit);
        setup.setCommandListener(this);
        home.addCommand(settings);
        home.addCommand(exit);
        home.setCommandListener(this);
    }

    protected void startApp() {
        if (display != null) {
            return;
        }
        display = Display.getDisplay(this);
        prefs.load();
        if (!prefs.ready()) {
            // Installed from a JAD that carries the settings.
            String s = getAppProperty("Muxway-Server");
            String t = getAppProperty("Muxway-Token");
            if (s != null && t != null && s.length() > 0 && t.length() > 0) {
                prefs.server = s.trim();
                prefs.token = t.trim();
                prefs.save();
            }
        }
        if (prefs.ready()) {
            showHome();
        } else {
            showSetup();
        }
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exit) {
            notifyDestroyed();
        } else if (c == settings) {
            showSetup();
        } else if (d == setup && c == ok) {
            prefs.server = server.getString().trim();
            String t = token.getString().trim();
            if (t.length() > 0) {
                prefs.token = t;
            }
            prefs.width = Math.max(8, Math.min(400, Text.toInt(width.getString(), Ui.WIDTH)));
            prefs.refresh = Math.max(0, Math.min(600, Text.toInt(refresh.getString(), 0)));
            prefs.save();
            if (prefs.ready()) {
                showHome();
            }
        }
    }

    private void showSetup() {
        server.setString(prefs.server);
        // The stored token is never shown again; an empty field keeps it.
        token.setString("");
        width.setString(Integer.toString(prefs.width));
        refresh.setString(Integer.toString(prefs.refresh));
        display.setCurrent(setup);
    }

    private void showHome() {
        for (int i = home.size() - 1; i >= 0; i--) {
            home.delete(i);
        }
        home.append(new StringItem(null, "Server: " + prefs.server + "\nPanes come next."));
        display.setCurrent(home);
    }
}
