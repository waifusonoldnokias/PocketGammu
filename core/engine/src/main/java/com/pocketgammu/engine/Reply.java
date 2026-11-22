package com.pocketgammu.engine;

/** One row of a GSM_Reply_Function table. */
public final class Reply {
    public interface Handler {
        GsmError handle(ProtocolMessage msg, Session s);
    }

    public final int msgType;
    public final int subtypeChar;
    public final int subtype;
    public final RequestId requestId;
    public final Handler handler;

    public Reply(int msgType, int subtypeChar, int subtype, RequestId requestId, Handler handler) {
        this.msgType = msgType;
        this.subtypeChar = subtypeChar;
        this.subtype = subtype;
        this.requestId = requestId;
        this.handler = handler;
    }

    public static final Handler NONE = new Handler() {
        @Override
        public GsmError handle(ProtocolMessage msg, Session s) {
            return GsmError.NONE;
        }
    };

    /** CheckReplyFunctions' matching rule for single-byte Nokia message types. */
    boolean matches(ProtocolMessage msg) {
        if (msgType != msg.type) return false;
        if (subtypeChar == 0) return true;
        return subtypeChar <= msg.length && msg.u(subtypeChar) == subtype;
    }
}
