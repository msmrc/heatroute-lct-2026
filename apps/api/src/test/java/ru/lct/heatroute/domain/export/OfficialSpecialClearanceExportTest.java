package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.economics.VariantEconomics;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.SpecialCrossingType;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Повторный допуск сохранённого экспорта по отступам §§3.1/4 технического приложения (G2). */
class OfficialSpecialClearanceExportTest {
    private static final String RESTRICTION_ID = "corridor";
    private static final BigDecimal FLOW_TPH = new BigDecimal("20");
    private static final BigDecimal DEPTH_M = new BigDecimal("3");
    private static final double GEOMETRY_TOLERANCE_M = 0.001;

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator =
            new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialOutputContractValidator contract = new OfficialOutputContractValidator();
    private final OfficialGeoJsonExporter exporter =
            new OfficialGeoJsonExporter(mapper, pipes, economics, contract, calculator);

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsCostedRankedParallelRouteDespiteSavedValidFlag(String type) {
        Fixture fixture = parallel(type, 7.700);
        assertThat(pipes.byDiameter(100).orElseThrow().getPairWidthM()).isEqualByComparingTo("0.510");
        assertThat(line(fixture.edge.getCoordinates()).distance(fixture.restriction.getMetricGeometry()))
                .isCloseTo(1.700, offset(1e-8));
        assertThat(savedVariant(fixture).path("valid").asBoolean()).isTrue();
        assertThat(savedVariant(fixture).path("economics").path("complete").asBoolean()).isTrue();
        assertThat(savedVariant(fixture).path("economics").path("construction_cost").decimalValue())
                .isEqualByComparingTo("15769760.00"); // 120 × 89 748 + одна врезка 5 000 000.
        assertThat(savedVariant(fixture).path("rank").asInt()).isEqualTo(1);
        assertThat(fixture.edge.getSections()).singleElement()
                .satisfies(section -> assertThat(section.getKind()).isEqualTo("base"));

        // Та же смета и геометрия без дороги допустимы: RED должен быть именно про отступ.
        assertAccepted(fixture, withoutRestriction(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void acceptsParallelRouteAtExactDu100AxisClearance(String type) {
        Fixture fixture = parallel(type, 7.755);
        assertThat(line(fixture.edge.getCoordinates()).distance(fixture.restriction.getMetricGeometry()))
                .isCloseTo(1.755, offset(1e-8));
        assertAccepted(fixture, fixture.inputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void acceptsPerpendicular26mCrossingWithExactly12mSpecial(String type) {
        Fixture fixture = crossing(type, false);
        assertThat(fixture.edge.getLengthM()).isEqualByComparingTo("26");
        assertThat(fixture.edge.getSections()).extracting(RouteSection::getKind)
                .containsExactly("base", "special", "base");
        assertThat(fixture.edge.getSections().get(1).getLengthM()).isEqualByComparingTo("12");
        assertCrossingExtensions(fixture, coordinate(50, 0), coordinate(50, 6));
        assertSpecialOutput(fixture, "12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void appliesTheCurrentCatalogAngleToA45DegreeCrossing(String type) {
        Fixture fixture = crossing(type, true);
        if ("road".equals(type)) {
            assertRejected(fixture);
            return;
        }
        assertThat(fixture.edge.getLengthM()).isEqualByComparingTo("36.770");
        RouteSection special = fixture.edge.getSections().get(1);
        assertThat(special.getLengthM()).isEqualByComparingTo("14.485");
        assertThat(special.getCrossingAngleDegrees()).isEqualByComparingTo("45");
        assertCrossingExtensions(fixture, coordinate(50, 0), coordinate(56, 6));
        assertSpecialOutput(fixture, "14.485");
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void appliesTheCurrentCatalogAngleToObliqueMillimetreSections(String type) {
        Fixture fixture = obliqueCrossing(type, false);
        if ("road".equals(type)) assertRejected(fixture);
        else assertObliqueCrossingAccepted(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void appliesTheCurrentCatalogAngleToRoundedObliqueSections(String type) {
        Fixture fixture = obliqueCrossing(type, true);
        if ("road".equals(type)) assertRejected(fixture);
        else assertObliqueCrossingAccepted(fixture);
    }

    private void assertObliqueCrossingAccepted(Fixture fixture) {
        RoadCrossingClearance.Assessment original = new RoadCrossingClearance().assess(
                line(fixture.edge.getCoordinates()), fixture.restriction.getMetricGeometry(), 1.755, 45, 3);
        assertThat(original.isAllowed()).as("unrounded straight crossing: %s", original.getFailureCode()).isTrue();
        assertThat(original.getIntervals()).singleElement().satisfies(interval ->
                assertThat(interval.getAngleDegrees()).isCloseTo(63.434948823, offset(1e-6)));
        LineSegment originalAxis = new LineSegment(fixture.edge.getCoordinates().get(0).toCoordinate(),
                fixture.edge.getCoordinates().get(fixture.edge.getCoordinates().size() - 1).toCoordinate());
        double maximumQuantizationM = fixture.edge.getSections().stream()
                .flatMap(section -> section.getCoordinates().stream())
                .mapToDouble(point -> originalAxis.distance(point.toCoordinate())).max().orElseThrow();
        assertThat(maximumQuantizationM).isGreaterThan(1e-6).isLessThan(0.001);
        assertThat(fixture.edge.getSections().get(1).getLengthM()).isEqualByComparingTo("12.708");
        SavedChamberAssessment.verify(savedVariant(fixture), new ExistingNetworkSupportIndex(fixture.inputs), economics);
        assertAccepted(fixture, fixture.inputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void missingSavedSectionsCannotBypassParallelClearance(String type) {
        Fixture fixture = parallel(type, 7.700);
        savedEdge(fixture).remove("sections");
        assertAccepted(fixture, withoutRestriction(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void forgedSpecialLabelAndRepricedEconomicsCannotExemptParallelRoute(String type) {
        Fixture fixture = parallel(type, 7.700);
        List<RouteSection> forged = List.of(new RouteSection("special", type, RESTRICTION_ID,
                fixture.edge.getCoordinates(), 120, 90.0));
        RouteEdge repriced = new RouteEdge(fixture.edge.getId(), fixture.edge.getUpstreamNodeId(),
                fixture.edge.getDownstreamNodeId(), 120, fixture.edge.getCoordinates(), forged, FLOW_TPH, 100);
        savedEdge(fixture).set("sections", mapper.valueToTree(forged));
        savedVariant(fixture).set("economics", mapper.valueToTree(calculator.calculate(
                fixture.nodes, List.of(repriced), fixture.connections, ExistingNetworkReconstructionResult.empty())));
        assertThat(savedVariant(fixture).path("economics").path("construction_cost").decimalValue())
                .isEqualByComparingTo(economics.newNetworkCost(pipes.byDiameter(100).orElseThrow(),
                        new BigDecimal("120"), crossingType(type), DEPTH_M).add(economics.tieInCost()));

        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void emittedSectionShiftCannotUseTheSafeSavedEdgeAsClearanceAuthority(String type) {
        Fixture fixture = parallel(type, 7.755);
        assertAccepted(fixture, fixture.inputs);
        JsonNode before = savedVariant(fixture).path("economics").deepCopy();
        // 1 мм допускается сопоставлением секций с ребром, но не уменьшает официальный отступ.
        for (JsonNode point : savedEdge(fixture).path("sections").path(0).path("coordinates")) {
            ((ObjectNode) point).put("ym", point.path("ym").decimalValue().subtract(new BigDecimal("0.001")));
        }
        assertThat(savedEdge(fixture).path("coordinates").path(0).path("ym").decimalValue())
                .isEqualByComparingTo("6170007.755");
        assertThat(savedEdge(fixture).path("sections").path(0).path("coordinates").path(0)
                .path("ym").decimalValue()).isEqualByComparingTo("6170007.754");
        assertThat(savedVariant(fixture).path("economics")).isEqualTo(before);
        assertAccepted(fixture, withoutRestriction(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void checksLaterRankedVariantBeforeEmittingAnyEarlierValidFeature(String type) {
        Fixture valid = crossing(type, false);
        Fixture invalid = parallel(type, 7.700);
        accountForOtherDemand(valid, invalid);
        accountForOtherDemand(invalid, valid);
        assertThat(savedVariant(valid).path("economics").path("score").decimalValue())
                .isLessThan(savedVariant(invalid).path("economics").path("score").decimalValue());
        savedVariant(invalid).put("rank", 2);
        ObjectNode calculation = mapper.createObjectNode().put("input_profile", "baseline_input");
        calculation.putArray("variants").add(savedVariant(valid)).add(savedVariant(invalid));
        List<ImportedOfficialFeature> inputs = new ArrayList<>(valid.inputs);
        inputs.addAll(withoutRestriction(invalid));
        exporter.validateVariant(calculation, inputs, valid.id);

        assertAll(
                () -> assertStreamRejected(calculation, inputs, null),
                () -> assertStreamRejected(calculation, inputs, invalid.id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void acceptsCollinearVerticesAndContiguousSpecialSectionSplits(String type) {
        Fixture fixture = crossing(type, false);
        replaceSections(fixture, List.of(
                new RouteSection("base", null, null,
                        List.of(coordinate(50, -10), coordinate(50, -10), coordinate(50, -3)), 7, null),
                new RouteSection("special", type, RESTRICTION_ID,
                        List.of(coordinate(50, -3), coordinate(50, 0), coordinate(50, 3)), 6, 90.0),
                new RouteSection("special", type, RESTRICTION_ID,
                        List.of(coordinate(50, 3), coordinate(50, 6), coordinate(50, 9)), 6, 90.0),
                new RouteSection("base", null, null,
                        List.of(coordinate(50, 9), coordinate(50, 16)), 7, null)), 100);
        assertSpecialOutput(fixture, "12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsBaseLabelForRealCrossingEvenWhenRepriced(String type) {
        Fixture fixture = crossing(type, false);
        replaceSections(fixture, List.of(new RouteSection("base", null, null,
                fixture.edge.getCoordinates(), 26, null)), 100);
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsExpandedSpecialIntervalEvenWhenRepriced(String type) {
        assertChangedExtensionRejected(type, 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsShortenedSpecialIntervalEvenWhenRepriced(String type) {
        assertChangedExtensionRejected(type, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsUnresolvableOriginalRestrictionReference(String type) {
        Fixture fixture = crossing(type, false);
        ((ObjectNode) savedEdge(fixture).path("sections").path(1)).put("restriction_id", "missing-corridor");
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsRepricedSavedDu400AtClearanceThatWasSafeForDu100(String type) {
        Fixture fixture = parallel(type, 8.000);
        assertAccepted(fixture, fixture.inputs);
        replaceSections(fixture, fixture.edge.getSections(), 400);
        assertAccepted(fixture, withoutRestriction(fixture));
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void acceptsOverlappingRoadsWithAtomicBeforeOverlapAndAfterSections(String type) {
        Fixture fixture = overlappingRoads(type, false);
        assertThat(savedEdge(fixture).path("sections")).hasSize(5);
        JsonNode output = assertAccepted(fixture, fixture.inputs);
        BigDecimal specialLength = networks(output).stream()
                .map(feature -> feature.path("properties"))
                .filter(properties -> "special".equals(properties.path("laying_method").asText()))
                .map(properties -> properties.path("length").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Два перехода по 12 м перекрываются на 10 м: физическая длина равна 14, а не 24 м.
        assertThat(specialLength).isEqualByComparingTo("14");
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsWholeUnionLabelsEvenWhenOverlappingRoadsAreCorrectlyPriced(String type) {
        Fixture fixture = overlappingRoads(type, true);
        assertRejected(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "tram_tracks"})
    void rejectsDuplicateOriginalRestrictionIdsBeforeAnyOutput(String type) {
        Fixture fixture = crossing(type, false);
        List<ImportedOfficialFeature> inputs = new ArrayList<>(fixture.inputs);
        inputs.add(fixture.restriction);
        assertRejected(withInputs(fixture, inputs));
    }

    private Fixture overlappingRoads(String type, boolean wholeUnion) {
        Fixture fixture = crossing(type, false);
        List<RouteSection> sections = new ArrayList<>();
        sections.add(new RouteSection("base", null, null,
                List.of(coordinate(50, -10), coordinate(50, -3)), 7, null));
        if (wholeUnion) {
            sections.add(new RouteSection("special", type, RESTRICTION_ID + "+other-corridor",
                    List.of(coordinate(50, -3), coordinate(50, 11)), 14, 90.0));
        } else {
            sections.add(new RouteSection("special", type, RESTRICTION_ID,
                    List.of(coordinate(50, -3), coordinate(50, -1)), 2, 90.0));
            sections.add(new RouteSection("special", type, RESTRICTION_ID + "+other-corridor",
                    List.of(coordinate(50, -1), coordinate(50, 9)), 10, 90.0));
            sections.add(new RouteSection("special", type, "other-corridor",
                    List.of(coordinate(50, 9), coordinate(50, 11)), 2, 90.0));
        }
        sections.add(new RouteSection("base", null, null,
                List.of(coordinate(50, 11), coordinate(50, 16)), 5, null));
        replaceSections(fixture, sections, 100);
        List<ImportedOfficialFeature> inputs = new ArrayList<>(fixture.inputs);
        inputs.add(new ImportedOfficialFeature("other-corridor", "restriction",
                mapper.createObjectNode().put("restriction_type", type), geometryFactory.createPolygon(new Coordinate[] {
                    coordinate(0, 2).toCoordinate(), coordinate(100, 2).toCoordinate(), coordinate(100, 8).toCoordinate(),
                    coordinate(0, 8).toCoordinate(), coordinate(0, 2).toCoordinate()})));
        return withInputs(fixture, inputs);
    }

    private Fixture withInputs(Fixture fixture, List<ImportedOfficialFeature> inputs) {
        return new Fixture(fixture.id, fixture.calculation, inputs, fixture.restriction,
                fixture.nodes, fixture.edge, fixture.connections);
    }

    private void assertChangedExtensionRejected(String type, double extension) {
        Fixture fixture = crossing(type, false);
        RouteCoordinate first = coordinate(50, -extension), last = coordinate(50, 6 + extension);
        replaceSections(fixture, List.of(
                new RouteSection("base", null, null,
                        List.of(coordinate(50, -10), first), 10 - extension, null),
                new RouteSection("special", type, RESTRICTION_ID,
                        List.of(first, last), 6 + 2 * extension, 90.0),
                new RouteSection("base", null, null,
                        List.of(last, coordinate(50, 16)), 10 - extension, null)), 100);
        assertRejected(fixture);
    }

    private void replaceSections(Fixture fixture, List<RouteSection> sections, int diameter) {
        RouteEdge edge = new RouteEdge(fixture.edge.getId(), fixture.edge.getUpstreamNodeId(),
                fixture.edge.getDownstreamNodeId(), fixture.edge.getLengthM().doubleValue(),
                fixture.edge.getCoordinates(), sections, FLOW_TPH, diameter);
        savedVariant(fixture).withArray("edges").set(0, mapper.valueToTree(edge));
        savedVariant(fixture).set("economics", mapper.valueToTree(calculator.calculate(
                fixture.nodes, List.of(edge), fixture.connections, ExistingNetworkReconstructionResult.empty())));
    }

    private void assertRejected(Fixture fixture) {
        assertAll("Original restrictions must override saved acceptance and sections",
                () -> assertIncomplete(catchThrowable(() -> exporter.export(fixture.calculation, fixture.inputs))),
                () -> assertIncomplete(catchThrowable(() -> exporter.validate(fixture.calculation, fixture.inputs))),
                () -> assertIncomplete(catchThrowable(() -> exporter.validateVariant(
                        fixture.calculation, fixture.inputs, fixture.id))),
                () -> assertStreamRejected(fixture.calculation, fixture.inputs, null),
                () -> assertStreamRejected(fixture.calculation, fixture.inputs, fixture.id));
    }

    private void assertStreamRejected(JsonNode calculation, List<ImportedOfficialFeature> inputs, String id) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Throwable failure = catchThrowable(() -> {
            if (id == null) exporter.writeValidated(calculation, inputs, output);
            else exporter.writeValidatedVariant(calculation, inputs, id, output);
        });
        assertAll("Reject before the stream consumer receives its first feature",
                () -> assertIncomplete(failure),
                () -> assertThat(output.toString(StandardCharsets.UTF_8).contains("\"type\":\"Feature\""))
                        .as("stream emitted a feature before clearance rejection").isFalse());
    }

    private void assertIncomplete(Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("OFFICIAL_EXPORT_INCOMPLETE");
    }

    private JsonNode assertAccepted(Fixture fixture, List<ImportedOfficialFeature> inputs) {
        exporter.validate(fixture.calculation, inputs);
        exporter.validateVariant(fixture.calculation, inputs, fixture.id);
        JsonNode output = exporter.export(fixture.calculation, inputs);
        assertThat(contract.validate(output)).isEmpty();
        assertThat(output.path("features")).isNotEmpty();
        BigDecimal networkCost = networks(output).stream()
                .map(feature -> feature.path("properties").path("cost").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        JsonNode saved = savedVariant(fixture).path("economics");
        assertThat(networkCost.add(economics.tieInCost()))
                .isEqualByComparingTo(saved.path("construction_cost").decimalValue());
        assertThat(saved.path("tie_in_cost").decimalValue()).isEqualByComparingTo("5000000");
        assertAll(
                () -> assertStreamMatches(fixture, inputs, output, false),
                () -> assertStreamMatches(fixture, inputs, output, true));
        return output;
    }

    private void assertStreamMatches(Fixture fixture, List<ImportedOfficialFeature> inputs,
            JsonNode expected, boolean selected) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (selected) exporter.writeValidatedVariant(fixture.calculation, inputs, fixture.id, output);
        else exporter.writeValidated(fixture.calculation, inputs, output);
        assertThat(mapper.readTree(output.toByteArray())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(expected)));
    }

    private void assertSpecialOutput(Fixture fixture, String specialLength) {
        JsonNode output = assertAccepted(fixture, fixture.inputs);
        assertThat(networks(output)).hasSize(3).filteredOn(feature -> "special".equals(
                feature.path("properties").path("laying_method").asText())).singleElement().satisfies(feature -> {
                    JsonNode properties = feature.path("properties");
                    assertThat(properties.path("length").decimalValue()).isEqualByComparingTo(specialLength);
                    assertThat(properties.path("diameter").asInt()).isEqualTo(100);
                    assertThat(properties.path("cost").decimalValue()).isEqualByComparingTo(economics.newNetworkCost(
                            pipes.byDiameter(100).orElseThrow(), new BigDecimal(specialLength),
                            crossingType(fixture.restriction.getAttributes().path("restriction_type").asText()), DEPTH_M));
                });
    }

    private List<JsonNode> networks(JsonNode output) {
        return StreamSupport.stream(output.path("features").spliterator(), false)
                .filter(feature -> "heat_network".equals(feature.path("properties").path("object_type").asText()))
                .collect(Collectors.toList());
    }

    private void assertCrossingExtensions(Fixture fixture, RouteCoordinate entry, RouteCoordinate exit) {
        RouteSection special = fixture.edge.getSections().get(1);
        List<RouteCoordinate> points = special.getCoordinates();
        assertThat(points.get(0).toCoordinate().distance(entry.toCoordinate()))
                .isCloseTo(3.0, offset(GEOMETRY_TOLERANCE_M));
        assertThat(points.get(1).toCoordinate().distance(exit.toCoordinate()))
                .isCloseTo(3.0, offset(GEOMETRY_TOLERANCE_M));
        assertThat(line(points).intersection(fixture.restriction.getMetricGeometry()).getLength())
                .isCloseTo(entry.toCoordinate().distance(exit.toCoordinate()), offset(GEOMETRY_TOLERANCE_M));
    }

    private Fixture parallel(String type, double y) {
        List<RouteCoordinate> coordinates = List.of(coordinate(-10, y), coordinate(110, y));
        return fixture("parallel", type, coordinates,
                List.of(new RouteSection("base", null, null, coordinates, 120, null)));
    }

    private Fixture crossing(String type, boolean diagonal) {
        RouteCoordinate start = coordinate(diagonal ? 40 : 50, -10);
        RouteCoordinate end = coordinate(diagonal ? 66 : 50, 16);
        double extension = diagonal ? 3 / Math.sqrt(2) : 3;
        RouteCoordinate specialStart = coordinate(diagonal ? 50 - extension : 50, -extension);
        RouteCoordinate specialEnd = coordinate(diagonal ? 56 + extension : 50, 6 + extension);
        double baseLength = diagonal ? 10 * Math.sqrt(2) - 3 : 7;
        double specialLength = diagonal ? 6 * Math.sqrt(2) + 6 : 12;
        return fixture("crossing", type, List.of(start, end), List.of(
                new RouteSection("base", null, null, List.of(start, specialStart), baseLength, null),
                new RouteSection("special", type, RESTRICTION_ID, List.of(specialStart, specialEnd),
                        specialLength, diagonal ? 45.0 : 90.0),
                new RouteSection("base", null, null, List.of(specialEnd, end), baseLength, null)));
    }

    private Fixture obliqueCrossing(String type, boolean collinearVertex) {
        RouteCoordinate start = coordinate(40, -10), end = coordinate(53, 16), middle = coordinate(46, 2);
        double unitX = 1 / Math.sqrt(5), unitY = 2 / Math.sqrt(5);
        // Ось имеет угол atan2(2, 1), вход (45, 0), выход (48, 6); длины защитных частей ровно по 3 м.
        RouteCoordinate specialStart = coordinate(45 - 3 * unitX, -3 * unitY);
        RouteCoordinate specialEnd = coordinate(48 + 3 * unitX, 6 + 3 * unitY);
        List<RouteCoordinate> axis = collinearVertex ? List.of(start, middle, end) : List.of(start, end);
        List<RouteCoordinate> special = collinearVertex
                ? List.of(specialStart, middle, specialEnd) : List.of(specialStart, specialEnd);
        double baseLength = 5 * Math.sqrt(5) - 3;
        return fixture("oblique", type, axis, List.of(
                new RouteSection("base", null, null, List.of(start, specialStart), baseLength, null),
                new RouteSection("special", type, RESTRICTION_ID, special,
                        3 * Math.sqrt(5) + 6, Math.toDegrees(Math.atan2(2, 1))),
                new RouteSection("base", null, null, List.of(specialEnd, end), baseLength, null)));
    }

    /** Синтетический сохранённый результат: настоящий граф, входной корень/ОКС и штатная смета. */
    private Fixture fixture(String id, String type, List<RouteCoordinate> coordinates, List<RouteSection> sections) {
        RouteCoordinate start = coordinates.get(0), end = coordinates.get(coordinates.size() - 1);
        List<RouteNode> nodes = List.of(
                new RouteNode(id + "-root", "existing_chamber_tie_in", start, true, true, 1, id + "-chamber", 100),
                new RouteNode(id + "-demand", "demand_connection", end, false, false, 0, id + "-cp"));
        RouteEdge edge = new RouteEdge(id + "-edge", nodes.get(0).getId(), nodes.get(1).getId(),
                line(coordinates).getLength(), coordinates, sections, FLOW_TPH, 100);
        List<RouteConnection> connections = List.of(new RouteConnection(
                id + "-cp", nodes.get(1).getId(), FLOW_TPH, "connected", null));
        VariantEconomics costs = calculator.calculate(nodes, List.of(edge), connections, ExistingNetworkReconstructionResult.empty());
        RouteVariant variant = new RouteVariant(id, "cheapest", nodes, List.of(edge), connections,
                edge.getLengthM(), List.of(), List.of(), ExistingNetworkReconstructionResult.empty(), costs, 1);
        ObjectNode calculation = mapper.createObjectNode().put("input_profile", "baseline_input");
        calculation.putArray("variants").add(mapper.valueToTree(variant));
        ImportedOfficialFeature restriction = new ImportedOfficialFeature(RESTRICTION_ID, "restriction",
                mapper.createObjectNode().put("restriction_type", type), geometryFactory.createPolygon(new Coordinate[] {
                    coordinate(0, 0).toCoordinate(), coordinate(100, 0).toCoordinate(), coordinate(100, 6).toCoordinate(),
                    coordinate(0, 6).toCoordinate(), coordinate(0, 0).toCoordinate()}));
        Coordinate source = start.toCoordinate();
        Coordinate next = coordinates.get(1).toCoordinate();
        double firstLegLength = source.distance(next);
        // Keep the chamber fixture normal to the existing main so this test isolates export clearance.
        double dx = next.x - source.x, dy = next.y - source.y;
        source.x -= 20 * dy / firstLegLength;
        source.y += 20 * dx / firstLegLength;
        List<ImportedOfficialFeature> inputs = List.of(
                new ImportedOfficialFeature(id + "-source", "source", mapper.createObjectNode(), geometryFactory.createPoint(source)),
                new ImportedOfficialFeature(id + "-network", "heat_network", mapper.createObjectNode()
                        .put("upstream_object_id", id + "-source").put("diameter", 100).put("flow_tph", 0),
                        geometryFactory.createLineString(new Coordinate[] {source, start.toCoordinate()})),
                new ImportedOfficialFeature(id + "-chamber", "heat_chamber", mapper.createObjectNode()
                        .put("upstream_object_id", id + "-network").put("diameter", 100), geometryFactory.createPoint(start.toCoordinate())),
                new ImportedOfficialFeature(id + "-cp", "oks_connection_point", mapper.createObjectNode()
                        .put("flow_tph", FLOW_TPH), geometryFactory.createPoint(end.toCoordinate())),
                restriction);
        return new Fixture(id, calculation, inputs, restriction, nodes, edge, connections);
    }

    private ObjectNode savedVariant(Fixture fixture) { return (ObjectNode) fixture.calculation.path("variants").path(0); }
    private ObjectNode savedEdge(Fixture fixture) { return (ObjectNode) savedVariant(fixture).path("edges").path(0); }

    private void accountForOtherDemand(Fixture fixture, Fixture other) {
        List<RouteConnection> connections = new ArrayList<>(fixture.connections);
        connections.add(new RouteConnection(other.id + "-cp", other.id + "-cp", FLOW_TPH,
                "no_route", "Not connected in this stored alternative"));
        savedVariant(fixture).set("connections", mapper.valueToTree(connections));
        savedVariant(fixture).put("no_route_demand_count", 1);
        savedVariant(fixture).set("economics", mapper.valueToTree(calculator.calculate(
                fixture.nodes, List.of(fixture.edge), connections, ExistingNetworkReconstructionResult.empty())));
    }

    private List<ImportedOfficialFeature> withoutRestriction(Fixture fixture) {
        return fixture.inputs.stream().filter(feature -> !RESTRICTION_ID.equals(feature.getFeatureId())).collect(Collectors.toList());
    }

    private SpecialCrossingType crossingType(String type) {
        return "road".equals(type) ? SpecialCrossingType.ROAD : SpecialCrossingType.TRAM_TRACKS;
    }

    private RouteCoordinate coordinate(double x, double y) { return new RouteCoordinate(500000 + x, 6170000 + y); }

    private LineString line(List<RouteCoordinate> points) {
        return geometryFactory.createLineString(points.stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new));
    }

    private static final class Fixture {
        private final String id;
        private final ObjectNode calculation;
        private final List<ImportedOfficialFeature> inputs;
        private final ImportedOfficialFeature restriction;
        private final List<RouteNode> nodes;
        private final RouteEdge edge;
        private final List<RouteConnection> connections;

        private Fixture(String id, ObjectNode calculation, List<ImportedOfficialFeature> inputs,
                ImportedOfficialFeature restriction, List<RouteNode> nodes, RouteEdge edge, List<RouteConnection> connections) {
            this.id = id;
            this.calculation = calculation;
            this.inputs = inputs;
            this.restriction = restriction;
            this.nodes = nodes;
            this.edge = edge;
            this.connections = connections;
        }
    }
}
