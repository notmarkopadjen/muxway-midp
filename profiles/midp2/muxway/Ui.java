package muxway;

/** What differs in the MIDP 2.0 build. */
final class Ui {
    static final String PROFILE = "MIDP-2.0";
    // Pane lines fetched per read: enough to scroll back a little, little
    // enough for the phone's heap and a slow link.
    static final int LINES = 120;

    // Unicode from the hub; these phones can show it.
    static final int TEXT = 0;

    // The inbox of what waits across hosts, and answers to a question's choices.
    static final boolean INBOX = true;

    // Talk groups and mail counts from the listing's two trailing columns.
    static final boolean TALK = true;

    // Later and Wake on a pane's menu, and the wake of a parked pane as it opens.
    static final boolean LATER = true;

    // The folder and the status after the name, from the listing's last two columns.
    static final boolean NAMES = true;

    // Messages from the feed, a pane's mail and mailing it.
    static final boolean MAIL = true;

    // A line per host with its flags, Auto on the list, + session, + pane and Close.
    static final boolean HOSTS = true;

    // The pane canvas in amber, green or paper.
    static final boolean THEME = true;

    // The Commands list: a program's starters, sent with Enter.
    static final boolean COMMANDS = true;

    // The warm-up while a pane view has nothing of its own and a request runs.
    static final boolean WARMUP = true;

    // Desk on the list's menu: the desk's focused pane opened from the hub's desk.
    static final boolean DESK = true;

    // The pane known by its id from the listing's last column, checked against every read's Muxway-Pane,
    // and followed to its new address when the host renumbers.
    static final boolean IDS = true;

    private Ui() {
    }
}
