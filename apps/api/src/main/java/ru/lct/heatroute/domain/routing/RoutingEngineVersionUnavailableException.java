package ru.lct.heatroute.domain.routing;

/** Поставленная в очередь версия движка отсутствует в текущей сборке и не может быть подменена. */
public final class RoutingEngineVersionUnavailableException extends IllegalStateException {
    private final String requestedVersion;
    private final String availableVersion;

    public RoutingEngineVersionUnavailableException(String requestedVersion, String availableVersion) {
        super("Queued routing engine version is unavailable");
        this.requestedVersion = requestedVersion;
        this.availableVersion = availableVersion;
    }

    public String getRequestedVersion() { return requestedVersion; }
    public String getAvailableVersion() { return availableVersion; }
}
