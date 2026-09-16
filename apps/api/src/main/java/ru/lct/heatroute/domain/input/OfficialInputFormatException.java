package ru.lct.heatroute.domain.input;

public class OfficialInputFormatException extends RuntimeException {
    public OfficialInputFormatException(String message) {
        super(message);
    }

    public OfficialInputFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
