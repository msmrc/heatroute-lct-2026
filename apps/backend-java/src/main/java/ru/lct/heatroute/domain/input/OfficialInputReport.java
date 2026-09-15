package ru.lct.heatroute.domain.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OfficialInputReport {
    private final String contractVersion;
    private final String sha256;
    private final long featureCount;
    private final Map<String, Long> featureCounts;
    private final List<OfficialInputError> errors;

    public OfficialInputReport(
            String contractVersion,
            String sha256,
            long featureCount,
            Map<String, Long> featureCounts,
            List<OfficialInputError> errors) {
        this.contractVersion = contractVersion;
        this.sha256 = sha256;
        this.featureCount = featureCount;
        this.featureCounts = Collections.unmodifiableMap(new LinkedHashMap<>(featureCounts));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public String getContractVersion() { return contractVersion; }
    public String getSha256() { return sha256; }
    public long getFeatureCount() { return featureCount; }
    public Map<String, Long> getFeatureCounts() { return featureCounts; }
    public List<OfficialInputError> getErrors() { return errors; }
    public boolean isValid() { return errors.isEmpty(); }
}
