package muxway;

/** What differs in the MIDP 1.0 build, for phones like a 96x65 Nokia. */
final class Ui {
    static final String PROFILE = "MIDP-1.0";
    // Pane lines fetched per read: enough to scroll back a little, little
    // enough for the phone's heap and a slow link.
    static final int LINES = 40;

    // Latin-1 from the hub: these phones have no glyphs for lines and bullets.
    static final int TEXT = 1;

    // The inbox and its answers: a screen, a job and a parser the 30 KiB JAR has no room for.
    static final boolean INBOX = false;

    // Talk groups and mail counts: a class and a few joins the cap has no room for either.
    static final boolean TALK = false;

    // A parked pane shows its mark; the command and the wake need bytes the cap has not got.
    static final boolean LATER = false;

    // The folder and the status after the name: columns the 96x65 row has no room for.
    static final boolean NAMES = false;

    // Messages and mail: two screens and their parsers, far over the cap.
    static final boolean MAIL = false;

    // Host lines and the writes that need the hosts' flags: the same.
    static final boolean HOSTS = false;

    // The screen is monochrome: black on white stays.
    static final boolean THEME = false;

    // The Commands list: fifty strings the cap has no room for.
    static final boolean COMMANDS = false;

    // The warm-up while a pane view has nothing of its own: the 96x65 box stays blank, the bytes are not there.
    static final boolean WARMUP = false;

    // The desk's focused pane from the list's menu: a request and a reader the cap has no room for.
    static final boolean DESK = false;

    private Ui() {
    }
}
