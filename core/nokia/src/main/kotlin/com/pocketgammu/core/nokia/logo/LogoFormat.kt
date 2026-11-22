package com.pocketgammu.core.nokia.logo

/**
 * Canvas presets for the logo editor. [smsPort] is null when the logo type
 * can only be written over a cable (startup logos).
 */
enum class LogoFormat(
    val label: String,
    val width: Int,
    val height: Int,
    val kind: LogoKind,
) {
    OPERATOR_DCT3("Operator logo", 72, 14, LogoKind.OPERATOR),
    OPERATOR_DCT4("Operator logo (DCT4 / S40)", 78, 21, LogoKind.OPERATOR),
    CALLER_GROUP("Caller group logo", 72, 14, LogoKind.CALLER_GROUP),
    STARTUP_DCT3("Startup logo (3310 class)", 84, 48, LogoKind.STARTUP),
    STARTUP_7110("Startup logo (7110 class)", 96, 65, LogoKind.STARTUP),
    PICTURE("Picture message", 72, 28, LogoKind.PICTURE),
    ;

    val smsPort: Int? get() = kind.smsPort
    val sizeLabel: String get() = "${width}×$height"
}

enum class LogoKind(val smsPort: Int?) {
    OPERATOR(0x1582),
    CALLER_GROUP(0x1583),
    STARTUP(null),
    /** Nokia picture message (Smart Messaging 3.0): 72×28 picture plus an optional text. */
    PICTURE(0x158A),
}
