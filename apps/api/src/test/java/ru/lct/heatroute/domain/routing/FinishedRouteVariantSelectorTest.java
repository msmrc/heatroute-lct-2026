package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.depth.DepthProfileIssue;
import ru.lct.heatroute.domain.depth.DepthProfilePoint;
import ru.lct.heatroute.domain.depth.DepthProfileResult;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.sizing.NetworkSizingIssue;

class FinishedRouteVariantSelectorTest {
    private final FinishedRouteVariantSelector selector = new FinishedRouteVariantSelector();
    private final EngineeringRouteEvaluator evaluator = new EngineeringRouteEvaluator();

    @Test
    void completeCompliantNetworkSuppliesEngineeringRolesBeforeAShorterIrregularControl() {
        RouteVariant balanced = variant("balanced", "engineering", 100, 100, 2, false);
        RouteVariant shortest = variant("shortest", "shortest", 90, 90, 2, false);
        RouteVariant compliant = variant("portfolio-clean", "engineering", 110, 120, 2, true);

        List<RouteVariant> selected = selector.select(List.of(balanced, shortest, compliant));

        assertRoles(selected);
        assertSource(role(selected, "balanced"), compliant);
        assertSource(role(selected, "shortest"), compliant);
        // Экономическая роль остаётся отдельной: экспертное предупреждение не меняет смету.
        assertSource(role(selected, "cheapest"), shortest);
    }

    @Test
    void finalLengthAndCostCanInvertTheOriginalRoleNamesWithoutReplacingBalancedControl() {
        RouteVariant balanced = variant("balanced", "engineering", 90, 300, 2, true);
        RouteVariant shortest = variant("shortest", "shortest", 120, 100, 2, true);
        RouteVariant cheapest = variant("cheapest", "cheapest", 100, 200, 2, true);

        List<RouteVariant> selected = selector.select(List.of(balanced, shortest, cheapest));

        assertRoles(selected);
        assertSource(role(selected, "balanced"), balanced);
        assertSource(role(selected, "shortest"), balanced);
        assertSource(role(selected, "cheapest"), shortest);
    }

    @Test
    void finishedPortfolioCandidatesCanWinButNeverLeakAuxiliaryIdentity() {
        RouteVariant balanced = variant("balanced", "engineering", 150, 300, 2, true);
        RouteVariant shortest = variant("shortest", "shortest", 130, 200, 2, true);
        RouteVariant portfolio = variant("portfolio-0", "engineering", 100, 100, 2, true);

        List<RouteVariant> selected = selector.select(List.of(portfolio, shortest, balanced));

        assertRoles(selected);
        assertSource(role(selected, "balanced"), balanced);
        assertSource(role(selected, "shortest"), portfolio);
        assertSource(role(selected, "cheapest"), portfolio);
    }

    @Test
    void invalidGeometrySizingFailuresAndLesserCoverageCannotWin() {
        RouteVariant balanced = variant("balanced", "engineering", 120, 300, 2, true);
        RouteVariant lowerCoverage = variant("shortest", "shortest", 10, 1, 1, true);
        RouteVariant geometryFailure = withFailures(variant("invalid-geometry", "engineering", 6, 1, 3, true),
                List.of(new RouteValidationIssue("INTERSECTION", "edge", "Blocked")), List.of());
        RouteVariant sizingFailure = withFailures(variant("invalid-sizing", "engineering", 6, 1, 3, true),
                List.of(), List.of(new NetworkSizingIssue("NO_DIAMETER", "edge", "No allowed diameter")));

        assertThat(sizingFailure.isValid()).isFalse();
        List<RouteVariant> selected = selector.select(List.of(geometryFailure, lowerCoverage, balanced, sizingFailure));

        assertRoles(selected);
        selected.forEach(result -> {
            assertSource(result, balanced);
            assertThat(result.isValid()).isTrue();
            assertThat(result.getConnectedDemandCount()).isEqualTo(2);
        });
    }

    @Test
    void invalidBalancedFallsBackToACompliantEngineeringCandidate() {
        RouteVariant balanced = withFailures(variant("balanced", "engineering", 50, 100, 2, true),
                List.of(new RouteValidationIssue("INTERSECTION", "edge", "Blocked")), List.of());
        RouteVariant irregular = variant("a-irregular", "engineering", 40, 200, 2, false);
        RouteVariant engineering = variant("portfolio-1", "engineering", 100, 300, 2, true);
        RouteVariant rawCheapest = variant("cheapest", "cheapest", 60, 50, 2, true);

        List<RouteVariant> selected = selector.select(List.of(balanced, irregular, rawCheapest, engineering));

        assertSource(role(selected, "balanced"), engineering);
        assertSource(role(selected, "shortest"), rawCheapest);
        assertSource(role(selected, "cheapest"), rawCheapest);
    }

    @Test
    void lowerCoverageBalancedDoesNotFreezeAnInferiorCoverageBaseline() {
        RouteVariant balanced = variant("balanced", "engineering", 40, 100, 1, true);
        RouteVariant portfolio = variant("portfolio-0", "engineering", 100, 300, 2, true);

        List<RouteVariant> selected = selector.select(List.of(balanced, portfolio));

        assertRoles(selected);
        selected.forEach(result -> assertSource(result, portfolio));
    }

    @Test
    void rawCheapestMustPassActualEngineeringEvaluationBeforeBecomingShortest() {
        RouteVariant shortest = variant("shortest", "shortest", 140, 300, 2, true);
        RouteVariant rawCheapest = variant("cheapest", "cheapest", 80, 50, 2, false);
        RouteVariant assessedButIrregular = variant("portfolio-0", "engineering", 70, 200, 2, false);
        assertThat(rawCheapest.getEngineeringIssues()).isEmpty();
        assertThat(evaluator.evaluate(rawCheapest.getEdges()).isCompliant()).isFalse();

        List<RouteVariant> selected = selector.select(List.of(shortest, rawCheapest, assessedButIrregular));

        assertSource(role(selected, "shortest"), shortest);
        assertSource(role(selected, "cheapest"), rawCheapest);
        assertThat(evaluator.evaluate(role(selected, "balanced").getEdges()).isCompliant()).isTrue();
    }

    @Test
    void compliantRawCheapestCanSupplyAllRolesWhenNoNamedEngineeringBaselineExists() {
        RouteVariant cheapest = variant("cheapest", "cheapest", 80, 100, 2, true);

        List<RouteVariant> selected = selector.select(List.of(cheapest));

        assertRoles(selected);
        selected.forEach(result -> assertSource(result, cheapest));
    }

    @Test
    void noncompliantRawCheapestAloneCannotBeRelabeledAsEngineering() {
        RouteVariant cheapest = variant("cheapest", "cheapest", 80, 100, 2, false);

        List<RouteVariant> selected = selector.select(List.of(cheapest));

        assertThat(selected).extracting(RouteVariant::getId).containsExactly("cheapest");
        assertSource(selected.get(0), cheapest);
    }

    @Test
    void shortestCannotTradeLengthForMoreAngleViolations() {
        RouteVariant shortest = variant("shortest", "shortest", 100, 300, 1, false);
        List<RouteCoordinate> coordinates = List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 0),
                new RouteCoordinate(5, 5), new RouteCoordinate(15, 5));
        RouteVariant moreSharpBends = withSingleGeometry(
                variant("portfolio-0", "engineering", 27, 200, 1, true), coordinates);
        assertThat(evaluator.evaluate(shortest.getEdges()).invalidAngleCount()).isEqualTo(1);
        assertThat(evaluator.evaluate(moreSharpBends.getEdges()).invalidAngleCount()).isEqualTo(2);
        assertThat(evaluator.evaluate(moreSharpBends.getEdges()).insufficientSpacingCount()).isZero();
        assertThat(moreSharpBends.getTotalLengthM()).isLessThan(shortest.getTotalLengthM());

        List<RouteVariant> selected = selector.select(List.of(shortest, moreSharpBends));

        assertSource(role(selected, "shortest"), shortest);
    }

    @Test
    void cheapestUsesOfficialCalculatedCostIncludingPenaltyNotConstructionSubtotal() {
        RouteVariant lowSubtotal = variant("balanced", "engineering", 100, 10, 2, true);
        lowSubtotal = withEconomics(lowSubtotal, economics(true, 10, 1000, lowSubtotal.getTotalLengthM()));
        RouteVariant lowCalculatedCost = variant("cheapest", "cheapest", 120, 100, 2, true);

        List<RouteVariant> selected = selector.select(List.of(lowSubtotal, lowCalculatedCost));

        assertSource(role(selected, "balanced"), lowSubtotal);
        assertSource(role(selected, "cheapest"), lowCalculatedCost);
    }

    @Test
    void incompleteOrMissingEconomicsCannotWinCostComparisonButCanWinOnLength() {
        RouteVariant balanced = variant("balanced", "engineering", 100, 300, 2, true);
        RouteVariant incomplete = variant("portfolio-0", "engineering", 60, 1, 2, true);
        incomplete = withEconomics(incomplete, economics(false, 1, 1, incomplete.getTotalLengthM()));
        RouteVariant missing = withEconomics(variant("portfolio-1", "engineering", 70, 1, 2, true), null);
        RouteVariant missingCalculated = variant("portfolio-2", "engineering", 80, 1, 2, true);
        missingCalculated = withEconomics(missingCalculated,
                economics(true, 1, null, missingCalculated.getTotalLengthM()));

        List<RouteVariant> selected = selector.select(List.of(incomplete, missing, balanced, missingCalculated));

        assertSource(role(selected, "shortest"), incomplete);
        assertSource(role(selected, "cheapest"), balanced);
    }

    @Test
    void missingCostAtMaximumCoverageDoesNotPromoteACostedLowerCoverageVariant() {
        RouteVariant balanced = withEconomics(variant("balanced", "engineering", 120, 100, 2, true), null);
        RouteVariant costed = variant("cheapest", "cheapest", 60, 1, 1, true);

        List<RouteVariant> selected = selector.select(List.of(costed, balanced));

        assertThat(selected).extracting(RouteVariant::getId).containsExactly("balanced", "shortest");
        selected.forEach(result -> assertSource(result, balanced));
    }

    @Test
    void shortestLengthTiesPreferCompleteCalculatedCostThenDeterministicId() {
        RouteVariant balanced = variant("balanced", "engineering", 100, 300, 2, true);
        RouteVariant lowSubtotal = variant("portfolio-0", "engineering", 100, 1, 2, true);
        lowSubtotal = withEconomics(lowSubtotal, economics(true, 1, 500, lowSubtotal.getTotalLengthM()));
        RouteVariant winner = variant("portfolio-1", "engineering", 100, 100, 2, true);
        RouteVariant sameCostLaterId = variant("portfolio-2", "engineering", 100, 100, 2, true);
        RouteVariant unknown = withEconomics(variant("a-unknown", "engineering", 100, 1, 2, true), null);
        List<RouteVariant> candidates = new ArrayList<>(List.of(balanced, lowSubtotal, winner, sameCostLaterId, unknown));

        for (int i = 0; i < candidates.size(); i++) {
            List<RouteVariant> selected = selector.select(candidates);
            assertSource(role(selected, "balanced"), balanced);
            assertSource(role(selected, "shortest"), winner);
            assertSource(role(selected, "cheapest"), winner);
            Collections.rotate(candidates, 1);
        }
        Collections.reverse(candidates);
        assertSource(role(selector.select(candidates), "shortest"), winner);
    }

    @Test
    void identicalGeometryCanFillAllRolesWhilePreservingFullPayloadAndClearingRank() {
        RouteVariant balanced = variant("balanced", "engineering", 100, 300, 2, true)
                .withEngineeringIssues(List.of(new RouteValidationIssue("ENGINEERING_WARNING", "edge", "Retained")))
                .withRank(7);

        List<RouteVariant> selected = selector.select(List.of(balanced));

        assertRoles(selected);
        selected.forEach(result -> {
            assertSource(result, balanced);
            assertThat(result).usingRecursiveComparison().ignoringFields("id", "strategy", "rank").isEqualTo(balanced);
            assertThat(result.getEdges().get(0).getDepthProfile()).isSameAs(balanced.getEdges().get(0).getDepthProfile());
            assertThat(result.getEconomics()).isSameAs(balanced.getEconomics());
            assertThat(result.getReconstruction()).isSameAs(balanced.getReconstruction());
        });
        assertThat(balanced.getRank()).isEqualTo(7);
        assertThat(selected.get(0)).isNotSameAs(selected.get(1)).isNotSameAs(balanced);
    }

    @Test
    void emptyOrEntirelyInvalidInputsYieldNoInventedRoles() {
        assertThat(selector.select(List.of())).isEmpty();
        RouteVariant invalid = withFailures(variant("balanced", "engineering", 100, 100, 2, true),
                List.of(), List.of(new NetworkSizingIssue("NO_DIAMETER", "edge", "No allowed diameter")));
        assertThat(selector.select(List.of(invalid))).isEmpty();
    }

    @Test
    void enabledDepthRejectsMissingIncompleteOrErroneousProfilesBeforeCoverageSelection() {
        RouteVariant detour = variant("cheapest", "cheapest", 150, 400, 2, true);
        RouteVariant missing = withLastDepthProfile(variant("balanced", "engineering", 90, 100, 3, true), null);
        RouteVariant incomplete = withLastDepthProfile(variant("shortest", "shortest", 80, 100, 3, true),
                depthProfile(false, List.of()));
        RouteVariant noPassage = withLastDepthProfile(variant("portfolio-0", "engineering", 70, 100, 3, true),
                depthProfile(true, List.of(new DepthProfileIssue("NO_VERTICAL_PASSAGE", "crossing", "No passage"))));
        assertThat(missing.isValid()).isTrue();
        assertThat(incomplete.isValid()).isTrue();
        assertThat(noPassage.isValid()).isTrue();

        List<RouteVariant> selected = selector.select(List.of(missing, incomplete, noPassage, detour), true);

        assertRoles(selected);
        selected.forEach(result -> assertSource(result, detour));
    }

    @Test
    void disabledDepthPreservesEligibilityForAllKindsOfAbsentOrUnusableProfiles() {
        RouteVariant source = variant("balanced", "engineering", 100, 300, 2, true);
        List<RouteVariant> candidates = List.of(withLastDepthProfile(source, null),
                withLastDepthProfile(source, depthProfile(false, List.of())),
                withLastDepthProfile(source, depthProfile(true,
                        List.of(new DepthProfileIssue("NO_VERTICAL_PASSAGE", "crossing", "No passage")))));

        for (RouteVariant candidate : candidates) {
            List<RouteVariant> explicit = selector.select(List.of(candidate), false);
            List<RouteVariant> defaultSelection = selector.select(List.of(candidate));
            assertRoles(explicit);
            assertRoles(defaultSelection);
            explicit.forEach(result -> assertSource(result, candidate));
            defaultSelection.forEach(result -> assertSource(result, candidate));
        }
    }

    @Test
    void enabledDepthAcceptsCompleteProfilesButDoesNotPublishAnyRoleIfNoProfilePasses() {
        RouteVariant complete = variant("balanced", "engineering", 100, 300, 2, true);
        assertRoles(selector.select(List.of(complete), true));
        RouteVariant missing = withLastDepthProfile(complete, null);
        RouteVariant incomplete = withLastDepthProfile(variant("shortest", "shortest", 80, 100, 2, true),
                depthProfile(false, List.of()));
        assertThat(selector.select(List.of(missing, incomplete), true)).isEmpty();
    }

    @Test
    void ambiguousOrBlankSourceIdentityIsRejected() {
        RouteVariant balanced = variant("balanced", "engineering", 100, 100, 2, true);
        assertThatThrownBy(() -> selector.select(List.of(balanced, balanced)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique nonblank IDs");
        assertThatThrownBy(() -> selector.select(List.of(variant(" ", "engineering", 100, 100, 2, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balancedAcceptsElevenChambersAndTwoSideBranchesDespitePointThreePercentExtraLength() {
        RouteVariant baseline = twoBranchSpine("balanced", 12, 120);
        RouteVariant improved = twoBranchSpine("portfolio-0", 11, 120.6);
        OfficialRouteValidator validator = new OfficialRouteValidator(new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry()));
        for (RouteVariant variant : List.of(baseline, improved)) {
            assertThat(validator.validate(variant.getNodes(), variant.getEdges(), List.of())).isEmpty();
            assertThat(evaluator.evaluate(variant.getEdges()).isCompliant()).isTrue();
            assertThat(variant.getConnectedDemandCount()).isEqualTo(3);
            assertThat(variant.getEdges().get(0).getFlowTph()).isEqualByComparingTo("3");
            assertThat(variant.getEdges()).filteredOn(edge -> edge.getId().contains("branch"))
                    .hasSize(2).allSatisfy(edge -> assertThat(edge.getFlowTph()).isEqualByComparingTo("1"));
        }
        assertThat(newChambers(baseline)).isEqualTo(12);
        assertThat(newChambers(improved)).isEqualTo(11);
        assertThat(improved.getTotalLengthM()).isEqualByComparingTo("200.600");
        assertThat(baseline.getTotalLengthM()).isEqualByComparingTo("200");
        assertThat(improved.getEconomics().getCalculatedCost()).isLessThan(baseline.getEconomics().getCalculatedCost());
        assertThat(improved.getEconomics().getScore()).isLessThan(baseline.getEconomics().getScore());

        RouteVariant selected = role(selector.select(List.of(baseline, improved), true), "balanced");

        assertSource(selected, improved);
        assertThat(selected).usingRecursiveComparison().ignoringFields("id", "strategy", "rank").isEqualTo(improved);
    }

    @Test
    void balancedLengthCorridorIncludesExactly105PercentButNotOneMillimetreMore() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 100, 1, 1, true), 100_000_000);
        for (double lengthM : new double[] {105.0, 105.001}) {
            RouteVariant candidate = withOfficialCost(variant("portfolio-0", "engineering", lengthM, 1, 1, true), 50_000_000);
            RouteVariant selected = role(selector.select(List.of(baseline, candidate)), "balanced");
            assertSource(selected, lengthM == 105.0 ? candidate : baseline);
        }
    }

    @Test
    void balancedCorridorNeverAccumulatesAcrossSuccessiveImprovementsOrInputOrder() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 100, 1, 1, true), 100_000_000);
        RouteVariant allowed = withOfficialCost(variant("portfolio-0", "engineering", 104, 1, 1, true), 90_000_000);
        RouteVariant outsideOriginalCorridor = withOfficialCost(
                variant("portfolio-1", "engineering", 108, 1, 1, true), 80_000_000);
        List<RouteVariant> candidates = new ArrayList<>(List.of(baseline, allowed, outsideOriginalCorridor));
        for (int index = 0; index < candidates.size(); index++) {
            assertSource(role(selector.select(candidates), "balanced"), allowed);
            Collections.rotate(candidates, 1);
        }
        Collections.reverse(candidates);
        assertSource(role(selector.select(candidates), "balanced"), allowed);
    }

    @Test
    void balancedRequiresStrictScoreImprovementAndNeverAcceptsHigherCalculatedCost() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 100, 1, 1, true), 100_000_000);
        RouteVariant sameScore = withOfficialCost(variant("a-equal", "engineering", 100, 1, 1, true), 100_000_000);
        RouteVariant moreExpensive = withOfficialCost(variant("b-shorter", "engineering", 99, 1, 1, true), 100_000_001);
        assertThat(moreExpensive.getEconomics().getScore()).isLessThan(baseline.getEconomics().getScore());
        assertSource(role(selector.select(List.of(baseline, sameScore, moreExpensive)), "balanced"), baseline);
    }

    @Test
    void balancedChoosesLowestScoreThenCostThenIdDeterministically() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 110, 1, 1, true), 100_000_000);
        RouteVariant higherCost = withOfficialCost(variant("a-short", "engineering", 100, 1, 1, true), 90_000_000);
        RouteVariant lowerCost = withOfficialCost(variant("b-long", "engineering", 107, 1, 1, true), 89_250_000);
        RouteVariant sameLaterId = withOfficialCost(variant("c-long", "engineering", 107, 1, 1, true), 89_250_000);
        RouteVariant worseScore = withOfficialCost(variant("0-worse", "engineering", 108, 1, 1, true), 89_250_000);
        assertThat(higherCost.getEconomics().getScore()).isEqualByComparingTo(lowerCost.getEconomics().getScore());
        List<RouteVariant> inputs = new ArrayList<>(List.of(baseline, higherCost, sameLaterId, lowerCost, worseScore));
        for (int index = 0; index < inputs.size(); index++) {
            assertSource(role(selector.select(inputs), "balanced"), lowerCost);
            Collections.rotate(inputs, 1);
        }
    }

    @Test
    void balancedKeepsBaselineWhenEitherScoreIsMissingOrEconomicsIncomplete() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 100, 1, 1, true), 100_000_000);
        RouteVariant candidate = withOfficialCost(variant("portfolio-0", "engineering", 99, 1, 1, true), 50_000_000);
        for (boolean missingScore : new boolean[] {false, true}) {
            RouteVariant unknownBaseline = withUnknownEconomics(baseline, missingScore);
            assertSource(role(selector.select(List.of(unknownBaseline, candidate)), "balanced"), unknownBaseline);
            RouteVariant unknownCandidate = withUnknownEconomics(candidate, missingScore);
            assertSource(role(selector.select(List.of(baseline, unknownCandidate)), "balanced"), baseline);
        }
    }

    @Test
    void balancedRejectsEachEngineeringMetricRegressionIndependently() {
        // Метрики: invalid angles, spacing, bends, irregular junctions, angle deviation,
        // preferred bend deviation, junction deviation. Для каждого случая ухудшается только одна.
        List<RouteVariant[]> pairs = List.of(
                new RouteVariant[] {pathVariant("balanced", anglePath(30, 60, 90)),
                        pathVariant("candidate", anglePath(30, 80, 80))},
                new RouteVariant[] {pathVariant("balanced", staircase(2)), pathVariant("candidate", staircase(1))},
                new RouteVariant[] {pathVariant("balanced", List.of(new RouteCoordinate(0, 0), new RouteCoordinate(20, 0))),
                        pathVariant("candidate", List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 0),
                                new RouteCoordinate(10, 10)))},
                new RouteVariant[] {starVariant("balanced", 0, 90, 150), starVariant("candidate", 0, 95, 175)},
                new RouteVariant[] {pathVariant("balanced", anglePath(30, 89, 105)),
                        pathVariant("candidate", anglePath(30, 80, 90))},
                new RouteVariant[] {pathVariant("balanced", anglePath(30, 90, 90)),
                        pathVariant("candidate", anglePath(30, 105, 105))},
                new RouteVariant[] {starVariant("balanced", 0, 95, 175), starVariant("candidate", 0, 100, 170)});
        for (int metric = 0; metric < pairs.size(); metric++) {
            RouteVariant baseline = withOfficialCost(pairs.get(metric)[0], 100_000_000);
            RouteVariant candidate = withOfficialCost(pairs.get(metric)[1], 50_000_000);
            double[] before = engineeringMetrics(baseline);
            double[] after = engineeringMetrics(candidate);
            for (int index = 0; index < before.length; index++) {
                if (index == metric) {
                    assertThat(after[index]).as("regressed metric %s", index).isGreaterThan(before[index] + 1e-7);
                } else {
                    assertThat(after[index]).as("unchanged/improved metric %s in case %s", index, metric)
                            .isLessThanOrEqualTo(before[index] + 1e-7);
                }
            }
            assertThat(candidate.getEconomics().getScore()).isLessThan(baseline.getEconomics().getScore());
            assertThat(candidate.getTotalLengthM()).isLessThanOrEqualTo(baseline.getTotalLengthM().multiply(new BigDecimal("1.05")));
            if (metric == 3 || metric == 6) {
                // Обе старые звезды нарушают теперь обязательные нормали; метрики по-прежнему
                // сравниваем, но роль должен получить полноценный ортогональный контроль.
                RouteVariant strict = withOfficialCost(starVariant("strict-control", 0, 90, 180), 120_000_000);
                List<RouteVariant> selected = selector.select(List.of(baseline, candidate, strict));
                assertRoles(selected);
                selected.forEach(result -> assertSource(result, strict));
            } else {
                assertThat(role(selector.select(List.of(baseline, candidate)), "balanced").getEdges().get(0).getId())
                        .as("selected source in metric case %s", metric).isEqualTo(baseline.getEdges().get(0).getId());
                assertSource(role(selector.select(List.of(baseline, candidate)), "balanced"), baseline);
            }
        }
    }

    @Test
    void balancedCountsANewRootChamberEvenWhenAllGeometryMetricsAreUnchanged() {
        RouteVariant baseline = withOfficialCost(variant("balanced", "engineering", 100, 1, 1, true), 100_000_000);
        RouteVariant candidate = withNewRoot(withOfficialCost(variant("candidate", "engineering", 100, 1, 1, true), 50_000_000));
        assertThat(newChambers(baseline)).isZero();
        assertThat(newChambers(candidate)).isEqualTo(1);
        assertThat(engineeringMetrics(candidate)).containsExactly(engineeringMetrics(baseline));
        assertSource(role(selector.select(List.of(baseline, candidate)), "balanced"), baseline);
    }

    @Test
    void roundedAngleWithinExistingToleranceIsAcceptedButPointSixDegreeViolationIsNot() {
        RouteVariant baseline = withOfficialCost(pathVariant("balanced", anglePath(1000, 91)), 100_000_000);
        RouteVariant rounded = withOfficialCost(pathVariant("rounded", anglePath(1000, 89.998)), 50_000_000);
        RouteVariant violation = withOfficialCost(pathVariant("violation", anglePath(1000, 89.4)), 50_000_000);
        assertThat(evaluator.evaluate(rounded.getEdges()).invalidAngleCount()).isZero();
        assertThat(evaluator.evaluate(rounded.getEdges()).preferredAngleDeviation()).isBetween(0.0019, 0.0021);
        assertThat(evaluator.evaluate(violation.getEdges()).invalidAngleCount()).isEqualTo(1);
        assertSource(role(selector.select(List.of(baseline, rounded)), "balanced"), rounded);
        assertSource(role(selector.select(List.of(baseline, violation)), "balanced"), baseline);
    }

    @Test
    void preferredDeviationUsesOnlyNumericalEpsilonNotAnAdditionalEngineeringTolerance() {
        RouteVariant baseline = withOfficialCost(pathVariant("balanced", anglePath(10_000_000, 91)), 100_000_000);
        RouteVariant roundoff = withOfficialCost(pathVariant("roundoff", anglePath(10_000_000, 91.00000005)), 50_000_000);
        RouteVariant regression = withOfficialCost(pathVariant("regression", anglePath(10_000_000, 91.000001)), 50_000_000);
        double control = evaluator.evaluate(baseline.getEdges()).preferredAngleDeviation();
        assertThat(evaluator.evaluate(roundoff.getEdges()).preferredAngleDeviation() - control).isBetween(0.0, 1e-7);
        assertThat(evaluator.evaluate(regression.getEdges()).preferredAngleDeviation() - control).isGreaterThan(1e-7);
        assertSource(role(selector.select(List.of(baseline, roundoff)), "balanced"), roundoff);
        assertSource(role(selector.select(List.of(baseline, regression)), "balanced"), baseline);
    }

    private void assertRoles(List<RouteVariant> variants) {
        assertThat(variants).extracting(RouteVariant::getId).containsExactly("balanced", "shortest", "cheapest");
        assertThat(variants).extracting(RouteVariant::getStrategy).containsExactly("engineering", "shortest", "cheapest");
    }

    private RouteVariant role(List<RouteVariant> variants, String role) {
        return variants.stream().filter(variant -> role.equals(variant.getId())).findFirst().orElseThrow();
    }

    private void assertSource(RouteVariant actual, RouteVariant source) {
        assertThat(actual.getNodes()).containsExactlyElementsOf(source.getNodes());
        assertThat(actual.getEdges()).containsExactlyElementsOf(source.getEdges());
        assertThat(actual.getConnections()).containsExactlyElementsOf(source.getConnections());
        assertThat(actual.getTotalLengthM()).isEqualByComparingTo(source.getTotalLengthM());
        assertThat(actual.getRank()).isNull();
    }

    private RouteVariant variant(String id, String strategy, double lengthM, int cost, int coverage, boolean compliant) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        for (int index = 0; index < coverage; index++) {
            String root = id + ":root:" + index;
            String demand = "demand:" + index;
            double offsetM = 1000.0 * index;
            double segmentM = compliant ? lengthM / coverage : lengthM / coverage / (1 + Math.sqrt(1.01));
            List<RouteCoordinate> coordinates = compliant
                    ? List.of(new RouteCoordinate(offsetM, 0), new RouteCoordinate(offsetM + segmentM, 0))
                    : List.of(new RouteCoordinate(offsetM, 0), new RouteCoordinate(offsetM + segmentM, 0),
                            new RouteCoordinate(offsetM, segmentM * 0.1));
            nodes.add(new RouteNode(root, "existing_chamber_tie_in", coordinates.get(0), true, true, 2, "existing"));
            nodes.add(new RouteNode(demand, "demand_connection", coordinates.get(coordinates.size() - 1),
                    false, false, 0, demand));
            edges.add(edge(id + ":edge:" + index, root, demand, coordinates));
            connections.add(new RouteConnection(demand, demand, BigDecimal.ONE, "connected", null));
        }
        BigDecimal actualLengthM = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RouteVariant(id, strategy, nodes, edges, connections, actualLengthM, List.of(), List.of(), List.of(),
                ExistingNetworkReconstructionResult.empty(), economics(true, cost, cost, actualLengthM), null);
    }

    private RouteEdge edge(String id, String upstream, String downstream, List<RouteCoordinate> coordinates) {
        double lengthM = 0;
        for (int index = 1; index < coordinates.size(); index++) {
            lengthM += coordinates.get(index - 1).toCoordinate().distance(coordinates.get(index).toCoordinate());
        }
        BigDecimal length = BigDecimal.valueOf(lengthM);
        DepthProfileResult depth = new DepthProfileResult(true, List.of(
                new DepthProfilePoint(BigDecimal.ZERO, BigDecimal.ONE), new DepthProfilePoint(length, BigDecimal.ONE)),
                List.of(), List.of(), length, length);
        return new RouteEdge(id, upstream, downstream, lengthM, coordinates,
                List.of(new RouteSection("normal", null, null, coordinates, lengthM, null)), BigDecimal.ONE, 50, depth);
    }

    private VariantEconomics economics(boolean complete, int construction, Integer calculated, BigDecimal length) {
        BigDecimal cost = calculated == null ? null : BigDecimal.valueOf(calculated);
        return new VariantEconomics(complete, BigDecimal.valueOf(construction), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, cost == null ? null : cost.subtract(BigDecimal.valueOf(construction)),
                cost, length, BigDecimal.ZERO, length, BigDecimal.ZERO, complete ? List.of() : List.of("Missing depth"));
    }

    private RouteVariant withEconomics(RouteVariant source, VariantEconomics economics) {
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(),
                source.getConnections(), source.getTotalLengthM(), source.getValidationIssues(), source.getEngineeringIssues(),
                source.getSizingIssues(), source.getReconstruction(), economics, source.getRank());
    }

    private RouteVariant withFailures(RouteVariant source, List<RouteValidationIssue> validation, List<NetworkSizingIssue> sizing) {
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), source.getEdges(),
                source.getConnections(), source.getTotalLengthM(), validation, source.getEngineeringIssues(), sizing,
                source.getReconstruction(), source.getEconomics(), source.getRank());
    }

    private RouteVariant withSingleGeometry(RouteVariant source, List<RouteCoordinate> coordinates) {
        RouteEdge original = source.getEdges().get(0);
        RouteEdge replacement = edge(original.getId(), original.getUpstreamNodeId(), original.getDownstreamNodeId(), coordinates);
        List<RouteNode> nodes = List.of(
                new RouteNode(original.getUpstreamNodeId(), "existing_chamber_tie_in", coordinates.get(0), true, true, 2, "existing"),
                new RouteNode(original.getDownstreamNodeId(), "demand_connection", coordinates.get(coordinates.size() - 1),
                        false, false, 0, "demand:0"));
        return new RouteVariant(source.getId(), source.getStrategy(), nodes, List.of(replacement), source.getConnections(),
                replacement.getLengthM(), List.of(), List.of(), List.of(), source.getReconstruction(),
                economics(true, 200, 200, replacement.getLengthM()), null);
    }

    private DepthProfileResult depthProfile(boolean complete, List<DepthProfileIssue> issues) {
        return new DepthProfileResult(complete, List.of(), List.of(), issues, BigDecimal.TEN, BigDecimal.TEN);
    }

    private RouteVariant withLastDepthProfile(RouteVariant source, DepthProfileResult profile) {
        List<RouteEdge> edges = new ArrayList<>(source.getEdges());
        RouteEdge last = edges.get(edges.size() - 1);
        edges.set(edges.size() - 1, new RouteEdge(last.getId(), last.getUpstreamNodeId(), last.getDownstreamNodeId(),
                last.getLengthM().doubleValue(), last.getCoordinates(), last.getSections(), last.getFlowTph(),
                last.getDiameter(), profile));
        return new RouteVariant(source.getId(), source.getStrategy(), source.getNodes(), edges,
                source.getConnections(), source.getTotalLengthM(), source.getValidationIssues(), source.getEngineeringIssues(),
                source.getSizingIssues(), source.getReconstruction(), source.getEconomics(), source.getRank());
    }

    private RouteVariant withOfficialCost(RouteVariant source, int calculatedCost) {
        VariantEconomics cost = economics(true, calculatedCost, calculatedCost, source.getTotalLengthM());
        return withEconomics(source, copyEconomics(cost, true,
                new OfficialEconomics().score(cost.getCalculatedCost(), source.getTotalLengthM())));
    }

    private RouteVariant withUnknownEconomics(RouteVariant source, boolean missingScore) {
        return withEconomics(source, copyEconomics(source.getEconomics(), missingScore,
                missingScore ? null : source.getEconomics().getScore()));
    }

    private VariantEconomics copyEconomics(VariantEconomics source, boolean complete, BigDecimal score) {
        return new VariantEconomics(complete, source.getConstructionCost(), source.getChamberConstructionCost(),
                source.getTieInCost(), source.getReconstructionCost(), source.getChamberReconstructionCost(),
                source.getUnconnectedPenalty(), source.getCalculatedCost(), source.getNewNetworkLength(),
                source.getReconstructionLength(), source.getLength(), score, complete ? List.of() : List.of("Incomplete"));
    }

    private RouteVariant pathVariant(String id, List<RouteCoordinate> coordinates) {
        return withSingleGeometry(variant(id, "engineering", 100, 1, 1, true), coordinates);
    }

    private List<RouteCoordinate> staircase(double spacingM) {
        return List.of(new RouteCoordinate(0, 0), new RouteCoordinate(10, 0),
                new RouteCoordinate(10, spacingM), new RouteCoordinate(20, spacingM));
    }

    private List<RouteCoordinate> anglePath(double legLengthM, double... internalAnglesDegrees) {
        List<RouteCoordinate> coordinates = new ArrayList<>();
        coordinates.add(new RouteCoordinate(0, 0));
        double x = legLengthM;
        double y = 0;
        double direction = 0;
        coordinates.add(new RouteCoordinate(x, y));
        for (double angle : internalAnglesDegrees) {
            direction += Math.toRadians(180 - angle);
            x += legLengthM * Math.cos(direction);
            y += legLengthM * Math.sin(direction);
            coordinates.add(new RouteCoordinate(x, y));
        }
        return coordinates;
    }

    private double[] engineeringMetrics(RouteVariant variant) {
        EngineeringRouteEvaluator.Evaluation value = evaluator.evaluate(variant.getEdges());
        return new double[] {value.invalidAngleCount(), value.insufficientSpacingCount(), value.bendCount(),
                value.irregularJunctionAngleCount(), value.totalAngleDeviation(), value.preferredAngleDeviation(),
                value.totalJunctionAngleDeviation()};
    }

    private RouteVariant starVariant(String id, double... rayAnglesDegrees) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        RouteCoordinate origin = new RouteCoordinate(0, 0);
        nodes.add(new RouteNode("root", "existing_chamber_tie_in", origin, true, true, 1, "existing"));
        for (int index = 0; index < rayAnglesDegrees.length; index++) {
            double angle = Math.toRadians(rayAnglesDegrees[index]);
            RouteCoordinate endpoint = new RouteCoordinate(30 * Math.cos(angle), 30 * Math.sin(angle));
            String demand = "demand:" + index;
            nodes.add(new RouteNode(demand, "demand_connection", endpoint, false, false, 0, demand));
            edges.add(edge(id + ":" + index, "root", demand, List.of(origin, endpoint)));
            connections.add(new RouteConnection(demand, demand, BigDecimal.ONE, "connected", null));
        }
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RouteVariant(id, "engineering", nodes, edges, connections, length, List.of(), List.of(), List.of(),
                ExistingNetworkReconstructionResult.empty(), economics(true, 1, 1, length), null);
    }

    private long newChambers(RouteVariant variant) {
        return variant.getNodes().stream().filter(RouteNode::isChamber)
                .filter(node -> node.getNodeType().startsWith("new_")).count();
    }

    private RouteVariant withNewRoot(RouteVariant source) {
        List<RouteNode> nodes = new ArrayList<>(source.getNodes());
        RouteNode root = nodes.get(0);
        nodes.set(0, new RouteNode(root.getId(), "new_chamber_tie_in", root.getCoordinate(), true, true,
                root.getBaseIncidentSections(), root.getTargetId()));
        return new RouteVariant(source.getId(), source.getStrategy(), nodes, source.getEdges(), source.getConnections(),
                source.getTotalLengthM(), source.getValidationIssues(), source.getEngineeringIssues(), source.getSizingIssues(),
                source.getReconstruction(), source.getEconomics(), source.getRank());
    }

    private RouteVariant twoBranchSpine(String id, int chamberCount, double terminalXM) {
        List<RouteNode> nodes = new ArrayList<>();
        List<RouteEdge> edges = new ArrayList<>();
        List<RouteConnection> connections = new ArrayList<>();
        RouteNode previous = new RouteNode("root", "new_chamber_tie_in", new RouteCoordinate(0, 0), true, true, 2, "pipe");
        nodes.add(previous);
        for (int index = 1; index < chamberCount; index++) {
            double x = index <= 10 ? index * 10.0 : 105;
            RouteNode chamber = new RouteNode("chamber:" + index, "new_chamber", new RouteCoordinate(x, 0), true, false, 0, null);
            nodes.add(chamber);
            edges.add(spineEdge(id + ":spine:" + index, previous, chamber, index <= 3 ? 3 : index <= 7 ? 2 : 1));
            previous = chamber;
        }
        RouteNode terminal = new RouteNode("demand:main", "demand_connection", new RouteCoordinate(terminalXM, 0),
                false, false, 0, "main");
        nodes.add(terminal);
        edges.add(spineEdge(id + ":terminal", previous, terminal, 1));
        connections.add(new RouteConnection("main", terminal.getId(), BigDecimal.ONE, "connected", null));
        for (int branch : new int[] {3, 7}) {
            RouteNode chamber = nodes.get(branch);
            String demand = "demand:branch:" + branch;
            RouteNode leaf = new RouteNode(demand, "demand_connection", new RouteCoordinate(branch * 10, branch == 3 ? 40 : -40),
                    false, false, 0, demand);
            nodes.add(leaf);
            edges.add(spineEdge(id + ":branch:" + branch, chamber, leaf, 1));
            connections.add(new RouteConnection(demand, demand, BigDecimal.ONE, "connected", null));
        }
        BigDecimal length = edges.stream().map(RouteEdge::getLengthM).reduce(BigDecimal.ZERO, BigDecimal::add);
        ExistingNetworkReconstructionResult reconstruction = ExistingNetworkReconstructionResult.empty();
        VariantEconomics economics = new OfficialVariantEconomicsCalculator(new OfficialPipeCatalog(), new OfficialEconomics())
                .calculate(nodes, edges, connections, reconstruction, false);
        return new RouteVariant(id, "engineering", nodes, edges, connections, length, List.of(), List.of(), List.of(),
                reconstruction, economics, null);
    }

    private RouteEdge spineEdge(String id, RouteNode upstream, RouteNode downstream, int flowTph) {
        RouteEdge base = edge(id, upstream.getId(), downstream.getId(), List.of(upstream.getCoordinate(), downstream.getCoordinate()));
        return new RouteEdge(id, upstream.getId(), downstream.getId(), base.getLengthM().doubleValue(), base.getCoordinates(),
                base.getSections(), BigDecimal.valueOf(flowTph), base.getDiameter(), base.getDepthProfile());
    }
}
