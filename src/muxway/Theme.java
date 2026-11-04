package muxway;

/**
 * The pane canvas's colours, the guideline's tokens: amber, green, paper.
 * MIDP 2.0 build only: the 1.0 build's screen is monochrome, Ui.THEME is
 * false there and ProGuard drops this class.
 */
final class Theme {
    static final String[] NAMES = {"Amber", "Green", "Paper"};
    private static final int[] BACKGROUND = {0x0a0603, 0x050806, 0xf1ead8};
    private static final int[] INK = {0xffb000, 0x39ff14, 0x1f2a1f};

    final int bg;
    final int ink;

    Theme(int which) {
        bg = BACKGROUND[which];
        ink = INK[which];
    }
}
