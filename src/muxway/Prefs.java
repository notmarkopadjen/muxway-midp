package muxway;

import javax.microedition.rms.RecordStore;

/** Settings, kept in the phone's record store. */
final class Prefs {
    private static final String STORE = "muxway";

    String server = "";
    String token = "";
    int width;
    int refresh;

    Prefs(int defaultWidth) {
        width = defaultWidth;
    }

    boolean ready() {
        return server.length() > 0 && token.length() > 0;
    }

    void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE, true);
            try {
                if (rs.getNumRecords() > 0) {
                    String[] f = Text.split(new String(rs.getRecord(1), "UTF-8"), '\n');
                    if (f.length >= 4 && f[0].equals("1")) {
                        server = f[1];
                        token = f[2];
                        width = Text.toInt(f[3], width);
                        refresh = f.length > 4 ? Text.toInt(f[4], 0) : 0;
                    }
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
                byte[] raw = ("1\n" + server + "\n" + token + "\n" + width + "\n" + refresh).getBytes("UTF-8");
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
