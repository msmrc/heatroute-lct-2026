package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.CatalogBuildResult;
import ru.lct.heatroute.domain.catalog.CatalogNetworkProblemCompiler;
import ru.lct.heatroute.domain.catalog.RoutingCatalogSnapshot;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.optimization.CatalogIdentity;
import ru.lct.heatroute.domain.optimization.CpSatNetworkOptimizer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Compiles one immutable catalog snapshot into an executable adaptive-search stage. */
@Component
public final class CatalogNetworkStageCompiler {
    private final CatalogNetworkProblemCompiler problemCompiler;
    private final CatalogFrozenCandidateAssembler candidateAssembler =
            new CatalogFrozenCandidateAssembler();

    public CatalogNetworkStageCompiler(CatalogNetworkProblemCompiler problemCompiler) {
        this.problemCompiler = Objects.requireNonNull(problemCompiler, "problemCompiler");
    }

    public AdaptiveCatalogNetworkSearch.Stage compile(RoutingProblemSnapshot problemSnapshot,
            CatalogBuildResult buildResult, Map<String, String> demandPortById,
            Map<String, String> rootPortById, int flowScaleDecimals,
            String checkerVersion, String candidateIdPrefix, String strategy,
            NodeRealizationResolver nodeRealizationResolver,
            Collection<ImportedOfficialFeature> relevantFeatures,
            CatalogFrozenCandidateAssembler.EdgeSectionAssembler sectionAssembler) {
        Objects.requireNonNull(problemSnapshot, "problemSnapshot");
        Objects.requireNonNull(buildResult, "buildResult");
        String checker = required(checkerVersion, "checker version");
        String idPrefix = required(candidateIdPrefix, "candidate ID prefix");
        String candidateStrategy = required(strategy, "strategy");
        Objects.requireNonNull(nodeRealizationResolver, "nodeRealizationResolver");
        Objects.requireNonNull(relevantFeatures, "relevantFeatures");
        Objects.requireNonNull(sectionAssembler, "sectionAssembler");
        RoutingCatalogSnapshot catalog = buildResult.getSnapshot();
        if (!problemSnapshot.getSnapshotHash().equals(catalog.getSourceSnapshotHash())
                || !problemSnapshot.getRuleId().equals(catalog.getRuleId())
                || !problemSnapshot.getRuleVersion().equals(catalog.getRuleVersion())) {
            throw new IllegalArgumentException("Catalog build result belongs to another problem/rule scope");
        }

        CatalogNetworkProblemCompiler.Compilation compilation = problemCompiler.compile(
                problemSnapshot, catalog, demandPortById, rootPortById, flowScaleDecimals);
        Map<String, CatalogFrozenCandidateAssembler.NodeRealization> nodeRealizations = Map.copyOf(
                Objects.requireNonNull(nodeRealizationResolver.resolve(compilation),
                        "node realizations"));
        List<ImportedOfficialFeature> features = freezeFeatures(relevantFeatures);
        CatalogIdentity identity = CatalogIdentity.fromProblem(
                catalog.getSourceSnapshotHash(), catalog.getRuleId(), catalog.getRuleVersion(),
                checker, catalog.getCatalogHash(), compilation.getProblem());
        CatalogFrozenNetworkRefinement.CandidateFactory candidateFactory = master ->
                candidateAssembler.assembleDetailed(
                        candidateId(idPrefix, master), candidateStrategy,
                        problemSnapshot, catalog, compilation, master, nodeRealizations,
                        features, sectionAssembler);
        return new AdaptiveCatalogNetworkSearch.Stage(
                buildResult, compilation.getProblem(), identity, candidateFactory);
    }

    private static String candidateId(String prefix, CpSatNetworkOptimizer.Result master) {
        List<String> assignment = new ArrayList<>();
        master.getSelectedRoots().stream().sorted().forEach(root -> assignment.add("root:" + root));
        master.getSelectedAssets().stream().sorted().forEach(asset -> assignment.add(
                "asset:" + asset + ":flow=" + master.getFlowUnits().get(asset)
                        + ":du=" + master.getDiameterMm().get(asset)));
        return prefix + "-" + sha256(assignment).substring(0, 16);
    }

    private static List<ImportedOfficialFeature> freezeFeatures(
            Collection<ImportedOfficialFeature> supplied) {
        List<ImportedOfficialFeature> result = new ArrayList<>(supplied.size());
        for (ImportedOfficialFeature feature : supplied) {
            Objects.requireNonNull(feature, "relevant feature");
            JsonNode attributes = feature.getAttributes();
            Geometry geometry = feature.getMetricGeometry();
            result.add(new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                    attributes == null ? null : attributes.deepCopy(),
                    geometry == null ? null : geometry.copy()));
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return List.copyOf(result);
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.stream().sorted(Comparator.naturalOrder()).forEach(value -> {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            });
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte value : digest.digest()) formatter.format("%02x", value);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    @FunctionalInterface
    public interface NodeRealizationResolver {
        Map<String, CatalogFrozenCandidateAssembler.NodeRealization> resolve(
                CatalogNetworkProblemCompiler.Compilation compilation);
    }
}
