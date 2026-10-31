package muxway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * The hub's client calls, in text mode. One request at a time. The token
 * goes in X-Muxway-Token: WAP 1.x stacks rewrite Authorization.
 */
final class Api {
    static final int MAX_BODY = 32 * 1024;

    private final Prefs prefs;
    private final String agent;

    int status;
    String body = "";
    String etag = "";

    Api(Prefs prefs, String agent) {
        this.prefs = prefs;
        this.agent = agent;
    }

    /** GET when json is null, POST otherwise. Returns the HTTP status. */
    int call(String path, String json, String ifNoneMatch) throws IOException {
        return exchange(path, "application/json", json == null ? null : utf8(json), null, 0, null, ifNoneMatch);
    }

    /**
     * POSTs one image as the multipart form the hub's files call takes. The
     * bytes are streamed from the picture, never held whole.
     */
    int upload(String path, Picture p) throws IOException {
        String boundary = "muxway-midp-" + Long.toString(System.currentTimeMillis(), 16);
        InputStream in = p.open();
        try {
            return exchange(path, "multipart/form-data; boundary=" + boundary, partHead(boundary, p.type()).getBytes(),
                in, p.length(), partTail(boundary).getBytes(), null);
        } finally {
            in.close();
        }
    }

    /** The form's one part, named as the protocol wants; the file's name is never the phone's. */
    static String partHead(String boundary, String type) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\nContent-Type: "
            + type + "\r\n\r\n";
    }

    static String partTail(String boundary) {
        return "\r\n--" + boundary + "--\r\n";
    }

    /** One request: GET when head is null, else POST of head, then length bytes of in, then tail. */
    private int exchange(String path, String contentType, byte[] head, InputStream in, int length, byte[] tail,
                         String ifNoneMatch) throws IOException {
        HttpConnection c = (HttpConnection) Connector.open(prefs.server + "/v1/" + path, Connector.READ_WRITE, true);
        InputStream answer = null;
        try {
            c.setRequestMethod(head == null ? HttpConnection.GET : HttpConnection.POST);
            c.setRequestProperty("X-Muxway-Token", prefs.token);
            c.setRequestProperty("Accept", "text/plain");
            c.setRequestProperty("User-Agent", agent);
            if (ifNoneMatch != null && ifNoneMatch.length() > 0) {
                c.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            if (head != null) {
                int total = head.length + (in == null ? 0 : length + tail.length);
                c.setRequestProperty("Content-Type", contentType);
                c.setRequestProperty("Content-Length", Integer.toString(total));
                OutputStream out = c.openOutputStream();
                out.write(head);
                if (in != null) {
                    copy(in, out, length);
                    out.write(tail);
                }
                out.close();
            }
            status = c.getResponseCode();
            String tag = c.getHeaderField("ETag");
            etag = tag == null ? "" : tag;
            // Read the answer even when it is empty: some phones release their
            // one HTTP connection only after the response stream was opened.
            byte[] raw = new byte[0];
            try {
                answer = c.openInputStream();
                raw = read(answer);
            } catch (IOException e) {
                // Some stacks throw here for an error status; the status alone
                // says what happened then.
                if (status == HttpConnection.HTTP_OK) {
                    throw e;
                }
            }
            body = status == HttpConnection.HTTP_NO_CONTENT || status == 304 ? ""
                : Text.screen(decode(raw, c.getHeaderField("Content-Type")));
            return status;
        } finally {
            if (answer != null) {
                try {
                    answer.close();
                } catch (IOException e) {
                }
            }
            c.close();
        }
    }

    /** Exactly length bytes, in small pieces; a file that ends early is an error, not a short upload. */
    private static void copy(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buf = new byte[4096];
        int left = length;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n <= 0) {
                throw new IOException("file ended early");
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    /** Reads at most MAX_BODY bytes; the rest is left unread. */
    private static byte[] read(InputStream in) throws IOException {
        byte[] buf = new byte[1024];
        byte[] all = new byte[0];
        int n;
        while (all.length < MAX_BODY && (n = in.read(buf, 0, Math.min(buf.length, MAX_BODY - all.length))) > 0) {
            byte[] next = new byte[all.length + n];
            System.arraycopy(all, 0, next, 0, all.length);
            System.arraycopy(buf, 0, next, all.length, n);
            all = next;
        }
        return all;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (IOException e) {
            return s.getBytes();
        }
    }

    /**
     * The encoding to read an answer with: Latin-1 when the Content-Type
     * says so, or when the bytes are not UTF-8 anyway (a relay may label
     * everything UTF-8), else UTF-8.
     */
    static String encoding(String contentType, byte[] raw) {
        String t = contentType == null ? "" : contentType.toLowerCase();
        if (t.indexOf("charset=iso-8859-1") >= 0 || t.indexOf("charset=us-ascii") >= 0 || !Text.utf8(raw)) {
            return "ISO-8859-1";
        }
        return "UTF-8";
    }

    private static String decode(byte[] raw, String contentType) {
        String encoding = encoding(contentType, raw);
        try {
            return new String(raw, encoding);
        } catch (Throwable e) {
            // Some phones throw a RuntimeException for bytes they cannot decode; Latin-1 decodes anything.
            try {
                return new String(raw, "ISO-8859-1");
            } catch (IOException e2) {
                return new String(raw);
            }
        }
    }

    /** A short message for a status the screens do not handle themselves. */
    static String problem(int status) {
        switch (status) {
        case 400:
            return "The hub refused the request.";
        case 401:
            return "Token refused.";
        case 403:
            return "Read only.";
        case 404:
            return "Not found.";
        case 413:
            return "Too long.";
        case 415:
            return "Not an image the host takes.";
        case 429:
            return "Too many tries. Wait and try again.";
        case 502:
        case 503:
            return "Host offline.";
        case 507:
            return "The host's inbox is full.";
        }
        return "Unexpected answer " + status + ".";
    }

    /** JSON string body for the input call. */
    static String text(String s) {
        StringBuffer out = new StringBuffer("{\"text\":\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append("\"}").toString();
    }

    static String key(String name) {
        return "{\"key\":\"" + name + "\"}";
    }
}
