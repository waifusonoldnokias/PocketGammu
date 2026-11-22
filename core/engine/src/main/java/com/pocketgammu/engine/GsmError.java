package com.pocketgammu.engine;

/** Subset of Gammu's GSM_Error codes, with Gammu's own wording where it exists. */
public enum GsmError {
    NONE("No error."),
    DEVICEOPENERROR("Error opening device. Unknown, busy or no permissions."),
    DEVICEWRITEERROR("Error writing to the device."),
    DEVICEREADERROR("Error during reading from the device."),
    DEVICECHANGESPEEDERROR("Error setting device speed. Maybe speed not supported."),
    DEVICEDTRRTSERROR("Error setting device DTR or RTS."),
    TIMEOUT("No response in specified timeout. Probably phone not connected."),
    UNKNOWNRESPONSE("Unknown response from phone. See README for more details on how to report bugs."),
    UNKNOWNFRAME("Unknown frame. See README for more details on how to report bugs."),
    FRAMENOTREQUESTED("Frame not requested right now."),
    NOTSUPPORTED("Function not supported by phone."),
    NOTIMPLEMENTED("Function not implemented. Help required."),
    INVALIDLOCATION("Invalid location. Maybe too high?"),
    INVALIDDATA("Invalid data given to phone."),
    SECURITYERROR("Security error. Maybe no PIN?"),
    GETTING_SMSC("Failed to get SMSC number from phone."),
    EMPTYSMSC("No SMSC number given. Provide it manually or use the one configured in phone."),
    UNKNOWNMODELSTRING("Unknown model type string. Check config file."),
    NOTCONNECTED("Phone is not connected."),
    IRDA_NODEVICE("No IrDA device answered discovery. Is the phone's infrared switched on and aimed at the dongle?"),
    IRDA_SERVICE("The phone does not offer the Nokia:PhoNet IrDA service."),
    IRDA_LINK("IrDA link lost."),
    SMS_FAILED("Phone reported that sending the SMS failed."),
    EMPTY("Empty entry or end of list."),
    FILENOTEXIST("File with specified name doesn't exist."),
    FILEALREADYEXIST("File with specified name already exist."),
    SHOULDBEFILE("You need to give a file, not a folder."),
    SHOULDBEFOLDER("You need to give a folder, not a file."),
    FOLDERNOTEMPTY("Folder must be empty."),
    FOLDERPART("Only part of folder has been listed."),
    WRONGCRC("CRC error."),
    MEMORY("Phone memory error, maybe it is read only (or no memory card)."),
    ABORTED("Operation aborted."),
    UNKNOWN("Unknown error.");

    public final String message;

    GsmError(String message) {
        this.message = message;
    }
}
