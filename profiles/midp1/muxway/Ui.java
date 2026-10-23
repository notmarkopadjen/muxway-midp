package muxway;

/** What differs in the MIDP 1.0 build, for phones like a 96x65 Nokia. */
final class Ui {
    static final String PROFILE = "MIDP-1.0";
    // Pane lines fetched per read: enough to scroll back a little, little
    // enough for the phone's heap and a slow link.
    static final int LINES = 40;

    private Ui() {
    }
}
