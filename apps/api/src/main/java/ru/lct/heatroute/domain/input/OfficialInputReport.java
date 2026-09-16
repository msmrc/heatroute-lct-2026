package ru.lct.heatroute.domain.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OfficialInputReport {
    private final String contractVersion;
    private final String inputProfile;
    private final String sha256;
    private final long featureCount;
    private final Map<String, Long> featureCounts;
    private final List<OfficialInputError> errors;
    private final List<OfficialInputWarning> warnings;

    public OfficialInputReport(
            String contractVersion,
            String inputProfile,
            String sha256,
            long featureCount,
            Map<String, Long> featureCounts,
            List<OfficialInputError> errors,
            List<OfficialInputWarning> warnings) {
        this.contractVersion = contractVersion;
        this.inputProfile = inputProfile;
        this.sha256 = sha256;
        this.featureCount = featureCount;
        this.featureCounts = Collections.unmodifiableMap(new LinkedHashMap<>(featureCounts));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public String getContractVersion() { return contractVersion; }
    public String getInputProfile() { return inputProfile; }
    public String getSha256() { return sha256; }
    public long getFeatureCount() { return featureCount; }
    public Map<String, Long> getFeatureCounts() { return featureCounts; }
    public List<OfficialInputError> getErrors() { return errors; }
    public List<OfficialInputWarning> getWarnings() { return warnings; }
    public boolean isValid() { return errors.isEmpty(); }
}
