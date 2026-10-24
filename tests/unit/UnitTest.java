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

    private static final String FOOTER = "  \u23f5\u23f5 auto mode on (shift+tab to cycle) \u00b7 \u2190 5 agents";
    private static final String NAMED = repeat('\u2500', 40) + " demo \u2500";
    private static final String SUMMARY = "\u273b Crunched for 1s \u00b7 done 8:41 PM";
    private static final String NOTICE = "        \u2193 Update installed \u00b7 Restart to apply";

    // A Claude Code screen after one exchange, the way the hub's text mode returns it, with text typed in the box.
    private static final String[] CLAUDE = {
        " Claude Code v1.0", " /home/alice/demo", "\u276f say pong", "\u25cf pong", "", SUMMARY, "", NOTICE,
        NAMED, "\u276f hi", RULE, FOOTER,
    };

    private static String[] emptyBox(String[] rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i].equals("\u276f hi") ? "\u276f" : rows[i].equals("> hi") ? ">" : rows[i];
        }
        return out;
    }

    private static void lensTests() {
        ClaudeCode lens = new ClaudeCode();
        String[][] s = lens.shape(CLAUDE, false, false);
        eq("claude_body_ends_at_transcript", join(s[0]), " Claude Code v1.0| /home/alice/demo|\u276f say pong|\u25cf pong");
        eq("claude_typed_box_pinned_chrome_hidden", join(s[1]), "\u276f hi");
        s = lens.shape(emptyBox(CLAUDE), false, false);
        eq("claude_empty_box_hidden", join(s[0]) + "/" + s[1].length, " Claude Code v1.0| /home/alice/demo|\u276f say pong|\u25cf pong/0");
        s = lens.shape(CLAUDE, true, false);
        eq("claude_reveal_in_place_without_rules", join(s[0]),
            " Claude Code v1.0| /home/alice/demo|\u276f say pong|\u25cf pong|" + SUMMARY + "|" + NOTICE.trim() + "|\u276f hi|" + FOOTER);
        eq("claude_reveal_pins_nothing", String.valueOf(s[1].length), "0");
        eq("summary_rows", String.valueOf(ClaudeCode.summary(SUMMARY)) + ClaudeCode.summary("* Puttering... (37s \u00b7 v 1.6k tokens)")
            + ClaudeCode.summary("\u25cf pong") + ClaudeCode.summary("\u25cf done at 8:41 PM") + ClaudeCode.summary("* 1.6k tokens"), "truetruefalsefalsefalse");
        eq("rule_with_name_only_loose", String.valueOf(ClaudeCode.rule(NAMED, true)) + ClaudeCode.rule(NAMED, false)
            + ClaudeCode.rule("\u2500\u2500\u2500 demo", true) + ClaudeCode.rule(RULE + " my session name", true), "truefalsefalsefalse");
        String[] shell = {"$ ls", "a b c", "$"};
        eq("claude_ignores_a_shell", String.valueOf(lens.shape(shell, false, false)), "null");
        String[] one = {"text", RULE, "\u276f typed"};
        eq("claude_needs_two_rules", String.valueOf(lens.shape(one, false, false)), "null");
        String[] tall = {"x", RULE, "\u276f line one", "  line two", "  line three", RULE, "footer"};
        s = lens.shape(tall, false, false);
        eq("claude_box_keeps_every_row", join(s[1]), "\u276f line one|  line two|  line three");
        eq("claude_indented_transcript_stays", join(lens.shape(new String[] {"        code", "z", RULE, "\u276f", RULE}, false, false)[0]), "        code|z");
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

    private static String repeat(char c, int n) {
        StringBuffer b = new StringBuffer(n);
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }

    private static final String DASHES = "----------------------------------------";

    private static final String FOOTER_ASCII = "  >> auto mode on (shift+tab to cycle) . < 5 agents";

    // The same screen once the hub spelt it in ASCII for a Latin-1 phone.
    private static final String[] CLAUDE_ASCII = {
        " Claude Code v1.0", " /home/alice/demo", "> say pong", "* pong", "", "* Crunched for 1s . done 8:41 PM", "",
        "        v Update installed . Restart to apply", DASHES + " demo -", "> hi", DASHES, FOOTER_ASCII,
    };

    private static void lensAsciiTests() {
        ClaudeCode lens = new ClaudeCode();
        eq("rule_ascii_twenty_dashes", String.valueOf(ClaudeCode.rule(DASHES)) + ClaudeCode.rule("====================") + ClaudeCode.rule("---"), "truetruefalse");
        eq("rule_ascii_not_mixed", String.valueOf(ClaudeCode.rule(DASHES + "x")) + ClaudeCode.rule("--- a/file"), "falsefalse");
        String[][] s = lens.shape(CLAUDE_ASCII, false, false);
        eq("claude_ascii_body", join(s[0]), " Claude Code v1.0| /home/alice/demo|> say pong|* pong");
        eq("claude_ascii_box", join(s[1]), "> hi");
        eq("claude_ascii_empty_box_hidden", String.valueOf(lens.shape(emptyBox(CLAUDE_ASCII), false, false)[1].length), "0");
        s = lens.shape(CLAUDE_ASCII, true, false);
        eq("claude_ascii_reveal", join(s[0]), " Claude Code v1.0| /home/alice/demo|> say pong|* pong|* Crunched for 1s . done 8:41 PM|v Update installed . Restart to apply|> hi|" + FOOTER_ASCII);
        String[] noRules = {"a", "b", "", "\u276f fix it", "  footer"};
        eq("claude_auto_needs_rules", String.valueOf(lens.shape(noRules, false, false)), "null");
        s = lens.shape(noRules, false, true);
        eq("claude_forced_pins_prompt", join(s[0]) + "/" + join(s[1]), "a|b/\u276f fix it");
        s = lens.shape(noRules, true, true);
        eq("claude_forced_reveal_shows_all_in_place", join(s[0]) + "/" + s[1].length, "a|b|\u276f fix it|  footer/0");
        String[] noRulesChrome = {"a", "\u273b Crunched for 2s", "", "        \u2193 Update installed", "\u276f", "  footer"};
        s = lens.shape(noRulesChrome, false, true);
        eq("claude_forced_hides_summary_and_notice", join(s[0]) + "/" + s[1].length, "a/0");
        s = lens.shape(noRulesChrome, true, true);
        eq("claude_forced_reveal_chrome_in_place", join(s[0]), "a|\u273b Crunched for 2s|\u2193 Update installed|\u276f|  footer");
        String[] ascii = {"x", "> ", "y"};
        s = lens.shape(ascii, false, true);
        eq("claude_forced_empty_ascii_prompt_hidden", join(s[0]) + "/" + s[1].length, "x/0");
        eq("claude_runs_claude_only", String.valueOf(lens.runs("claude")) + lens.runs("bash") + lens.runs(""), "truefalsefalse");
        String[] shell = {"$ ls", "a b c", "$ "};
        eq("claude_forced_without_prompt_is_null", String.valueOf(lens.shape(shell, false, true)), "null");
        String[] far = new String[12];
        for (int i = 0; i < far.length; i++) {
            far[i] = i == 2 ? "> old prompt" : "row " + i;
        }
        eq("claude_forced_looks_at_last_eight_only", String.valueOf(lens.shape(far, false, true)), "null");
        s = lens.shape(CLAUDE, false, true);
        eq("claude_forced_with_rules_as_auto", join(s[1]), "\u276f hi");
    }

    private static void prefsTests() {
        Prefs p = new Prefs();
        p.read("1\nhttp://relay.example.com\nabc\n0\n5\n1");
        eq("prefs_old_record_reads_with_defaults", p.record(), "3\nhttp://relay.example.com\nabc\n0\n5\n" + Ui.TEXT + "\n");
        eq("prefs_unknown_pane_is_auto", String.valueOf(p.view("h/work/0.0")), "0");
        p.setView("h/work/0.0", 1);
        p.setView("h/work/1.0", 2);
        p.setView("h/work/0.0", 2);
        p.text = 2;
        eq("prefs_panes_in_record", p.record(), "3\nhttp://relay.example.com\nabc\n0\n5\n2\n\nh/work/0.0\t2\nh/work/1.0\t2");
        Prefs q = new Prefs();
        q.read(p.record() + "\nbroken line\n\tno address\nh/work/2.0\tx");
        eq("prefs_record_round_trip", String.valueOf(q.view("h/work/1.0")) + q.view("h/work/0.0") + q.text, "222");
        eq("prefs_bad_lines_dropped", String.valueOf(q.view("h/work/2.0")) + q.view("broken line"), "00");
        for (int i = 0; i < 40; i++) {
            q.setView("h/s/" + i + ".0", 1);
        }
        eq("prefs_cap_drops_oldest", String.valueOf(q.view("h/work/0.0")) + q.view("h/s/7.0") + q.view("h/s/8.0") + q.view("h/s/39.0"), "0011");
        eq("prefs_cap_size", String.valueOf(Text.split(q.record(), '\n').length), String.valueOf(7 + Prefs.PANES));
        q.read("4\nx\ny\n0\n0");
        eq("prefs_unknown_version_ignored", q.server, "http://relay.example.com");
        q.read("2\nhttp://relay.example.com\nabc\n0\n5\n1\nh/work/0.0\t1");
        eq("prefs_v2_record_has_no_stamp", q.installed + q.view("h/work/0.0"), "1");
        q.installed = "12345";
        q.setView("h/work/3.0", 2);
        Prefs r = new Prefs();
        r.read(q.record());
        eq("prefs_v3_record_round_trip", r.installed + r.view("h/work/0.0") + r.view("h/work/3.0"), "1234512");
    }

    private static String label(String row, boolean hosts) {
        return Text.label(Text.split(row, '\t'), hosts);
    }

    private static void labelTests() {
        eq("label_title_and_command", label("alice/work/0.0\twrite\tclaude\tmuxway android\t", false), "muxway android (claude)");
        eq("label_drops_claudes_mark", label("alice/work/0.0\twrite\tclaude\t\u2733 build\t", false) + "|" + label("alice/work/0.0\twrite\tclaude\t* build\t", false), "build (claude)|build (claude)");
        eq("label_mark_alone_is_address", label("alice/work/0.0\twrite\tclaude\t\u2733 \t", false), "work/0.0 (claude)");
        eq("label_empty_title_is_address", label("alice/work/0.1\twrite\tbash\t\t", false), "work/0.1 (bash)");
        eq("label_title_like_command_is_address", label("alice/work/0.1\tread\tbash\tbash\t", false), "work/0.1 (bash)");
        eq("label_title_like_host_is_address", label("alice/work/0.1\tread\tbash\talice\t", false), "work/0.1 (bash)");
        eq("label_several_hosts_name_the_host", label("alice/work/0.0\twrite\tclaude\tbuild\t", true), "alice/build (claude)");
        eq("label_missing_columns", label("alice/work/0.0\twrite\tbash", false), "work/0.1 (bash)".replace("0.1", "0.0"));
        String big = "alice/work/0.0\tread\tbash\t";
        for (int i = 0; i < 300; i++) {
            big += "x";
        }
        eq("label_cut_at_64", String.valueOf(label(big, false).length()), "64");
        eq("label_tab_in_title_takes_the_first_part", label("alice/work/0.0\tread\tbash\tsafe\twrite\tmore", false), "safe (bash)");
    }

    private static void pixelTests() {
        int[] w = new int[128];
        for (int i = 0; i < w.length; i++) {
            w[i] = 4;
        }
        w['i'] = 2;
        w['m'] = 6;
        eq("pixel_narrow_letters_fill_the_row", join(Text.wrap(new String[] {"iiiiiiiiii"}, w, 6, 20)), "iiiiiiiiii");
        eq("pixel_wide_letters_wrap_earlier", join(Text.wrap(new String[] {"mmmm"}, w, 6, 20)), "mmm|m");
        eq("pixel_plain_letters", join(Text.wrap(new String[] {"abcdef", ""}, w, 6, 20)), "abcde|f|");
        eq("pixel_unicode_counts_as_wide", join(Text.wrap(new String[] {"\u010d\u0107\u017e\u0161"}, w, 6, 20)), "\u010d\u0107\u017e|\u0161");
        eq("pixel_one_wide_char_per_row", join(Text.wrap(new String[] {"mm"}, w, 6, 5)), "m|m");
        eq("pixel_zero_leaves_rows", join(Text.wrap(new String[] {"mmmmmmmm"}, w, 6, 0)), "mmmmmmmm");
    }

    public static void main(String[] args) {
        lensTests();
        lensAsciiTests();
        wrapTests();
        prefsTests();
        labelTests();
        pixelTests();
        byte[] ascii = "plain".getBytes();
        byte[] utf8 = {(byte) 0xe2, (byte) 0x94, (byte) 0x80, 'x'};
        byte[] latin1 = {'a', (byte) 0xb7, 'b', (byte) 0xe4};
        eq("encoding_latin1", Api.encoding("text/plain; charset=iso-8859-1", ascii), "ISO-8859-1");
        eq("encoding_ascii_reads_as_latin1", Api.encoding("Text/Plain; Charset=US-ASCII", ascii), "ISO-8859-1");
        eq("encoding_utf8_default", Api.encoding("text/plain; charset=utf-8", utf8), "UTF-8");
        eq("encoding_hostile_is_utf8", Api.encoding("text/plain; charset=utf-7; charset=../" + repeat('x', 300), utf8), "UTF-8");
        eq("encoding_missing_is_utf8", Api.encoding(null, ascii), "UTF-8");
        eq("encoding_mislabelled_latin1_by_bytes", Api.encoding("text/plain; charset=utf-8", latin1), "ISO-8859-1");
        eq("utf8_valid", String.valueOf(Text.utf8(utf8)) + Text.utf8(ascii) + Text.utf8(new byte[0]), "truetruetrue");
        eq("utf8_latin1_symbols_invalid", String.valueOf(Text.utf8(latin1)) + Text.utf8(new byte[] {(byte) 0xb7}), "falsefalse");
        eq("utf8_truncated_invalid", String.valueOf(Text.utf8(new byte[] {(byte) 0xe2, (byte) 0x94})), "false");
        eq("utf8_bad_continuation_invalid", String.valueOf(Text.utf8(new byte[] {(byte) 0xc3, 'a'})), "false");
        eq("utf8_overlong_and_high_invalid", String.valueOf(Text.utf8(new byte[] {(byte) 0xc0, (byte) 0x80})) + Text.utf8(new byte[] {(byte) 0xf8}), "falsefalse");
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
