package muxway;

/** Reshapes the rows of one program's screen for a small display. */
interface Lens {
    /** The program's name, for the pane's View choice. */
    String name();

    /** True when the program named by the hub (its command, e.g. "claude") is this lens's. */
    boolean runs(String command);

    /**
     * Returns {body, pinned}: rows that scroll and rows kept at the bottom.
     * Null when the rows are not this program's screen. Forced, the lens
     * does its best with any rows and answers null only when there is
     * nothing to pin.
     */
    String[][] shape(String[] rows, boolean reveal, boolean forced);
}
