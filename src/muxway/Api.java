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
        HttpConnection c = (HttpConnection) Connector.open(prefs.server + "/v1/" + path, Connector.READ_WRITE, true);
        InputStream in = null;
        try {
            c.setRequestMethod(json == null ? HttpConnection.GET : HttpConnection.POST);
            c.setRequestProperty("X-Muxway-Token", prefs.token);
            c.setRequestProperty("Accept", "text/plain");
            c.setRequestProperty("User-Agent", agent);
            if (ifNoneMatch != null && ifNoneMatch.length() > 0) {
                c.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            if (json != null) {
                byte[] data = utf8(json);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Content-Length", Integer.toString(data.length));
                OutputStream out = c.openOutputStream();
                out.write(data);
                out.close();
            }
            status = c.getResponseCode();
            String tag = c.getHeaderField("ETag");
            etag = tag == null ? "" : tag;
            // Read the answer even when it is empty: some phones release their
            // one HTTP connection only after the response stream was opened.
            byte[] raw = new byte[0];
            try {
                in = c.openInputStream();
                raw = read(in);
            } catch (IOException e) {
                // Some stacks throw here for an error status; the status alone
                // says what happened then.
                if (status == HttpConnection.HTTP_OK) {
                    throw e;
                }
            }
            body = status == HttpConnection.HTTP_NO_CONTENT || status == 304 ? "" : Text.screen(decode(raw));
            return status;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
            c.close();
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

    private static String decode(byte[] raw) {
        try {
            return new String(raw, "UTF-8");
        } catch (IOException e) {
            return new String(raw);
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
        case 429:
            return "Too many tries. Wait and try again.";
        case 502:
        case 503:
            return "Host offline.";
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
