package muxway;

/** Reshapes the rows of one program's screen for a small display. */
interface Lens {
    /**
     * Returns {body, pinned}: rows that scroll and rows kept at the bottom.
     * Null when the rows are not this program's screen.
     */
    String[][] shape(String[] rows, boolean reveal);
}
