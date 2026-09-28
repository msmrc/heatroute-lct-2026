package ru.lct.heatroute.domain.routing;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.catalog.RoutingProblemSnapshot;
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
        try {
            AdaptiveCatalogNetworkSearch.Stage stage = stageCompiler.compilePrepared(
                    problem, generated.getBuildResult(), generated.getDemandPortById(),
                    generated.getRootPortById(), flowScaleDecimals, checkerVersion,
                    candidateIdPrefix, strategy,
                    compilation -> nodeResolver.resolve(problem, compilation,
                            generated.getDemandPortById(), generated.getRootPortById()),
                    window, sectionAssembler);
            return new Preparation(generated, window, sectionAssembler, stage, null);
        } catch (CandidateAssemblyIncompleteException exception) {
            return new Preparation(generated, window, sectionAssembler, null,
                    exception.getReason());
        }
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
