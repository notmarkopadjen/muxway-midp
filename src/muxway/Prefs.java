package muxway;

import javax.microedition.rms.RecordStore;

/** Settings, kept in the phone's record store. */
final class Prefs {
    private static final String STORE = "muxway";
    // Panes remembered with a view of their own; the oldest goes first.
    static final int PANES = 32;

    String server = "";
    String token = "";
    int width;
    int refresh;
    // How the hub spells what the phone cannot show: 0 Unicode, 1 Latin-1, 2 ASCII.
    int text = Ui.TEXT;

    private String[] paneAddress = new String[0];
    private int[] paneView = new int[0];

    boolean ready() {
        return server.length() > 0 && token.length() > 0;
    }

    /** The view of a pane: 0 auto, 1 plain, 2 and up a lens by its number. */
    int view(String address) {
        for (int i = 0; i < paneAddress.length; i++) {
            if (paneAddress[i].equals(address)) {
                return paneView[i];
            }
        }
        return 0;
    }

    void setView(String address, int view) {
        for (int i = 0; i < paneAddress.length; i++) {
            if (paneAddress[i].equals(address)) {
                paneView[i] = view;
                return;
            }
        }
        int n = paneAddress.length, drop = n == PANES ? 1 : 0;
        String[] a = new String[n - drop + 1];
        int[] v = new int[a.length];
        System.arraycopy(paneAddress, drop, a, 0, n - drop);
        System.arraycopy(paneView, drop, v, 0, n - drop);
        a[a.length - 1] = address;
        v[v.length - 1] = view;
        paneAddress = a;
        paneView = v;
    }

    /** Fills the fields from one record; a short or old one leaves the newer fields at their defaults. */
    void read(String record) {
        String[] f = Text.split(record, '\n');
        if (f.length < 5 || !(f[0].equals("1") || f[0].equals("2"))) {
            return;
        }
        server = f[1];
        token = f[2];
        width = Text.toInt(f[3], width);
        refresh = Text.toInt(f[4], 0);
        if (!f[0].equals("2")) {
            return;
        }
        text = f.length > 5 ? Math.max(0, Math.min(2, Text.toInt(f[5], Ui.TEXT))) : Ui.TEXT;
        paneAddress = new String[0];
        paneView = new int[0];
        for (int i = 6; i < f.length; i++) {
            String[] p = Text.split(f[i], '\t');
            if (p.length == 2 && p[0].length() > 0) {
                setView(p[0], Math.max(0, Text.toInt(p[1], 0)));
            }
        }
    }

    String record() {
        StringBuffer s = new StringBuffer("2\n");
        s.append(server).append('\n').append(token).append('\n').append(width).append('\n');
        s.append(refresh).append('\n').append(text);
        for (int i = 0; i < paneAddress.length; i++) {
            s.append('\n').append(paneAddress[i]).append('\t').append(paneView[i]);
        }
        return s.toString();
    }

    void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE, true);
            try {
                if (rs.getNumRecords() > 0) {
                    read(new String(rs.getRecord(1), "UTF-8"));
                }
            } finally {
                rs.closeRecordStore();
            }
        } catch (Exception e) {
        }
    }

    void save() {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE, true);
            try {
                byte[] raw = record().getBytes("UTF-8");
                if (rs.getNumRecords() > 0) {
                    rs.setRecord(1, raw, 0, raw.length);
                } else {
                    rs.addRecord(raw, 0, raw.length);
                }
            } finally {
                rs.closeRecordStore();
            }
        } catch (Exception e) {
        }
    }
}
