package ru.lct.heatroute.domain.optimization;

import java.util.Objects;

/** Ожидаемая нехватка конфигурации каталога: требуется расширение без доказательного cut. */
public final class CandidateAssemblyIncompleteException extends RuntimeException {
    private final String reason;

    public CandidateAssemblyIncompleteException(String reason, String message) {
        super(Objects.requireNonNull(message, "message"));
        String value = Objects.requireNonNull(reason, "reason").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Incomplete assembly reason is required");
        this.reason = value;
    }

    public String getReason() { return reason; }
}
