package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

/**
 * Deterministic layered search for the optional vertical profile.
 *
 * <p>Each utility crossing has a four-metre constant-depth plateau. Candidate depths are searched
 * at the official 0.5 m step, while the ordinary 3.0 m depth is always retained as an explicit
 * candidate. Transitions are straight slopes limited to 0.10 m/m. The dynamic programme chooses
 * the cheapest non-overlapping set of passage decisions for one sized route edge.</p>
 */
@Component
public class OfficialDepthOptimizer {
    public static final BigDecimal NORMAL_DEPTH_M = new BigDecimal("3.0");
    public static final BigDecimal OFFICIAL_MINIMUM_DEPTH_M = new BigDecimal("0.7");
    public static final BigDecimal DEPTH_STEP_M = new BigDecimal("0.5");
    public static final BigDecimal MAXIMUM_SLOPE = new BigDecimal("0.10");
    public static final BigDecimal CROSSING_HALF_LENGTH_M = new BigDecimal("2.0");
    public static final BigDecimal DEFAULT_MAXIMUM_DEPTH_M = new BigDecimal("10.0");
    private static final double EPSILON = 1e-7;

    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialEconomics economics;

    public OfficialDepthOptimizer(OfficialPipeCatalog pipeCatalog, OfficialEconomics economics) {
        this.pipeCatalog = pipeCatalog;
        this.economics = economics;
    }

    public DepthProfileResult optimize(
            BigDecimal edgeLengthM,
            int diameter,
            List<DepthCrossing> crossings,
            BigDecimal maximumDepthM) {
        return optimize(edgeLengthM, diameter, crossings, OFFICIAL_MINIMUM_DEPTH_M, maximumDepthM);
    }

    public DepthProfileResult optimize(
            BigDecimal edgeLengthM,
            int diameter,
            List<DepthCrossing> crossings,
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM) {
        requirePositive(edgeLengthM, "edge_length_m");
        requireDepthRange(minimumDepthM, maximumDepthM);
        PipeCatalogEntry pipe = pipeCatalog.byDiameter(diameter)
                .orElseThrow(() -> new IllegalArgumentException("diameter must be an official DU"));
        List<DepthCrossing> ordered = crossings == null ? List.of() : crossings.stream()
                .sorted(Comparator.comparing(DepthCrossing::getStationM)
                        .thenComparing(DepthCrossing::getType)
                        .thenComparing(DepthCrossing::getId))
                .collect(Collectors.toList());
        if (ordered.isEmpty()) return normal(edgeLengthM, List.of());

        List<List<Candidate>> options = new ArrayList<>();
        List<DepthProfileIssue> issues = new ArrayList<>();
        for (DepthCrossing crossing : ordered) {
            if (crossing.getStationM().compareTo(edgeLengthM) > 0) {
                issues.add(issue("CROSSING_OUTSIDE_EDGE", crossing,
                        "Crossing station lies outside the route edge"));
                continue;
            }
            List<Candidate> candidates = candidates(
                    edgeLengthM, pipe, crossing, minimumDepthM, maximumDepthM);
            if (candidates.isEmpty()) {
                issues.add(issue("NO_VERTICAL_PASSAGE", crossing,
                        "No above/below profile fits the depth range and available slope length"));
            } else {
                options.add(candidates);
            }
        }
        if (!issues.isEmpty()) return normal(edgeLengthM, issues);

        List<State> states = options.get(0).stream()
                .map(candidate -> new State(candidate, null, candidate.weightedMeters))
                .collect(Collectors.toList());
        for (int index = 1; index < options.size(); index++) {
            List<State> next = new ArrayList<>();
            for (Candidate candidate : options.get(index)) {
                State best = states.stream()
                        .filter(previous -> transitionsAreCompatible(previous.candidate, candidate))
                        .map(previous -> new State(
                                candidate,
                                previous,
                                previous.totalWeight.add(candidate.weightedMeters)))
                        .min(this::compareStates)
                        .orElse(null);
                if (best != null) next.add(best);
            }
            if (next.isEmpty()) {
                DepthCrossing crossing = ordered.get(index);
                return normal(edgeLengthM, List.of(issue(
                        "VERTICAL_TRANSITIONS_OVERLAP",
                        crossing,
                        "Required slopes overlap another utility crossing")));
            }
            states = next;
        }

        State best = states.stream().min(this::compareStates).orElseThrow();
        List<Candidate> selected = new ArrayList<>();
        for (State cursor = best; cursor != null; cursor = cursor.previous) selected.add(cursor.candidate);
        java.util.Collections.reverse(selected);

        List<CandidateGroup> groups = candidateGroups(selected);
        List<DepthProfilePoint> points = profilePoints(edgeLengthM, groups);
        BigDecimal profileLength = edgeLengthM;
        BigDecimal weighted = edgeLengthM;
        for (CandidateGroup group : groups) {
            BigDecimal horizontal = group.rampEnd.subtract(group.rampStart);
            BigDecimal leftRun = group.plateauStart.subtract(group.rampStart);
            BigDecimal rightRun = group.rampEnd.subtract(group.plateauEnd);
            BigDecimal leftLength = BigDecimal.valueOf(Math.hypot(
                    leftRun.doubleValue(), group.depth.subtract(group.startDepth).abs().doubleValue()));
            BigDecimal rightLength = BigDecimal.valueOf(Math.hypot(
                    rightRun.doubleValue(), group.endDepth.subtract(group.depth).abs().doubleValue()));
            BigDecimal constantLength = group.plateauEnd.subtract(group.plateauStart);
            BigDecimal targetMultiplier = economics.depthMultiplier(group.depth);
            BigDecimal leftAverageMultiplier = economics.depthMultiplier(group.startDepth).add(targetMultiplier)
                    .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            BigDecimal rightAverageMultiplier = targetMultiplier.add(economics.depthMultiplier(group.endDepth))
                    .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            BigDecimal groupProfileLength = leftLength.add(constantLength).add(rightLength);
            BigDecimal groupWeighted = leftLength.multiply(leftAverageMultiplier)
                    .add(constantLength.multiply(targetMultiplier));
            groupWeighted = groupWeighted.add(rightLength.multiply(rightAverageMultiplier));
            groupWeighted = groupWeighted.add(group.specialWeightedIncrement(targetMultiplier));
            profileLength = profileLength.subtract(horizontal).add(groupProfileLength);
            weighted = weighted.subtract(horizontal).add(groupWeighted);
        }
        List<DepthCrossingDecision> decisions = groups.stream()
                .flatMap(group -> group.decisions().stream())
                .collect(Collectors.toList());
        return new DepthProfileResult(true, points, decisions, List.of(), profileLength, weighted);
    }

    private boolean transitionsAreCompatible(Candidate previous, Candidate current) {
        if (previous.rampEnd.doubleValue() <= current.rampStart.doubleValue() + EPSILON) {
            return true;
        }
        // Adjacent crossings at the same chosen depth share one continuous plateau. Returning to
        // 3.0 m between them would add two artificial slopes and can make an otherwise feasible
        // longitudinal profile look impossible.
        return previous.decision.getDepthM().compareTo(current.decision.getDepthM()) == 0
                && previous.decision.getPlateauStartM().compareTo(current.decision.getPlateauStartM()) <= 0;
    }

    private List<Candidate> candidates(
            BigDecimal edgeLength,
            PipeCatalogEntry pipe,
            DepthCrossing crossing,
            BigDecimal minimumDepth,
            BigDecimal maximumDepth) {
        List<Candidate> result = new ArrayList<>();
        for (BigDecimal depth : depthLevels(minimumDepth, maximumDepth)) {
            BigDecimal aboveClearance = crossing.getExistingTopDepthM()
                    .subtract(depth.add(pipe.getEnvelopeHeightM()));
            if (aboveClearance.add(BigDecimal.valueOf(EPSILON))
                    .compareTo(crossing.getMinimumVerticalClearanceM()) >= 0) {
                candidate(edgeLength, crossing, depth, "above", aboveClearance).ifPresent(result::add);
            }
            BigDecimal belowClearance = depth.subtract(
                    crossing.getExistingTopDepthM().add(crossing.getExistingHeightM()));
            if (belowClearance.add(BigDecimal.valueOf(EPSILON))
                    .compareTo(crossing.getMinimumVerticalClearanceM()) >= 0) {
                candidate(edgeLength, crossing, depth, "below", belowClearance).ifPresent(result::add);
            }
        }
        result.sort(this::compareCandidates);
        return result;
    }

    private java.util.Optional<Candidate> candidate(
            BigDecimal edgeLength,
            DepthCrossing crossing,
            BigDecimal depth,
            String passage,
            BigDecimal actualClearance) {
        BigDecimal delta = depth.subtract(NORMAL_DEPTH_M).abs();
        BigDecimal ramp = delta.divide(MAXIMUM_SLOPE, 9, RoundingMode.HALF_UP);
        BigDecimal plateauStart = crossing.getStationM().subtract(CROSSING_HALF_LENGTH_M);
        BigDecimal plateauEnd = crossing.getStationM().add(CROSSING_HALF_LENGTH_M);
        if (plateauStart.signum() < 0 || plateauEnd.compareTo(edgeLength) > 0) {
            return java.util.Optional.empty();
        }
        BigDecimal rampStart = plateauStart.subtract(ramp).max(BigDecimal.ZERO);
        BigDecimal rampEnd = plateauEnd.add(ramp).min(edgeLength);
        BigDecimal startDepth = plateauStart.compareTo(ramp) < 0 ? depth : NORMAL_DEPTH_M;
        BigDecimal endDepth = edgeLength.subtract(plateauEnd).compareTo(ramp) < 0
                ? depth
                : NORMAL_DEPTH_M;

        BigDecimal leftRun = plateauStart.subtract(rampStart);
        BigDecimal rightRun = rampEnd.subtract(plateauEnd);
        BigDecimal leftLength = BigDecimal.valueOf(Math.hypot(
                leftRun.doubleValue(), depth.subtract(startDepth).abs().doubleValue()));
        BigDecimal rightLength = BigDecimal.valueOf(Math.hypot(
                rightRun.doubleValue(), endDepth.subtract(depth).abs().doubleValue()));
        BigDecimal targetMultiplier = economics.depthMultiplier(depth);
        BigDecimal leftAverageMultiplier = economics.depthMultiplier(startDepth).add(targetMultiplier)
                .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
        BigDecimal rightAverageMultiplier = targetMultiplier.add(economics.depthMultiplier(endDepth))
                .divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
        BigDecimal plateauLength = CROSSING_HALF_LENGTH_M.multiply(new BigDecimal("2"));
        BigDecimal weighted = leftLength.multiply(leftAverageMultiplier)
                .add(plateauLength.multiply(targetMultiplier).multiply(crossing.getSpecialCostMultiplier()));
        weighted = weighted.add(rightLength.multiply(rightAverageMultiplier));
        DepthCrossingDecision decision = new DepthCrossingDecision(
                crossing.getId(),
                crossing.getType(),
                passage,
                depth,
                rampStart,
                plateauStart,
                plateauEnd,
                rampEnd,
                actualClearance,
                crossing.getMinimumVerticalClearanceM());
        return java.util.Optional.of(new Candidate(
                decision,
                rampStart,
                rampEnd,
                leftRun.add(rightRun),
                weighted,
                crossing.getSpecialCostMultiplier(),
                startDepth,
                endDepth));
    }

    private List<BigDecimal> depthLevels(BigDecimal minimumDepth, BigDecimal maximumDepth) {
        Map<BigDecimal, BigDecimal> unique = new TreeMap<>();
        for (BigDecimal value = minimumDepth;
                value.compareTo(maximumDepth) <= 0;
                value = value.add(DEPTH_STEP_M)) {
            BigDecimal normalized = value.setScale(3, RoundingMode.HALF_UP);
            unique.put(normalized, normalized);
        }
        if (NORMAL_DEPTH_M.compareTo(minimumDepth) >= 0 && NORMAL_DEPTH_M.compareTo(maximumDepth) <= 0) {
            unique.put(NORMAL_DEPTH_M.setScale(3, RoundingMode.HALF_UP), NORMAL_DEPTH_M);
        }
        return new ArrayList<>(unique.values());
    }

    private List<CandidateGroup> candidateGroups(List<Candidate> selected) {
        List<CandidateGroup> groups = new ArrayList<>();
        for (Candidate candidate : selected) {
            CandidateGroup last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null
                    && last.depth.compareTo(candidate.decision.getDepthM()) == 0
                    && last.rampEnd.doubleValue() > candidate.rampStart.doubleValue() + EPSILON) {
                last.add(candidate);
            } else {
                groups.add(new CandidateGroup(candidate));
            }
        }
        return groups;
    }

    private List<DepthProfilePoint> profilePoints(BigDecimal edgeLength, List<CandidateGroup> groups) {
        Map<BigDecimal, BigDecimal> stations = new TreeMap<>();
        stations.put(BigDecimal.ZERO, NORMAL_DEPTH_M);
        stations.put(edgeLength, NORMAL_DEPTH_M);
        for (CandidateGroup group : groups) {
            stations.put(group.rampStart, group.startDepth);
            stations.put(group.plateauStart, group.depth);
            stations.put(group.plateauEnd, group.depth);
            stations.put(group.rampEnd, group.endDepth);
        }
        List<DepthProfilePoint> raw = stations.entrySet().stream()
                .map(entry -> new DepthProfilePoint(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        if (raw.size() <= 2) return raw;
        List<DepthProfilePoint> compact = new ArrayList<>();
        compact.add(raw.get(0));
        for (int index = 1; index < raw.size() - 1; index++) {
            BigDecimal previous = raw.get(index - 1).getDepthM();
            BigDecimal current = raw.get(index).getDepthM();
            BigDecimal next = raw.get(index + 1).getDepthM();
            if (previous.compareTo(current) == 0 && current.compareTo(next) == 0) continue;
            compact.add(raw.get(index));
        }
        compact.add(raw.get(raw.size() - 1));
        return compact;
    }

    private DepthProfileResult normal(BigDecimal edgeLength, List<DepthProfileIssue> issues) {
        return new DepthProfileResult(
                issues.isEmpty(),
                List.of(
                        new DepthProfilePoint(BigDecimal.ZERO, NORMAL_DEPTH_M),
                        new DepthProfilePoint(edgeLength, NORMAL_DEPTH_M)),
                List.of(),
                issues,
                edgeLength,
                edgeLength);
    }

    private int compareStates(State left, State right) {
        int cost = left.totalWeight.compareTo(right.totalWeight);
        return cost != 0 ? cost : compareCandidates(left.candidate, right.candidate);
    }

    private int compareCandidates(Candidate left, Candidate right) {
        int cost = left.weightedMeters.compareTo(right.weightedMeters);
        if (cost != 0) return cost;
        int ramp = left.rampHorizontal.compareTo(right.rampHorizontal);
        if (ramp != 0) return ramp;
        int passage = left.decision.getPassage().compareTo(right.decision.getPassage());
        if (passage != 0) return passage;
        return left.decision.getDepthM().compareTo(right.decision.getDepthM());
    }

    private DepthProfileIssue issue(String code, DepthCrossing crossing, String message) {
        return new DepthProfileIssue(code, crossing.getId(), message);
    }

    private void requireDepthRange(BigDecimal minimum, BigDecimal maximum) {
        requirePositive(minimum, "minimum_depth_m");
        requirePositive(maximum, "maximum_depth_m");
        if (minimum.compareTo(OFFICIAL_MINIMUM_DEPTH_M) < 0) {
            throw new IllegalArgumentException("minimum_depth_m must be at least 0.7");
        }
        if (maximum.compareTo(minimum) < 0 || NORMAL_DEPTH_M.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("maximum_depth_m must include the ordinary 3.0 m depth");
        }
    }

    private void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static final class Candidate {
        private final DepthCrossingDecision decision;
        private final BigDecimal rampStart;
        private final BigDecimal rampEnd;
        private final BigDecimal rampHorizontal;
        private final BigDecimal weightedMeters;
        private final BigDecimal specialCostMultiplier;
        private final BigDecimal startDepth;
        private final BigDecimal endDepth;

        private Candidate(
                DepthCrossingDecision decision,
                BigDecimal rampStart,
                BigDecimal rampEnd,
                BigDecimal rampHorizontal,
                BigDecimal weightedMeters,
                BigDecimal specialCostMultiplier,
                BigDecimal startDepth,
                BigDecimal endDepth) {
            this.decision = decision;
            this.rampStart = rampStart;
            this.rampEnd = rampEnd;
            this.rampHorizontal = rampHorizontal;
            this.weightedMeters = weightedMeters;
            this.specialCostMultiplier = specialCostMultiplier;
            this.startDepth = startDepth;
            this.endDepth = endDepth;
        }
    }

    private static final class State {
        private final Candidate candidate;
        private final State previous;
        private final BigDecimal totalWeight;

        private State(Candidate candidate, State previous, BigDecimal totalWeight) {
            this.candidate = candidate;
            this.previous = previous;
            this.totalWeight = totalWeight;
        }
    }

    private static final class CandidateGroup {
        private final List<Candidate> candidates = new ArrayList<>();
        private final BigDecimal depth;
        private final BigDecimal rampStart;
        private final BigDecimal plateauStart;
        private final BigDecimal startDepth;
        private BigDecimal plateauEnd;
        private BigDecimal rampEnd;
        private BigDecimal endDepth;

        private CandidateGroup(Candidate candidate) {
            candidates.add(candidate);
            depth = candidate.decision.getDepthM();
            rampStart = candidate.rampStart;
            plateauStart = candidate.decision.getPlateauStartM();
            startDepth = candidate.startDepth;
            plateauEnd = candidate.decision.getPlateauEndM();
            rampEnd = candidate.rampEnd;
            endDepth = candidate.endDepth;
        }

        private void add(Candidate candidate) {
            candidates.add(candidate);
            plateauEnd = candidate.decision.getPlateauEndM().max(plateauEnd);
            rampEnd = candidate.rampEnd.max(rampEnd);
            endDepth = candidate.endDepth;
        }

        private List<DepthCrossingDecision> decisions() {
            return candidates.stream().map(candidate -> new DepthCrossingDecision(
                    candidate.decision.getCrossingId(),
                    candidate.decision.getCrossingType(),
                    candidate.decision.getPassage(),
                    candidate.decision.getDepthM(),
                    rampStart,
                    candidate.decision.getPlateauStartM(),
                    candidate.decision.getPlateauEndM(),
                    rampEnd,
                    candidate.decision.getVerticalClearanceM(),
                    candidate.decision.getRequiredClearanceM())).collect(Collectors.toList());
        }

        private BigDecimal specialWeightedIncrement(BigDecimal targetMultiplier) {
            List<BigDecimal> cuts = candidates.stream()
                    .flatMap(candidate -> java.util.stream.Stream.of(
                            candidate.decision.getPlateauStartM(),
                            candidate.decision.getPlateauEndM()))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            BigDecimal result = BigDecimal.ZERO;
            for (int index = 0; index < cuts.size() - 1; index++) {
                BigDecimal start = cuts.get(index);
                BigDecimal end = cuts.get(index + 1);
                BigDecimal middle = start.add(end).divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
                BigDecimal multiplier = candidates.stream()
                        .filter(candidate -> middle.compareTo(candidate.decision.getPlateauStartM()) >= 0
                                && middle.compareTo(candidate.decision.getPlateauEndM()) <= 0)
                        .map(candidate -> candidate.specialCostMultiplier)
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ONE);
                result = result.add(end.subtract(start)
                        .multiply(targetMultiplier)
                        .multiply(multiplier.subtract(BigDecimal.ONE)));
            }
            return result;
        }
    }
}
