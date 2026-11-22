package com.pocketgammu.engine;

import java.io.IOException;

/** GSM_Protocol_Functions. */
public interface Protocol {
    void initialise() throws IOException;

    void writeMessage(byte[] msg, int length, int type) throws IOException;

    /** Feeds one received byte; complete messages are handed to the session. */
    void stateMachine(int rxChar) throws IOException;

    void terminate();
}
