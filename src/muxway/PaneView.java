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
    static final int ENTER = 1, REPLY = 2, KEYS = 3;

    // The green call key on Nokia phones; it sends Enter.
    private static final int SEND_KEY = -10;

    private final Font font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Image[] frames;
    private final Muxway app;

    String title = "";
    // What the list called the pane; the overlay shows it.
    String name = "";
    String address = "";
    boolean writable;
    Lens[] lenses = new Lens[0];
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
        g.setColor(0xffffff);
        g.fillRect(0, 0, w, h);
        g.setColor(0x000000);
        g.setFont(font);
        String[] v = visible();
        for (int i = 0; i < n; i++) {
            g.drawString(v[i], 0, y0 + i * lh, Graphics.TOP | Graphics.LEFT);
        }
        if (info) {
            bar(g, y0, (writable ? "> " : "  ") + (name.length() > 0 ? name : title), null);
            int last = body.length - offset;
            int first = Math.max(0, last - bodyRows());
            bar(g, y0 + (n - 1) * lh, (first + 1) + "-" + last + "/" + body.length, "2/8 5 0 # *");
        }
        if (frame >= 0 && frames != null && frames.length > 0) {
            g.drawImage(frames[frame % frames.length], w, 0, Graphics.TOP | Graphics.RIGHT);
        }
    }

    /** One inverted row of the overlay; the hint on the right only when both fit. */
    private void bar(Graphics g, int y, String left, String right) {
        g.setColor(0x000000);
        g.fillRect(0, y, width, font.getHeight());
        g.setColor(0xffffff);
        g.drawString(left, 0, y, Graphics.TOP | Graphics.LEFT);
        if (right != null && font.stringWidth(left + " " + right) <= width) {
            g.drawString(right, width, y, Graphics.TOP | Graphics.RIGHT);
        }
        g.setColor(0x000000);
    }

    protected void keyPressed(int key) {
        switch (key) {
        case KEY_NUM2:
            scroll(1);
            return;
        case KEY_NUM8:
            scroll(-1);
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
        case KEY_POUND:
            info = !info;
            Trace.show("Info " + (info ? "on" : "off"));
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
        int action = getGameAction(key);
        if (action == UP) {
            scroll(1);
        } else if (action == DOWN) {
            scroll(-1);
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
