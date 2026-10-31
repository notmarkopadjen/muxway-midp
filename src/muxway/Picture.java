package muxway;

import java.io.IOException;
import java.io.InputStream;

/** An image on its way to a pane's host: a camera shot, or a file on the phone. */
interface Picture {
    String type();

    int length();

    InputStream open() throws IOException;

    /** Lets go of the file; a shot's bytes go with the object. */
    void close();
}
