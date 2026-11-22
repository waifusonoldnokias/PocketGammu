package com.pocketgammu.engine;

import java.io.IOException;

public class GsmException extends IOException {
    public final GsmError error;

    public GsmException(GsmError error) {
        super(error.message);
        this.error = error;
    }

    public GsmException(GsmError error, String detail) {
        super(error.message + (detail == null ? "" : " (" + detail + ")"));
        this.error = error;
    }

    public static void check(GsmError e) throws GsmException {
        if (e != GsmError.NONE) throw new GsmException(e);
    }
}
