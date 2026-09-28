package ru.lct.heatroute.domain.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Один атомарный физический участок. Ориентация координат каноническая и не задаёт направление
 * потока; направление хранится в привязке конкретного пути.
 */
public final class CatalogPhysicalAsset {
    private final String id;
    private final String physicalContext;
    private final ConstructionMode constructionMode;
    private final CatalogMetricPoint firstPoint;
    private final CatalogMetricPoint secondPoint;
    private final List<String> sourcePathIds;
    private final String fingerprint;

    public CatalogPhysicalAsset(String id, String physicalContext,
            CatalogMetricPoint firstPoint, CatalogMetricPoint secondPoint,
            Collection<String> sourcePathIds) {
        this(id, physicalContext, ConstructionMode.NEW_CONSTRUCTION,
                firstPoint, secondPoint, sourcePathIds);
    }

    public CatalogPhysicalAsset(String id, String physicalContext, ConstructionMode constructionMode,
            CatalogMetricPoint firstPoint, CatalogMetricPoint secondPoint,
            Collection<String> sourcePathIds) {
        this.id = required(id, "physical asset ID");
        this.physicalContext = required(physicalContext, "physical context");
        this.constructionMode = Objects.requireNonNull(constructionMode, "constructionMode");
        this.firstPoint = Objects.requireNonNull(firstPoint, "firstPoint");
        this.secondPoint = Objects.requireNonNull(secondPoint, "secondPoint");
        if (compare(firstPoint, secondPoint) >= 0) {
            throw new IllegalArgumentException("Physical asset points must be distinct and canonical");
        }
        if (sourcePathIds == null || sourcePathIds.isEmpty()) {
            throw new IllegalArgumentException("Physical asset requires source paths");
        }
        List<String> sources = new ArrayList<>(sourcePathIds.size());
        for (String sourcePathId : sourcePathIds) {
            sources.add(required(sourcePathId, "source path ID"));
        }
        sources.sort(Comparator.naturalOrder());
        Set<String> unique = new LinkedHashSet<>(sources);
        if (unique.size() != sources.size()) {
            throw new IllegalArgumentException("Physical asset source paths must be unique");
        }
        this.sourcePathIds = List.copyOf(sources);
        this.fingerprint = fingerprintOf();
    }

    public String getId() { return id; }
    public String getPhysicalContext() { return physicalContext; }
    public ConstructionMode getConstructionMode() { return constructionMode; }
    public CatalogMetricPoint getFirstPoint() { return firstPoint; }
    public CatalogMetricPoint getSecondPoint() { return secondPoint; }
    public List<String> getSourcePathIds() { return sourcePathIds; }
    public String getFingerprint() { return fingerprint; }

    public double getExactLengthMm() {
        return Math.hypot((double) secondPoint.getXMm() - firstPoint.getXMm(),
                (double) secondPoint.getYMm() - firstPoint.getYMm());
    }

    static int compare(CatalogMetricPoint left, CatalogMetricPoint right) {
        int byX = Long.compare(left.getXMm(), right.getXMm());
        return byX != 0 ? byX : Long.compare(left.getYMm(), right.getYMm());
    }

    private String fingerprintOf() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, id, physicalContext, constructionMode, firstPoint.getXMm(), firstPoint.getYMm(),
                    secondPoint.getXMm(), secondPoint.getYMm(), sourcePathIds.size());
            for (String sourcePathId : sourcePathIds) update(digest, sourcePathId);
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    public enum ConstructionMode { NEW_CONSTRUCTION, RECONSTRUCTION }
}
