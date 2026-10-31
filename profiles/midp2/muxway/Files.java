package muxway;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.VideoControl;

/**
 * Where an image for a pane comes from on a MIDP 2.0 phone: the camera, or
 * a file in the phone's memory. The only class that touches the media and
 * file APIs.
 */
final class Files implements CommandListener {
    static final boolean ANY = true;
    // Over this a file takes minutes on GPRS and the watchdog would give up first.
    static final int MAX = 1024 * 1024;

    private final Muxway app;
    private final Display display;
    private final boolean camera;
    private final List source = new List("Image", List.IMPLICIT);
    private final Command back = new Command("Back", Command.BACK, 2);
    private final Command shoot = new Command("Shoot", Command.OK, 1);
    private final Command send = new Command("Send", Command.OK, 1);
    private final Command retake = new Command("Retake", Command.SCREEN, 2);

    private List gallery;
    // The folder shown, file:///C:/Images/; empty at the list of roots.
    private String folder = "";
    private final Vector entries = new Vector();

    private Player player;
    private VideoControl video;
    private Viewfinder finder;
    private Preview preview;
    private boolean shooting;

    Files(Muxway app, Display display) {
        this.app = app;
        this.display = display;
        camera = "true".equals(System.getProperty("supports.video.capture"));
        if (camera) {
            source.append("Camera", null);
        }
        source.append("Gallery", null);
        source.addCommand(back);
        source.setCommandListener(this);
    }

    /** The Camera / Gallery choice. */
    void open() {
        Trace.show("Image: " + (camera ? "Camera, Gallery" : "Gallery"));
        display.setCurrent(source);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == back) {
            stopCamera();
            app.showPane();
        } else if (d == source && c == List.SELECT_COMMAND) {
            if (camera && source.getSelectedIndex() == 0) {
                showCamera();
            } else {
                showGallery(photos());
            }
        } else if (d == gallery && c == List.SELECT_COMMAND) {
            pick(gallery.getSelectedIndex());
        } else if (d == finder && c == shoot) {
            snap();
        } else if (d == preview && c == send) {
            app.picked(preview.picture);
        } else if (d == preview && c == retake) {
            showCamera();
        }
    }

    // --- gallery ---

    /** The phone's photo folder, or the roots when it names none. */
    private static String photos() {
        String p = System.getProperty("fileconn.dir.photos");
        return p == null ? "" : p;
    }

    private void showGallery(String where) {
        Vector dirs = new Vector(), files = new Vector();
        try {
            if (where.length() == 0) {
                for (Enumeration e = FileSystemRegistry.listRoots(); e.hasMoreElements();) {
                    dirs.addElement(e.nextElement());
                }
            } else {
                FileConnection fc = (FileConnection) Connector.open(where, Connector.READ);
                try {
                    for (Enumeration e = fc.list(); e.hasMoreElements();) {
                        String n = (String) e.nextElement();
                        if (n.endsWith("/")) {
                            dirs.addElement(n);
                        } else if (Text.imageType(n) != null) {
                            files.addElement(n);
                        }
                    }
                } finally {
                    fc.close();
                }
            }
        } catch (Exception e) {
            // Refused by the person, or a folder the phone will not open.
            Trace.show("Gallery error: " + e);
            app.alert("Cannot read " + (where.length() == 0 ? "the phone's memory" : name(where)) + ".",
                gallery == null ? (Displayable) source : gallery);
            return;
        }
        folder = where;
        entries.removeAllElements();
        gallery = new List(where.length() == 0 ? "Gallery" : name(where), List.IMPLICIT);
        if (where.length() > 0) {
            add("..");
        }
        for (int i = 0; i < dirs.size(); i++) {
            add((String) dirs.elementAt(i));
        }
        for (int i = 0; i < files.size(); i++) {
            add((String) files.elementAt(i));
        }
        if (dirs.size() + files.size() == 0) {
            gallery.append("No images here.", null);
            entries.addElement("");
        }
        gallery.addCommand(back);
        gallery.setCommandListener(this);
        StringBuffer shown = new StringBuffer();
        for (int i = 0; i < entries.size(); i++) {
            shown.append(entries.elementAt(i)).append('\n');
        }
        Trace.show("Gallery " + (where.length() == 0 ? "/" : where) + ": " + shown.toString());
        display.setCurrent(gallery);
    }

    private void add(String entry) {
        gallery.append(entry, null);
        entries.addElement(entry);
    }

    private void pick(int i) {
        if (i < 0 || i >= entries.size()) {
            return;
        }
        String n = (String) entries.elementAt(i);
        if (n.length() == 0) {
            return;
        }
        if (n.equals("..")) {
            showGallery(parent(folder));
            return;
        }
        if (n.endsWith("/")) {
            showGallery(folder.length() == 0 ? "file:///" + n : folder + n);
            return;
        }
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(folder + n, Connector.READ);
            long size = fc.fileSize();
            if (size < 0 || size > MAX) {
                fc.close();
                Trace.show("Too big: " + n + " " + size);
                app.alert("Too big for the phone's link (1 MiB at most).", gallery);
                return;
            }
            Trace.show("Picked " + n + " " + size);
            app.picked(new FilePicture(fc, Text.imageType(n), (int) size));
        } catch (Exception e) {
            Trace.show("Gallery error: " + e);
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException e2) {
                }
            }
            app.alert("Cannot read " + n + ".", gallery);
        }
    }

    /** The folder above, or the roots. */
    private static String parent(String url) {
        String up = url.substring(0, url.lastIndexOf('/', url.length() - 2) + 1);
        return up.equals("file:///") ? "" : up;
    }

    /** The last part of a folder's URL, for its title. */
    private static String name(String url) {
        return url.substring(url.lastIndexOf('/', url.length() - 2) + 1, url.length() - 1);
    }

    // --- camera ---

    private void showCamera() {
        stopCamera();
        try {
            try {
                player = Manager.createPlayer("capture://image");
            } catch (Exception e) {
                player = Manager.createPlayer("capture://video");
            }
            player.realize();
            video = (VideoControl) player.getControl("VideoControl");
            if (video == null) {
                throw new IOException("no video control");
            }
            finder = new Viewfinder();
            video.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, finder);
            try {
                video.setDisplayFullScreen(true);
            } catch (Exception e) {
                // A phone without full screen video shows it as it likes; the shot is what matters.
                try {
                    video.setDisplayLocation(0, 0);
                    video.setDisplaySize(finder.getWidth(), finder.getHeight());
                } catch (Exception e2) {
                }
            }
            video.setVisible(true);
            player.start();
            Trace.show("Camera");
            display.setCurrent(finder);
        } catch (Throwable e) {
            Trace.show("Camera error: " + e);
            stopCamera();
            app.alert("No camera on this phone.", source);
        }
    }

    /** Takes the shot off the UI thread: a phone can take a second over it. */
    private synchronized void snap() {
        if (video == null || shooting) {
            return;
        }
        shooting = true;
        new Thread() {
            public void run() {
                byte[] shot = null;
                try {
                    try {
                        shot = video.getSnapshot("encoding=jpeg&width=640&height=480");
                    } catch (Exception e) {
                        try {
                            shot = video.getSnapshot("encoding=jpeg");
                        } catch (Exception e2) {
                            shot = video.getSnapshot(null);
                        }
                    }
                } catch (Throwable e) {
                    Trace.show("Snapshot error: " + e);
                }
                stopCamera();
                shooting = false;
                String type = shot == null ? null : Text.imageType(shot);
                if (type == null) {
                    app.alert("The camera gave no image the host takes.", source);
                    return;
                }
                Trace.show("Shot " + shot.length + " bytes " + type);
                preview = new Preview(new BytesPicture(shot, type));
                display.setCurrent(preview);
            }
        }.start();
    }

    private void stopCamera() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception e) {
            }
            player.close();
            player = null;
            video = null;
        }
    }

    /** The live picture, drawn by the phone over this canvas; 5 or Fire takes the shot. */
    private final class Viewfinder extends Canvas {
        Viewfinder() {
            setFullScreenMode(true);
            addCommand(shoot);
            addCommand(back);
            setCommandListener(Files.this);
        }

        protected void paint(Graphics g) {
            g.setColor(0x000000);
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        protected void keyPressed(int key) {
            if (key == KEY_NUM5 || getGameAction(key) == FIRE) {
                snap();
            }
        }
    }

    /** The shot, as much of it as fits, with Send and Retake; 5 sends. */
    private final class Preview extends Canvas {
        final Picture picture;
        private Image image;

        Preview(Picture picture) {
            this.picture = picture;
            try {
                BytesPicture b = (BytesPicture) picture;
                image = Image.createImage(b.data, 0, b.data.length);
            } catch (Throwable e) {
                // Too big for the heap, or a format the phone will not draw: the words will do.
                image = null;
            }
            addCommand(send);
            addCommand(retake);
            addCommand(back);
            setCommandListener(Files.this);
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            g.setColor(0x000000);
            g.fillRect(0, 0, w, h);
            if (image != null) {
                g.drawImage(image, w / 2, h / 2, Graphics.HCENTER | Graphics.VCENTER);
            }
            g.setColor(0xffffff);
            g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL));
            g.drawString((picture.length() + 512) / 1024 + " KiB  5 Send", 0, h, Graphics.BOTTOM | Graphics.LEFT);
        }

        protected void keyPressed(int key) {
            if (key == KEY_NUM5 || getGameAction(key) == FIRE) {
                app.picked(picture);
            }
        }
    }

    private static final class BytesPicture implements Picture {
        final byte[] data;
        private final String type;

        BytesPicture(byte[] data, String type) {
            this.data = data;
            this.type = type;
        }

        public String type() {
            return type;
        }

        public int length() {
            return data.length;
        }

        public InputStream open() {
            return new ByteArrayInputStream(data);
        }

        public void close() {
        }
    }

    private static final class FilePicture implements Picture {
        private final FileConnection file;
        private final String type;
        private final int length;

        FilePicture(FileConnection file, String type, int length) {
            this.file = file;
            this.type = type;
            this.length = length;
        }

        public String type() {
            return type;
        }

        public int length() {
            return length;
        }

        public InputStream open() throws IOException {
            return file.openInputStream();
        }

        public void close() {
            try {
                file.close();
            } catch (IOException e) {
            }
        }
    }
}
