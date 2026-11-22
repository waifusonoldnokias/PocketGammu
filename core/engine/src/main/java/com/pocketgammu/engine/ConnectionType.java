package com.pocketgammu.engine;

/** Gammu connection types this app supports. The names match gammurc "connection = ...". */
public enum ConnectionType {
    FBUS("fbus", "FBUS cable (DAU-9P or FBUS TTL cable)", false),
    DLR3("dlr3", "DLR-3P cable (AT*NOKIAFBUS switch)", false),
    DKU5("dku5", "DKU-5 / CA-42 cable (AT*NOKIAFBUS switch)", false),
    FBUSPL2303("fbuspl2303", "FBUS over PL2303 cable (CA-42 clones)", false),
    FBUSIRDA("fbusirda", "FBUS over infrared (raw, 6210/7110/8210 era)", true),
    IRDAPHONET("irdaphonet", "IrDA PhoNet (full IrDA stack)", true),
    DKU2("dku2", "Nokia USB cable: DKU-2, CA-53 or the phone's own USB port", false);

    public final String gammuName;
    public final String label;
    public final boolean infrared;

    ConnectionType(String gammuName, String label, boolean infrared) {
        this.gammuName = gammuName;
        this.label = label;
        this.infrared = infrared;
    }

    public boolean isPhonet() {
        return this == IRDAPHONET || this == DKU2;
    }

    public static ConnectionType byName(String n) {
        for (ConnectionType t : values()) if (t.gammuName.equalsIgnoreCase(n)) return t;
        throw new IllegalArgumentException(n);
    }
}
