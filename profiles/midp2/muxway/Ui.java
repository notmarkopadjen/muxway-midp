package muxway;

/** What differs in the MIDP 2.0 build. */
final class Ui {
    static final String PROFILE = "MIDP-2.0";
    // Pane lines fetched per read: enough to scroll back a little, little
    // enough for the phone's heap and a slow link.
    static final int LINES = 120;

    // Unicode from the hub; these phones can show it.
    static final int TEXT = 0;

    private Ui() {
    }
}
