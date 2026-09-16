package ru.lct.heatroute.domain.reconstruction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExistingNetworkReconstructionResult {
    private final List<NetworkReconstructionSection> networkSections;
    private final List<ChamberReconstruction> chambers;
    private final List<ReconstructionIssue> issues;

    public ExistingNetworkReconstructionResult(
            List<NetworkReconstructionSection> networkSections,
            List<ChamberReconstruction> chambers,
            List<ReconstructionIssue> issues) {
        this.networkSections = immutable(networkSections);
        this.chambers = immutable(chambers);
        this.issues = immutable(issues);
    }

    public List<NetworkReconstructionSection> getNetworkSections() { return networkSections; }
    public List<ChamberReconstruction> getChambers() { return chambers; }
    public List<ReconstructionIssue> getIssues() { return issues; }
    public boolean isAvailable() { return issues.isEmpty(); }

    public static ExistingNetworkReconstructionResult empty() {
        return new ExistingNetworkReconstructionResult(List.of(), List.of(), List.of());
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
