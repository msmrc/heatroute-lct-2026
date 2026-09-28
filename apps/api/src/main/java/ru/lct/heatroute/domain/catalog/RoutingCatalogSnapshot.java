package ru.lct.heatroute.domain.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Версионированный конечный каталог; геометрия и сертификаты не меняются после создания. */
public final class RoutingCatalogSnapshot {
    private final String sourceSnapshotHash;
    private final String ruleId;
    private final String ruleVersion;
    private final String catalogVersion;
    private final List<DirectedPathOption> pathOptions;
    private final Map<String, DirectedPathOption> pathOptionsById;
    private final String catalogHash;

    public RoutingCatalogSnapshot(String sourceSnapshotHash, String ruleId, String ruleVersion,
            String catalogVersion, Collection<DirectedPathOption> pathOptions) {
        this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.catalogVersion = required(catalogVersion, "catalog version");
        if (pathOptions == null) throw new IllegalArgumentException("Path options are required");
        List<DirectedPathOption> ordered = new ArrayList<>(pathOptions);
        if (ordered.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Path option cannot be null");
        ordered.sort(Comparator.comparing(DirectedPathOption::getId));
        Map<String, DirectedPathOption> indexed = new LinkedHashMap<>();
        for (DirectedPathOption option : ordered) {
            if (!sourceSnapshotHash.equals(option.getProvenance().getSourceSnapshotHash())) {
                throw new IllegalArgumentException("Path option belongs to another source snapshot: " + option.getId());
            }
            for (PathAdmissionCertificate certificate : option.getCertificatesByDiameter().values()) {
                if (!ruleId.equals(certificate.getRuleId()) || !ruleVersion.equals(certificate.getRuleVersion())) {
                    throw new IllegalArgumentException("Path certificate belongs to another rule version: "
                            + option.getId());
                }
            }
            if (indexed.put(option.getId(), option) != null) {
                throw new IllegalArgumentException("Duplicate path option ID: " + option.getId());
            }
        }
        this.pathOptions = List.copyOf(ordered);
        this.pathOptionsById = java.util.Collections.unmodifiableMap(indexed);
        this.catalogHash = catalogHash();
    }

    public String getSourceSnapshotHash() { return sourceSnapshotHash; }
    public String getRuleId() { return ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public String getCatalogVersion() { return catalogVersion; }
    public List<DirectedPathOption> getPathOptions() { return pathOptions; }
    public DirectedPathOption pathOption(String id) { return pathOptionsById.get(id); }
    public String getCatalogHash() { return catalogHash; }

    private String catalogHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, sourceSnapshotHash, ruleId, ruleVersion, catalogVersion);
            update(digest, Integer.toString(pathOptions.size()));
            for (DirectedPathOption option : pathOptions) update(digest, option.getId(), option.getFingerprint());
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
