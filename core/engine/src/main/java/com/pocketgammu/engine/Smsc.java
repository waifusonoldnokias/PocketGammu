package com.pocketgammu.engine;

/** GSM_SMSC subset. */
public final class Smsc {
    public int location;
    public String number = "";
    public String name = "";
    public String defaultNumber = "";
    /** TP-VP relative value; 0xFF ("maximum time") by default as in Gammu. */
    public int validityRelative = 0xFF;
}
