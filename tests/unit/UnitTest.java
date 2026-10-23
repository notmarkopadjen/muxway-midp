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

    public static void main(String[] args) {
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
