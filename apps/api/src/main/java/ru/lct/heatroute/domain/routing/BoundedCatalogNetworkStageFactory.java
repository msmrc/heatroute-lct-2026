package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
import ru.lct.heatroute.domain.catalog.DirectedPathOption;
import ru.lct.heatroute.domain.optimization.CandidateAssemblyIncompleteException;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Собирает начальную стадию N03→N05 с единым подготовленным окном объектов. */
@Component
public final class BoundedCatalogNetworkStageFactory {
    private final RoutingFeatureWindowFactory windowFactory;
    private final BoundedRootDemandCatalogGenerator catalogGenerator;
    private final CatalogProblemNodeRealizationResolver nodeResolver;
    private final CatalogEdgeSectionAssemblerFactory sectionAssemblerFactory;
    private final CatalogNetworkStageCompiler stageCompiler;

    public BoundedCatalogNetworkStageFactory(
            RoutingFeatureWindowFactory windowFactory,
            BoundedRootDemandCatalogGenerator catalogGenerator,
            CatalogProblemNodeRealizationResolver nodeResolver,
            CatalogEdgeSectionAssemblerFactory sectionAssemblerFactory,
            CatalogNetworkStageCompiler stageCompiler) {
        this.windowFactory = Objects.requireNonNull(windowFactory, "windowFactory");
        this.catalogGenerator = Objects.requireNonNull(catalogGenerator, "catalogGenerator");
        this.nodeResolver = Objects.requireNonNull(nodeResolver, "nodeResolver");
        this.sectionAssemblerFactory = Objects.requireNonNull(
                sectionAssemblerFactory, "sectionAssemblerFactory");
        this.stageCompiler = Objects.requireNonNull(stageCompiler, "stageCompiler");
    }

    public Preparation prepare(RoutingProblemSnapshot problem,
            Collection<ImportedOfficialFeature> relevantFeatures,
            BoundedRootDemandCatalogGenerator.Options options,
            int flowScaleDecimals, String checkerVersion,
            String candidateIdPrefix, String strategy) {
        Objects.requireNonNull(problem, "problem");
        PreparedRoutingFeatureWindow window = windowFactory.prepare(relevantFeatures);
        BoundedRootDemandCatalogGenerator.GeneratedCatalog generated =
                catalogGenerator.generate(problem, window, options);
        CatalogEdgeSectionAssemblerFactory.PreparedAssembler sectionAssembler =
                sectionAssemblerFactory.prepare(window);
        if (generated.getBuildResult().getSnapshot().getPathOptions().isEmpty()) {
            return new Preparation(generated, window, sectionAssembler, null, "empty_catalog");
        }
        Set<String> availablePorts = new LinkedHashSet<>();
        for (DirectedPathOption option
                : generated.getBuildResult().getSnapshot().getPathOptions()) {
            availablePorts.add(option.getFromPortId());
            availablePorts.add(option.getToPortId());
        }
        Map<String, String> demandPorts = representedPorts(
                generated.getDemandPortById(), availablePorts);
        List<String> uncoveredDemands = new ArrayList<>();
        Set<String> structurallyUnroutable =
                generated.getStructurallyUnroutableDemandIds();
        problem.getDemands().forEach(demand -> {
            if (!demandPorts.containsKey(demand.getId())
                    && !structurallyUnroutable.contains(demand.getId())) {
                uncoveredDemands.add(demand.getId());
            }
        });
        if (demandPorts.isEmpty()) {
            return new Preparation(generated, window, sectionAssembler, null,
                    "no_routable_demands:" + String.join(",", uncoveredDemands));
        }
        Set<String> excludedDemands = new LinkedHashSet<>(structurallyUnroutable);
        excludedDemands.addAll(uncoveredDemands);
        Map<String, String> rootPorts = representedPorts(
                generated.getRootPortById(), availablePorts);
        if (rootPorts.isEmpty()) {
            return new Preparation(generated, window, sectionAssembler, null,
                    "uncovered_roots");
        }
        try {
            AdaptiveCatalogNetworkSearch.Stage stage = stageCompiler.compilePrepared(
                    problem, generated.getBuildResult(), demandPorts,
                    rootPorts, flowScaleDecimals, checkerVersion,
                    candidateIdPrefix, strategy,
                    compilation -> nodeResolver.resolve(problem, compilation,
                            demandPorts, rootPorts),
                    window, sectionAssembler, excludedDemands);
            return new Preparation(generated, window, sectionAssembler, stage, null);
        } catch (CandidateAssemblyIncompleteException exception) {
            return new Preparation(generated, window, sectionAssembler, null,
                    exception.getReason());
        }
    }

    private static Map<String, String> representedPorts(
            Map<String, String> supplied, Set<String> availablePorts) {
        Map<String, String> result = new LinkedHashMap<>();
        supplied.forEach((owner, port) -> {
            if (availablePorts.contains(port)) result.put(owner, port);
        });
        return Collections.unmodifiableMap(result);
    }

    public static final class Preparation {
        private final BoundedRootDemandCatalogGenerator.GeneratedCatalog generatedCatalog;
        private final PreparedRoutingFeatureWindow featureWindow;
        private final CatalogEdgeSectionAssemblerFactory.PreparedAssembler sectionAssembler;
        private final AdaptiveCatalogNetworkSearch.Stage stage;
        private final String stageIncompleteReason;

        private Preparation(BoundedRootDemandCatalogGenerator.GeneratedCatalog generatedCatalog,
                PreparedRoutingFeatureWindow featureWindow,
                CatalogEdgeSectionAssemblerFactory.PreparedAssembler sectionAssembler,
                AdaptiveCatalogNetworkSearch.Stage stage,
                String stageIncompleteReason) {
            this.generatedCatalog = generatedCatalog;
            this.featureWindow = featureWindow;
            this.sectionAssembler = sectionAssembler;
            this.stage = stage;
            this.stageIncompleteReason = stageIncompleteReason;
        }

        public BoundedRootDemandCatalogGenerator.GeneratedCatalog getGeneratedCatalog() {
            return generatedCatalog;
        }
        public PreparedRoutingFeatureWindow getFeatureWindow() { return featureWindow; }
        public CatalogEdgeSectionAssemblerFactory.PreparedAssembler getSectionAssembler() {
            return sectionAssembler;
        }
        public Optional<AdaptiveCatalogNetworkSearch.Stage> getStage() {
            return Optional.ofNullable(stage);
        }
        public Optional<String> getStageIncompleteReason() {
            return Optional.ofNullable(stageIncompleteReason);
        }
    }
}
