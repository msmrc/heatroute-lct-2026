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
    private final boolean physicalAssetsDeclared;
    private final List<CatalogPhysicalAsset> physicalAssets;
    private final Map<String, CatalogPhysicalAsset> physicalAssetsById;
    private final List<DirectedPathOption> pathOptions;
    private final Map<String, DirectedPathOption> pathOptionsById;
    private final String catalogHash;

    public RoutingCatalogSnapshot(String sourceSnapshotHash, String ruleId, String ruleVersion,
            String catalogVersion, Collection<DirectedPathOption> pathOptions) {
        this(sourceSnapshotHash, ruleId, ruleVersion, catalogVersion, null, pathOptions);
    }

    /** Production-контракт N03: все ссылки путей разрешаются в объявленные физические активы. */
    public RoutingCatalogSnapshot(String sourceSnapshotHash, String ruleId, String ruleVersion,
            String catalogVersion, Collection<CatalogPhysicalAsset> physicalAssets,
            Collection<DirectedPathOption> pathOptions) {
        this.sourceSnapshotHash = required(sourceSnapshotHash, "source snapshot hash");
        this.ruleId = required(ruleId, "rule ID");
        this.ruleVersion = required(ruleVersion, "rule version");
        this.catalogVersion = required(catalogVersion, "catalog version");
        this.physicalAssetsDeclared = physicalAssets != null;
        List<CatalogPhysicalAsset> orderedAssets = physicalAssets == null
                ? new ArrayList<>() : new ArrayList<>(physicalAssets);
        if (orderedAssets.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Physical asset cannot be null");
        }
        orderedAssets.sort(Comparator.comparing(CatalogPhysicalAsset::getId));
        Map<String, CatalogPhysicalAsset> indexedAssets = new LinkedHashMap<>();
        for (CatalogPhysicalAsset asset : orderedAssets) {
            if (indexedAssets.put(asset.getId(), asset) != null) {
                throw new IllegalArgumentException("Duplicate physical asset ID: " + asset.getId());
            }
        }
        this.physicalAssets = List.copyOf(orderedAssets);
        this.physicalAssetsById = java.util.Collections.unmodifiableMap(indexedAssets);
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
            if (physicalAssetsDeclared) validatePhysicalChain(option);
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
    public boolean hasDeclaredPhysicalAssets() { return physicalAssetsDeclared; }
    public List<CatalogPhysicalAsset> getPhysicalAssets() { return physicalAssets; }
    public CatalogPhysicalAsset physicalAsset(String id) { return physicalAssetsById.get(id); }
    public List<DirectedPathOption> getPathOptions() { return pathOptions; }
    public DirectedPathOption pathOption(String id) { return pathOptionsById.get(id); }
    public String getCatalogHash() { return catalogHash; }

    private String catalogHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, sourceSnapshotHash, ruleId, ruleVersion, catalogVersion);
            update(digest, Boolean.toString(physicalAssetsDeclared), Integer.toString(physicalAssets.size()));
            for (CatalogPhysicalAsset asset : physicalAssets) update(digest, asset.getId(), asset.getFingerprint());
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

    private void validatePhysicalChain(DirectedPathOption option) {
        CatalogMetricPoint current = option.getCoordinates().get(0);
        List<CatalogMetricPoint> reconstructed = new ArrayList<>();
        reconstructed.add(current);
        double exactLength = 0.0;
        for (String assetId : option.getPhysicalAssetIds()) {
            CatalogPhysicalAsset asset = physicalAssetsById.get(assetId);
            if (asset == null) {
                throw new IllegalArgumentException("Path option references an unknown physical asset: " + assetId);
            }
            CatalogMetricPoint next;
            if (current.equals(asset.getFirstPoint())) next = asset.getSecondPoint();
            else if (current.equals(asset.getSecondPoint())) next = asset.getFirstPoint();
            else throw new IllegalArgumentException("Physical asset chain is discontinuous for path option: "
                        + option.getId());
            exactLength += asset.getExactLengthMm();
            reconstructed.add(next);
            current = next;
        }
        List<CatalogMetricPoint> optionPoints = option.getCoordinates();
        if (!current.equals(optionPoints.get(optionPoints.size() - 1))
                || !isSubsequence(optionPoints, reconstructed)) {
            throw new IllegalArgumentException("Physical asset chain does not reconstruct path option: "
                    + option.getId());
        }
        if (Math.round(exactLength) != option.getLengthMm()) {
            throw new IllegalArgumentException("Physical asset chain length differs from path option: "
                    + option.getId());
        }
    }

    private static boolean isSubsequence(List<CatalogMetricPoint> expected,
            List<CatalogMetricPoint> actual) {
        int expectedIndex = 0;
        for (CatalogMetricPoint point : actual) {
            if (expectedIndex < expected.size() && expected.get(expectedIndex).equals(point)) expectedIndex++;
        }
        return expectedIndex == expected.size();
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
