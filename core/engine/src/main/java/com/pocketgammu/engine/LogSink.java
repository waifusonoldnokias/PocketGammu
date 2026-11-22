package com.pocketgammu.engine;

/** Where the protocol log goes (the app's Log screen, or stdout in tests). */
public interface LogSink {
    void log(String line);

    LogSink NONE = new LogSink() {
        @Override
        public void log(String line) {}
    };

    LogSink STDOUT = new LogSink() {
        @Override
        public void log(String line) {
            System.out.println(line);
        }
    };
}
