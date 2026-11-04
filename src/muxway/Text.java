package muxway;

/** String helpers CLDC 1.0 does not have. */
final class Text {
    private Text() {
    }

    static String[] split(String s, char at) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == at) {
                n++;
            }
        }
        String[] out = new String[n];
        int from = 0;
        for (int i = 0; i < n; i++) {
            int to = s.indexOf(at, from);
            if (to < 0) {
                to = s.length();
            }
            out[i] = s.substring(from, to);
            from = to + 1;
        }
        return out;
    }

    /** Cuts rows longer than cols into pieces of cols characters; cols of 0 leaves them. */
    static String[] wrap(String[] rows, int cols) {
        if (cols <= 0) {
            return rows;
        }
        int n = 0;
        for (int i = 0; i < rows.length; i++) {
            n += Math.max(1, (rows[i].length() + cols - 1) / cols);
        }
        String[] out = new String[n];
        n = 0;
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i];
            while (r.length() > cols) {
                out[n++] = r.substring(0, cols);
                r = r.substring(cols);
            }
            out[n++] = r;
        }
        return out;
    }

    /**
     * Cuts rows where the next character would not fit in max pixels.
     * widths holds the width of each character below its length, wide the
     * width of any other; max of 0 leaves the rows.
     */
    static String[] wrap(String[] rows, int[] widths, int wide, int max) {
        if (max <= 0) {
            return rows;
        }
        java.util.Vector out = new java.util.Vector();
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i];
            int from = 0, px = 0;
            for (int j = 0; j < r.length(); j++) {
                char c = r.charAt(j);
                int w = c < widths.length ? widths[c] : wide;
                if (px + w > max && j > from) {
                    out.addElement(r.substring(from, j));
                    from = j;
                    px = 0;
                }
                px += w;
            }
            out.addElement(r.substring(from));
        }
        String[] a = new String[out.size()];
        out.copyInto(a);
        return a;
    }

    /**
     * What the pane list shows for one row of the hub's text list
     * (address, mode, command, title, kind, mail, talk, alias, cwd): the
     * alias where the owner gave one, else the title, and, in brackets,
     * the program. A title that is empty, the program's name or the host's
     * name says nothing, so the pane's session/window.pane stands in; a
     * leading mark is dropped. With
     * panes of several hosts the host leads. Cut at 64 characters.
     */
    static String label(String[] f, boolean hosts) {
        return label(f, hosts, "");
    }

    /** The same with the mail mark after the name, before the program, so a cut keeps it. */
    static String label(String[] f, boolean hosts, String mail) {
        return label(f, hosts, mail, true);
    }

    /**
     * The same, with the folder and the status of the 2.0 build after the
     * name: the last segment of the cwd column, then for a named pane the
     * title the alias replaced, after a middle dot (a dash without one).
     */
    static String label(String[] f, boolean hosts, String mail, boolean dot) {
        String address = f[0];
        int slash = address.indexOf('/');
        String host = slash < 0 ? address : address.substring(0, slash);
        String rest = slash < 0 ? address : address.substring(slash + 1);
        String command = f.length > 2 ? f[2].trim() : "";
        String title = f.length > 3 ? f[3].trim() : "";
        // Claude Code's mark in front of its title is decoration; the list has a mark of its own.
        if (title.startsWith("\u2733 ") || title.startsWith("* ")) {
            title = title.substring(2).trim();
        } else if (title.equals("\u2733") || title.equals("*")) {
            title = "";
        }
        if (title.equals(command) || title.equals(host)) {
            title = "";
        }
        // The owner's alias is the name; the title is what the program says about itself.
        String alias = f.length > 7 ? f[7].trim() : "";
        String name = alias.length() > 0 ? alias : title.length() == 0 ? rest : title;
        if (hosts) {
            name = host + "/" + name;
        }
        if (Ui.NAMES) {
            String cwd = f.length > 8 ? f[8].trim() : "";
            String folder = cwd.substring(cwd.lastIndexOf('/') + 1);
            if (folder.length() > 0) {
                name += " /" + folder;
            }
            if (alias.length() > 0 && title.length() > 0) {
                name += (dot ? " \u00b7 " : " - ") + title;
            }
        }
        if (Ui.TALK && mail.length() > 0) {
            // The name gives way, the count stays within the cut.
            int room = 63 - mail.length();
            name = (name.length() > room ? name.substring(0, room) : name) + " " + mail;
        }
        if (command.length() > 0) {
            name += " (" + command + ")";
        }
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /**
     * The mail column of a listing as the list shows it: an envelope and
     * the unread count, M where the font has no envelope; empty when there
     * is none, or when the column is not one to three digits.
     */
    static String mail(String column, boolean unicode) {
        column = column.trim();
        if (column.length() == 0 || column.length() > 3 || column.equals("0")) {
            return "";
        }
        for (int i = 0; i < column.length(); i++) {
            if (column.charAt(i) < '0' || column.charAt(i) > '9') {
                return "";
            }
        }
        return (unicode ? "\u2709 " : "M ") + column;
    }

    /** The kind column of a listing as a mark: what the pane waits for, zz once its owner parked it, a space for nothing. */
    static String mark(String kind) {
        if (kind.equals("question")) {
            return "?";
        } else if (kind.equals("permission")) {
            return "!";
        } else if (kind.equals("idle")) {
            return "z";
        } else if (kind.equals("done")) {
            return "v";
        } else if (kind.equals("later")) {
            return "zz";
        }
        return " ";
    }

    /**
     * What the inbox shows for one row of the hub's text inbox (address,
     * kind, at, text, choices): the kind's mark, the pane's name and the
     * text. Cut at 64 characters.
     */
    static String inboxLabel(String[] f, String name) {
        String text = f.length > 3 ? f[3].trim() : "";
        String label = mark(f[1].trim()) + " " + name + (text.length() > 0 ? ": " + text : "");
        return label.length() > 64 ? label.substring(0, 64) : label;
    }

    /** The choices column split on |: at most eight, each cut to 40 characters; null when there are none. */
    static String[] choices(String column) {
        String[] all = split(column, '|');
        int n = 0;
        for (int i = 0; i < all.length && n < 8; i++) {
            all[i] = all[i].trim();
            if (all[i].length() > 40) {
                all[i] = all[i].substring(0, 40);
            }
            if (all[i].length() > 0) {
                all[n++] = all[i];
            }
        }
        if (n < 2) {
            return null;
        }
        String[] out = new String[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }

    /** True when the bytes are well-formed UTF-8; Latin-1 text with accents or symbols is not. */
    static boolean utf8(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xff, n;
            if (c < 0x80) {
                n = 0;
            } else if (c >= 0xc2 && c < 0xe0) {
                n = 1;
            } else if (c >= 0xe0 && c < 0xf0) {
                n = 2;
            } else if (c >= 0xf0 && c < 0xf5) {
                n = 3;
            } else {
                return false;
            }
            if (i + n >= b.length) {
                return false;
            }
            for (int j = 1; j <= n; j++) {
                if ((b[i + j] & 0xc0) != 0x80) {
                    return false;
                }
            }
            i += n + 1;
        }
        return true;
    }

    /** A server the app may call: http or https, a host and maybe a path; nothing a query or a login could hide in. */
    static boolean url(String s) {
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '?' || c == '#' || c == '@') {
                return false;
            }
        }
        return true;
    }

    /** True when the server is the phone itself or on a private network (RFC 1918). */
    static boolean local(String url) {
        String h = url.substring(url.indexOf("//") + 2);
        int cut = h.indexOf('/');
        if (cut >= 0) {
            h = h.substring(0, cut);
        }
        if (h.startsWith("[")) {
            return h.startsWith("[::1]");
        }
        cut = h.indexOf(':');
        if (cut >= 0) {
            h = h.substring(0, cut);
        }
        if (h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            int second = toInt(split(h, '.')[1], 0);
            return second >= 16 && second <= 31;
        }
        return false;
    }

    /** A pane address as the protocol spells it: a host, then session/window.pane or @alias. */
    static boolean address(String a) {
        int slash = a.indexOf('/');
        if (slash < 1 || slash > 32 || slash == a.length() - 1 || a.indexOf("..") >= 0 || a.indexOf("//") >= 0) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            boolean host = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            boolean rest = i >= slash && ((c >= 'A' && c <= 'Z') || c == '.' || c == '_' || c == '/' || (c == '@' && i == slash + 1));
            if (!host && !rest) {
                return false;
            }
        }
        return true;
    }

    static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Keeps printable characters, newlines and tabs (list columns). */
    static String screen(String s) {
        StringBuffer out = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f && (c < 0x80 || c > 0x9f))) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Drops control characters (C0, DEL, C1); the hub refuses them in input. */
    static String printable(String s) {
        StringBuffer out = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f && (c < 0x80 || c > 0x9f)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The value of one string key in a small JSON object, such as the path
     * in the answer to an upload. Null when the key is missing, the value
     * is not a string, or an escape is one the app does not know.
     */
    static String jsonString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        i = blank(json, i + key.length() + 2);
        if (i >= json.length() || json.charAt(i) != ':') {
            return null;
        }
        i = blank(json, i + 1);
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        StringBuffer out = new StringBuffer();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= json.length()) {
                return null;
            }
            c = json.charAt(i);
            if (c == '"' || c == '\\' || c == '/') {
                out.append(c);
            } else if (c == 'n' || c == 't' || c == 'r' || c == 'b' || c == 'f') {
                out.append(' ');
            } else if (c == 'u' && i + 4 < json.length()) {
                try {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException e) {
                    return null;
                }
                i += 4;
            } else {
                return null;
            }
        }
        return null;
    }

    private static int blank(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
            i++;
        }
        return i;
    }

    /** The type of an image file by its extension; null for anything else. */
    static String imageType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }

    /** The type of an image by its first bytes, JPEG or PNG; null for anything else. */
    static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (b.length > 3 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }
}
