package muxway;

/** Plain Java SE checks of the text helpers; prints PASS or FAIL per case. */
public final class UnitTest {
    private static int failed;

    private static void eq(String name, String got, String want) {
        if (got.equals(want)) {
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " got " + esc(got) + " want " + esc(want));
        }
    }

    private static String esc(String s) {
        StringBuffer out = new StringBuffer("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) {
                out.append("\\u").append(Integer.toHexString(0x10000 | c).substring(1));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String join(String[] rows) {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(rows[i]);
        }
        return out.toString();
    }

    private static final String RULE = "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

    // A Claude Code screen after one exchange, the way the hub's text mode returns it.
    private static final String[] CLAUDE = {
        " Claude Code v1.0", " /home/alice/demo", "\u276f say pong", "\u25cf pong", "", "",
        "                                              \u25cf high \u00b7 /effort",
        RULE, "\u276f", RULE, "  \u23f5\u23f5 auto mode on (shift+tab to cycle) \u00b7 \u2190 5 agents",
    };

    private static void lensTests() {
        ClaudeCode lens = new ClaudeCode();
        String[][] s = lens.shape(CLAUDE, false);
        eq("claude_body_ends_at_transcript", join(s[0]), " Claude Code v1.0| /home/alice/demo|\u276f say pong|\u25cf pong");
        eq("claude_box_pinned_footer_hidden", join(s[1]), "\u276f");
        s = lens.shape(CLAUDE, true);
        eq("claude_reveal_status_box_footer", join(s[1]),
            "\u25cf high \u00b7 /effort|\u276f|"
            + "  \u23f5\u23f5 auto mode on (shift+tab to cycle) \u00b7 \u2190 5 agents");
        eq("claude_body_same_when_revealed", String.valueOf(s[0].length), "4");
        String[] shell = {"$ ls", "a b c", "$"};
        eq("claude_ignores_a_shell", String.valueOf(lens.shape(shell, false)), "null");
        String[] one = {"text", RULE, "\u276f typed"};
        eq("claude_needs_two_rules", String.valueOf(lens.shape(one, false)), "null");
        String[] tall = {"x", RULE, "\u276f line one", "  line two", "  line three", RULE, "footer"};
        s = lens.shape(tall, false);
        eq("claude_box_keeps_every_row", join(s[1]), "\u276f line one|  line two|  line three");
        eq("claude_indented_transcript_stays", join(lens.shape(new String[] {"        code", "z", RULE, "\u276f", RULE}, false)[0]), "        code|z");
        eq("rule_needs_three", String.valueOf(ClaudeCode.rule("\u2500\u2500")), "false");
        eq("rule_with_spaces", String.valueOf(ClaudeCode.rule("  \u2550\u2550\u2550  ")), "true");
        eq("rule_not_dashes", String.valueOf(ClaudeCode.rule("----------")), "false");
    }

    private static void wrapTests() {
        eq("wrap_cuts_long_rows", join(Text.wrap(new String[] {"abcdefgh", "ij"}, 3)), "abc|def|gh|ij");
        eq("wrap_keeps_empty_rows", join(Text.wrap(new String[] {"", "abc", ""}, 3)), "|abc|");
        eq("wrap_zero_leaves_rows", join(Text.wrap(new String[] {"abcdef"}, 0)), "abcdef");
        eq("wrap_counts_chars_not_bytes", join(Text.wrap(new String[] {"\u010d\u0107\u017e\u00e4"}, 2)), "\u010d\u0107|\u017e\u00e4");
    }

    public static void main(String[] args) {
        lensTests();
        wrapTests();
        Prefs p = new Prefs();
        p.read("1\nhttp://relay.example.com\nabc\n0\n5");
        eq("prefs_old_record_reads_auto", p.record(), "1\nhttp://relay.example.com\nabc\n0\n5\n0");
        p.read("1\nhttp://relay.example.com\nabc\n20\n5\n1");
        eq("prefs_view_kept", String.valueOf(p.view), "1");
        eq("printable_drops_escape", Text.printable("a\u001b[2Jb"), "a[2Jb");
        eq("printable_drops_newline_and_tab", Text.printable("line\nbreak\there"), "linebreakhere");
        eq("printable_drops_del_and_c1", Text.printable("x\u007fy\u0085z\u009b"), "xyz");
        eq("printable_keeps_unicode", Text.printable("čćž 日本"), "čćž 日本");
        eq("screen_keeps_newlines_and_tabs", Text.screen("a\tb\nc\u0007\u001bd\r"), "a\tb\ncd");
        eq("split_keeps_empty_fields", String.valueOf(Text.split("a\t\tb", '\t').length), "3");
        eq("json_text_escapes_quotes", Api.text("say \"hi\" \\ bye"), "{\"text\":\"say \\\"hi\\\" \\\\ bye\"}");
        eq("json_key", Api.key("C-c"), "{\"key\":\"C-c\"}");
        eq("problem_401", Api.problem(401), "Token refused.");
        eq("problem_503", Api.problem(503), "Host offline.");
        System.exit(failed == 0 ? 0 : 1);
    }
}
