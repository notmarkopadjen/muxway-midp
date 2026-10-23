package muxway;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * One pane, drawn like a terminal: a title bar, then the pane's last lines in
 * a monospace font, opening at the bottom. Up and Down scroll. The size is
 * taken from the first full paint, because Canvas.getWidth binds to a class
 * MIDP 1.0 phones do not have it on.
 */
final class PaneView extends Canvas {
    // The green call key on Nokia phones; it sends Enter.
    private static final int SEND_KEY = -10;

    private final Font font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font bold = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Image[] frames;

    String title = "";
    String address = "";
    boolean writable;
    private String[] lines = new String[0];
    private int offset;
    private int width;
    private int height;
    private int frame = -1;
    private final Runnable enter;

    PaneView(Image[] frames, Runnable enter) {
        this.frames = frames;
        this.enter = enter;
    }

    /** Columns that fit, or 0 before the first paint. */
    int columns() {
        int w = font.charWidth('M');
        return width > 0 && w > 0 ? Math.max(8, (width - 2) / w) : 0;
    }

    void setText(String text) {
        String[] all = Text.split(text, '\n');
        int n = all.length;
        while (n > 0 && all[n - 1].length() == 0) {
            n--;
        }
        lines = new String[n];
        System.arraycopy(all, 0, lines, 0, n);
        offset = 0;
        Trace.show(title + ": " + text);
        repaint();
    }

    /** The waiting mark in the title bar; -1 hides it. */
    void busy(int step) {
        frame = step;
        repaint();
    }

    protected void paint(Graphics g) {
        if (g.getClipX() == 0 && g.getClipY() == 0) {
            width = Math.max(width, g.getClipWidth());
            height = Math.max(height, g.getClipHeight());
        }
        int w = width, h = height;
        int lh = font.getHeight();
        int bar = bold.getHeight() + 2;
        g.setColor(0xffffff);
        g.fillRect(0, 0, w, h);
        g.setColor(0x000000);
        g.fillRect(0, 0, w, bar);
        g.setColor(0xffffff);
        g.setFont(bold);
        String head = (writable ? "> " : "  ") + title;
        g.drawString(head, 1, 1, Graphics.TOP | Graphics.LEFT);
        if (frame >= 0 && frames != null && frames.length > 0) {
            Image f = frames[frame % frames.length];
            g.drawImage(f, w - 1, bar / 2, Graphics.RIGHT | Graphics.VCENTER);
        }
        g.setColor(0x000000);
        g.setFont(font);
        int rows = Math.max(1, (h - bar - 1) / lh);
        int last = lines.length - offset;
        int first = Math.max(0, last - rows);
        int y = bar + 1;
        for (int i = first; i < last; i++) {
            g.drawString(lines[i], 1, y, Graphics.TOP | Graphics.LEFT);
            y += lh;
        }
        if (offset > 0) {
            g.drawString("v", w - 1, h - 1, Graphics.BOTTOM | Graphics.RIGHT);
        }
        if (first > 0) {
            g.drawString("^", w - 1, bar + 1, Graphics.TOP | Graphics.RIGHT);
        }
    }

    protected void keyPressed(int key) {
        if (key == SEND_KEY) {
            if (writable) {
                enter.run();
            }
            return;
        }
        scroll(getGameAction(key));
    }

    protected void keyRepeated(int key) {
        scroll(getGameAction(key));
    }

    private void scroll(int action) {
        int rows = Math.max(1, (height - bold.getHeight() - 3) / font.getHeight());
        if (action == UP) {
            offset = Math.min(offset + 1, Math.max(0, lines.length - rows));
        } else if (action == DOWN) {
            offset = Math.max(0, offset - 1);
        } else {
            return;
        }
        repaint();
    }
}
