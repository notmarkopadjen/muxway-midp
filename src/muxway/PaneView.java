package muxway;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * One pane, drawn like a terminal over the whole screen: its last rows in a
 * monospace font, bottom-aligned, with the rows a lens pinned under them.
 * Title and scroll position are an overlay shown on #. The size is taken
 * from the first full paint, because Canvas.getWidth binds to a class MIDP
 * 1.0 phones do not have it on. Rows arrive on the network thread and are
 * drawn on the phone's, so shaping and scrolling hold the view's lock,
 * and nothing asks for a repaint while holding it: on a real phone
 * repaint() takes the display's own lock, the one paint() runs under.
 */
final class PaneView extends Canvas {
    static final int ENTER = 1, REPLY = 2, KEYS = 3, REFRESH = 4, IMAGE = 5, INBOX = 6, PREVIOUS = 7, NEXT = 8, MESSAGES = 9;

    // The green call key on Nokia phones; it sends Enter.
    private static final int SEND_KEY = -10;

    private final Font font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    // The overlay's name while the pane is parked; the phone gives its plain face when it has no italics.
    private Font italic;
    private final Image[] frames;
    private final Muxway app;
    // The canvas's colours; MIDP 2.0 build only, the 1.0 build is black on white.
    Theme theme;

    String title = "";
    // What the list called the pane; the overlay shows it.
    String name = "";
    String address = "";
    boolean writable;
    // What the pane waits for, as the list marked it: ? ! z v, zz, zz !, or a space.
    String mark = " ";
    // The host takes files for this pane, as the probe found.
    boolean inbox;
    // Parked for later by its owner; the overlay says so.
    boolean later;
    // True once the view holds rows of its own; before that it draws the warm-up or nothing, never a borrowed screen.
    private boolean lit;
    private boolean warmed;
    Lens[] lenses = new Lens[0];
    // The program in the pane, as the hub named it; Auto picks a lens by it first.
    String command = "";
    // Wrap width in columns; 0 fits the screen by measured pixels.
    int cols;
    // 0 asks every lens, 1 none, 2 and up forces one lens by its number.
    int mode;

    private String[] raw = new String[0];
    private String[] body = new String[0];
    private String[] pinned = new String[0];
    private int shapedCols = -1;
    private int shapedWidth = -1;
    private int[] shapedWidths;
    // Pixel width of each character below 256, measured on the paint thread.
    private int[] widths;
    private int offset;
    private boolean reveal;
    private boolean info;
    private int width;
    private int height;
    private int frame = -1;

    PaneView(Image[] frames, Muxway app) {
        this.frames = frames;
        this.app = app;
        if (Ui.LATER) {
            italic = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_ITALIC, Font.SIZE_SMALL);
        }
    }

    /** Columns that fit, or 0 before the first paint. */
    int columns() {
        int w = font.charWidth('M');
        return width > 0 && w > 0 ? Math.max(8, width / w) : 0;
    }

    /** True once the first paint has measured the screen, and the font when rows are wrapped by pixels. */
    boolean ready() {
        return width > 0 && (cols > 0 || widths != null);
    }

    void setText(String text) {
        String[] all = Text.split(text, '\n');
        int n = all.length;
        while (n > 0 && all[n - 1].length() == 0) {
            n--;
        }
        Trace.show(title + ": " + text);
        synchronized (this) {
            if (Ui.WARMUP) {
                lit = true;
            }
            raw = new String[n];
            System.arraycopy(all, 0, raw, 0, n);
            offset = 0;
            shape();
        }
        show();
    }

    /** The waiting mark in the top right corner; -1 hides it. */
    void busy(int step) {
        frame = step;
        repaint();
    }

    /** Changes the view (see mode) and redraws. */
    void setMode(int m) {
        synchronized (this) {
            mode = m;
            shape();
        }
        show();
    }

    private void toggleReveal() {
        synchronized (this) {
            reveal = !reveal;
            // What was hidden sits at the bottom: show it.
            offset = 0;
            shape();
        }
        show();
    }

    private synchronized void shape() {
        String[][] shaped = null;
        if (mode >= 2 && mode - 2 < lenses.length) {
            shaped = lenses[mode - 2].shape(raw, reveal, true);
        } else if (mode == 0) {
            for (int i = 0; i < lenses.length && shaped == null; i++) {
                if (lenses[i].runs(command)) {
                    shaped = lenses[i].shape(raw, reveal, true);
                }
            }
            for (int i = 0; i < lenses.length && shaped == null; i++) {
                shaped = lenses[i].shape(raw, reveal, false);
            }
        }
        shapedCols = cols;
        shapedWidth = width;
        shapedWidths = widths;
        body = wrap(shaped == null ? raw : shaped[0]);
        pinned = wrap(shaped == null ? new String[0] : shaped[1]);
        offset = Math.min(offset, Math.max(0, body.length - bodyRows()));
    }

    /** At the set columns, or by pixels: the phone's "monospace" face is not. */
    private String[] wrap(String[] rows) {
        if (cols > 0) {
            return Text.wrap(rows, cols);
        }
        return widths == null ? rows : Text.wrap(rows, widths, widths['M'], width);
    }

    private int rows() {
        return Math.max(1, height / font.getHeight());
    }

    private int pinRows() {
        return Math.min(pinned.length, rows() / 2);
    }

    private int bodyRows() {
        return rows() - pinRows();
    }

    /** What is on screen, top to bottom: the body ends where the pinned rows start. */
    private synchronized String[] visible() {
        int n = rows(), pin = pinRows(), top = n - pin;
        String[] out = new String[n];
        int last = body.length - offset;
        for (int i = 0; i < top; i++) {
            int j = last - top + i;
            out[i] = j >= 0 && j < body.length ? body[j] : "";
        }
        for (int i = 0; i < pin; i++) {
            out[top + i] = pinned[pinned.length - pin + i];
        }
        return out;
    }

    private void show() {
        trace();
        repaint();
    }

    private void trace() {
        if (Trace.on) {
            String[] v = visible();
            StringBuffer s = new StringBuffer("Rows: ");
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    s.append('\n');
                }
                s.append(v[i]);
            }
            Trace.show(s.toString());
        }
    }

    protected void paint(Graphics g) {
        if (g.getClipX() == 0 && g.getClipY() == 0) {
            width = Math.max(width, g.getClipWidth());
            height = Math.max(height, g.getClipHeight());
        }
        if (widths == null && width > 0 && cols == 0) {
            int[] measured = new int[256];
            for (int c = 0; c < measured.length; c++) {
                measured[c] = font.charWidth((char) c);
            }
            widths = measured;
        }
        if (shapedCols != cols || shapedWidth != width || shapedWidths != widths) {
            shape();
            trace();
        }
        int w = width, h = height, lh = font.getHeight(), n = rows();
        int y0 = h - n * lh;
        g.setColor(Ui.THEME ? theme.bg : 0xffffff);
        g.fillRect(0, 0, w, h);
        g.setColor(Ui.THEME ? theme.ink : 0x000000);
        g.setFont(font);
        if (Ui.WARMUP && !lit) {
            if (frame >= 0) {
                warmUp(g, w, h);
            }
        } else {
            String[] v = visible();
            for (int i = 0; i < n; i++) {
                g.drawString(v[i], 0, y0 + i * lh, Graphics.TOP | Graphics.LEFT);
            }
        }
        if (info) {
            String lead = lead();
            String shown = name.length() > 0 ? name : title;
            // The right end says read-only where the token cannot write; a header says so on every client.
            String right = Ui.NAMES && !writable ? "read-only" : null;
            if (Ui.LATER && later) {
                // The mark and the slant are the only signs of a parked pane.
                if (right != null && font.stringWidth(lead + " " + right) + italic.stringWidth(shown) > width) {
                    right = null;
                }
                bar(g, y0, lead, right);
                g.setColor(Ui.THEME ? theme.bg : 0xffffff);
                g.setFont(italic);
                g.drawString(shown, font.stringWidth(lead), y0, Graphics.TOP | Graphics.LEFT);
                g.setFont(font);
                g.setColor(Ui.THEME ? theme.ink : 0x000000);
            } else {
                bar(g, y0, lead + shown, right);
            }
            int last = body.length - offset;
            int first = Math.max(0, last - bodyRows());
            bar(g, y0 + (n - 1) * lh, (first + 1) + "-" + last + "/" + body.length,
                "1 2/8" + (Files.ANY ? " 3" : "") + " 5" + (Ui.MAIL ? " 7" : "") + (Ui.INBOX ? " 9" : "") + " 0 # *");
        }
        if (frame >= 0 && Ui.THEME) {
            // A turning ring in the ink, as the other clients draw working.
            g.setColor(theme.ink);
            g.drawArc(w - 9, 1, 7, 7, (frame * 45) % 360, 270);
        } else if (frame >= 0 && frames != null && frames.length > 0) {
            g.drawImage(frames[frame % frames.length], w, 0, Graphics.TOP | Graphics.RIGHT);
        }
    }

    /**
     * The tube coming on while a request runs and the view has no rows of its
     * own: a line at mid-height, half the width then whole, opening into a
     * band and settling to a line until the rows come. In the ink, 150 ms
     * a step with the spinner. MIDP 2.0 build only.
     */
    private void warmUp(Graphics g, int w, int h) {
        if (!warmed) {
            warmed = true;
            Trace.show("Warm-up");
        }
        int step = Math.min(frame, 4);
        int wide = step == 0 ? w / 2 : w;
        int tall = step == 2 ? 3 : step == 3 ? 5 : 1;
        g.fillRect((w - wide) / 2, h / 2 - tall / 2, wide, tall);
    }

    /** The overlay's top row before the name: the write mark, then what the pane waits for. */
    private String lead() {
        String lead = writable ? "> " : "  ";
        if (Ui.NAMES && mark.trim().length() > 0) {
            lead += mark + " ";
        }
        return lead;
    }

    /** One inverted row of the overlay; the hint on the right only when both fit. */
    private void bar(Graphics g, int y, String left, String right) {
        g.setColor(Ui.THEME ? theme.ink : 0x000000);
        g.fillRect(0, y, width, font.getHeight());
        g.setColor(Ui.THEME ? theme.bg : 0xffffff);
        g.drawString(left, 0, y, Graphics.TOP | Graphics.LEFT);
        if (right != null && font.stringWidth(left + " " + right) <= width) {
            g.drawString(right, width, y, Graphics.TOP | Graphics.RIGHT);
        }
        g.setColor(Ui.THEME ? theme.ink : 0x000000);
    }

    protected void keyPressed(int key) {
        switch (key) {
        case KEY_NUM1:
            app.shortcut(REFRESH);
            return;
        case KEY_NUM2:
            scroll(1);
            return;
        case KEY_NUM8:
            scroll(-1);
            return;
        case KEY_NUM3:
            if (Files.ANY && writable) {
                app.shortcut(IMAGE);
            }
            return;
        case KEY_NUM5:
            if (writable) {
                app.shortcut(REPLY);
            }
            return;
        case KEY_NUM0:
            if (writable) {
                app.shortcut(KEYS);
            }
            return;
        case KEY_NUM9:
            if (Ui.INBOX) {
                app.shortcut(INBOX);
            }
            return;
        case KEY_POUND:
            info = !info;
            String said = "Info " + (info ? "on" : "off") + (Ui.LATER && later ? " italic" : "");
            if (Ui.NAMES) {
                said += ": " + lead() + (name.length() > 0 ? name : title) + (writable ? "" : " read-only");
            }
            Trace.show(said);
            repaint();
            return;
        case KEY_STAR:
            toggleReveal();
            return;
        case SEND_KEY:
            if (writable) {
                app.shortcut(ENTER);
            }
            return;
        }
        if (Ui.MAIL && key == KEY_NUM7) {
            app.shortcut(MESSAGES);
            return;
        }
        int action = getGameAction(key);
        if (action == UP) {
            scroll(1);
        } else if (action == DOWN) {
            scroll(-1);
        } else if (action == LEFT) {
            app.shortcut(PREVIOUS);
        } else if (action == RIGHT) {
            app.shortcut(NEXT);
        }
    }

    protected void keyRepeated(int key) {
        if (key == KEY_NUM2 || key == KEY_NUM8) {
            keyPressed(key);
        } else {
            int action = getGameAction(key);
            if (action == UP || action == DOWN) {
                keyPressed(key);
            }
        }
    }

    private void scroll(int by) {
        synchronized (this) {
            int next = Math.max(0, Math.min(offset + by, Math.max(0, body.length - bodyRows())));
            if (next == offset) {
                return;
            }
            offset = next;
        }
        show();
    }
}
