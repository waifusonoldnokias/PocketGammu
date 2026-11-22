package com.pocketgammu.engine;

/** GSM_NetworkInfo subset. */
public final class NetworkInfo {
    public enum State { HOME, ROAMING, REQUESTING, NONE, DENIED, UNKNOWN }

    public State state = State.UNKNOWN;
    /** Gammu format "MCC MNC", e.g. "466 92". */
    public String networkCode = "";
    public String networkName = "";
    public String lac = "", cid = "";

    public boolean registered() {
        return state == State.HOME || state == State.ROAMING;
    }

    @Override
    public String toString() {
        if (!registered()) return state.name().toLowerCase();
        return networkCode + (networkName.isEmpty() ? "" : " (" + networkName + ")")
                + (state == State.ROAMING ? ", roaming" : "");
    }
}
