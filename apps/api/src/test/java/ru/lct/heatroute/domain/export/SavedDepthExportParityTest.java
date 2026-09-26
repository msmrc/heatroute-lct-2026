package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.depth.*;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.*;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.*;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Saved-profile admission uses source geometry; arithmetic expectations are raw analytic integrals. */
class SavedDepthExportParityTest {
    private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator calculator = new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialGeoJsonExporter exporter = new OfficialGeoJsonExporter(mapper, pipes, economics,
            new OfficialOutputContractValidator(), calculator);

    @Test void lawfulPersistedUtilityProfileRoundTrips() throws Exception {
        ObjectNode calculation = fixture(null);
        JsonNode reloaded = mapper.readTree(mapper.writeValueAsBytes(calculation));
        exporter.validate(reloaded, sources());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.writeValidated(reloaded, sources(), output);
        assertThat(mapper.readTree(output.toByteArray()).path("features")).isNotEmpty();
    }

    @Test void exactAnalyticPriceSurvivesNonBreakpointSectionCut() throws Exception {
        for (String cut : List.of("42.003", "41.751", "47.999", "54.187", "58.249")) {
            ObjectNode calculation = fixture(cut);
            JsonNode output = exporter.export(calculation, sources());
            BigDecimal actual = StreamSupport.stream(output.path("features").spliterator(), false)
                    .map(f -> f.path("properties"))
                    .filter(p -> "heat_network".equals(p.path("object_type").asText()))
                    .map(p -> p.path("cost").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(actual).as("independent closed-form integral cut=%s", cut).isEqualByComparingTo(analyticPrice(cut));
        }
    }

    @Test void wrongActualUtilityStationFailsBeforeFirstByte() throws Exception {
        ObjectNode calculation = fixture(null);
        List<ImportedOfficialFeature> shifted = List.of(source("LINESTRING (500080 6099990,500080 6100010)"));
        rejected(calculation, shifted, "CROSSING_STATION_MISMATCH");
    }

    @Test void dipInsideSavedPlateauFailsBeforeFirstByte() throws Exception {
        ObjectNode calculation = fixture(null);
        ArrayNode points = (ArrayNode) profile(calculation).path("points");
        points.insert(3, mapper.createObjectNode().put("station_m", 50).put("depth_m", new BigDecimal("3.6")));
        rejected(calculation, sources(), "CROSSING_PROFILE_MISMATCH");
    }

    @Test void reportedWeightedMeanCannotReplaceRecalculation() throws Exception {
        ObjectNode calculation = fixture(null);
        profile(calculation).put("depth_adjusted_cost_meters", new BigDecimal("999"));
        rejected(calculation, sources(), "DEPTH_WEIGHTED_METERS_MISMATCH");
    }

    @Test void reportedThreeDimensionalLengthCannotReplaceXyCheck() throws Exception {
        ObjectNode calculation = fixture(null);
        profile(calculation).put("profile_length_3d_m", new BigDecimal("100"));
        rejected(calculation, sources(), "DEPTH_3D_LENGTH_MISMATCH");
    }

    @Test void unorderedOrIncompleteSavedProfileCannotExport() throws Exception {
        ObjectNode incomplete = fixture(null);
        profile(incomplete).put("complete", false);
        rejected(incomplete, sources(), "DEPTH_PROFILE_INCOMPLETE");
        ObjectNode unordered = fixture(null);
        ((ObjectNode) profile(unordered).path("points").path(1)).put("station_m", 55);
        rejected(unordered, sources(), "PROFILE_STATION_ORDER");
    }

    @Test void invalidSecondVariantArithmeticAlsoWritesNoBytes() throws Exception {
        ObjectNode calculation = fixture(null);
        ObjectNode invalid = ((ObjectNode) calculation.path("variants").path(0)).deepCopy();
        invalid.put("id", "invalid"); invalid.put("rank", 2);
        ObjectNode cost = (ObjectNode) invalid.path("economics");
        BigDecimal changed = cost.path("construction_cost").decimalValue().add(new BigDecimal(".01"));
        cost.put("construction_cost", changed); cost.put("calculated_cost", changed);
        cost.put("score", economics.score(changed, new BigDecimal("100")));
        ((ArrayNode) calculation.path("variants")).add(invalid);
        rejected(calculation, sources(), "exported construction components disagree");
    }

    @Test void linearSegmentCrossingThreeMetresUsesXyIntegralWithoutMandatoryExtraVertex() throws Exception {
        ObjectNode calculation = flatFixture("2.999", "3.002");
        ObjectNode profile = profile(calculation);
        profile.put("depth_adjusted_cost_meters", new BigDecimal("100.007"));
        BigDecimal rate = pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM();
        BigDecimal firstLength = new BigDecimal("100").divide(new BigDecimal("3"),18,RoundingMode.HALF_UP);
        BigDecimal secondWeighted = new BigDecimal("200.02").divide(new BigDecimal("3"),18,RoundingMode.HALF_UP);
        BigDecimal expected = rate.multiply(firstLength).setScale(2,RoundingMode.HALF_UP)
                .add(rate.multiply(secondWeighted).setScale(2,RoundingMode.HALF_UP));
        replaceCost(calculation, expected);
        JsonNode output = exporter.export(calculation, List.of());
        BigDecimal sum = StreamSupport.stream(output.path("features").spliterator(),false)
                .map(f -> f.path("properties"))
                .filter(p -> "heat_network".equals(p.path("object_type").asText()))
                .map(p -> p.path("cost").decimalValue()).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(expected);
    }

    @Test void priceThresholdNearSectionBoundaryDoesNotCreateZeroLengthGeometry() throws Exception {
        ObjectNode calculation=flatFixture("2.999","3.002");
        ObjectNode edge=(ObjectNode)calculation.path("variants").path(0).path("edges").path(0);
        edge.set("sections",mapper.valueToTree(List.of(section("base",0,33.333),section("base",33.333,100))));
        profile(calculation).put("depth_adjusted_cost_meters",new BigDecimal("100.007"));
        BigDecimal rate=pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM();
        BigDecimal oneThird=new BigDecimal("100").divide(new BigDecimal("3"),18,RoundingMode.HALF_UP);
        BigDecimal expected=rate.multiply(new BigDecimal("33.333")).setScale(2,RoundingMode.HALF_UP)
                .add(rate.multiply(oneThird.subtract(new BigDecimal("33.333"))).setScale(2,RoundingMode.HALF_UP))
                .add(rate.multiply(new BigDecimal("200.02").divide(new BigDecimal("3"),18,RoundingMode.HALF_UP)).setScale(2,RoundingMode.HALF_UP));
        replaceCost(calculation,expected);
        JsonNode output=exporter.export(calculation,List.of());
        assertThat(StreamSupport.stream(output.path("features").spliterator(),false)
                .map(f -> f.path("properties")).filter(p -> "heat_network".equals(p.path("object_type").asText())))
                .allSatisfy(p -> {
                    assertThat(p.path("length").decimalValue()).isPositive();
                    assertThat(p.path("start_node_id").textValue()).isNotEqualTo(p.path("end_node_id").textValue());
                });
    }

    @Test void proportionalSectionStationsKeepSubMillimetreArithmetic() throws Exception {
        ObjectNode calculation = fixture(null);
        ObjectNode variant = (ObjectNode) calculation.path("variants").path(0);
        ((ObjectNode)variant.path("edges").path(0)).put("length_m",new BigDecimal("100.001"));
        variant.put("total_length_m",new BigDecimal("100.001"));
        ObjectNode profile=profile(calculation);
        // Actual physical48..52m maps to stored48.00048..52.00052m; outward millimetre
        // bounds are48.000..52.001. The manual plateau must retain that complete span.
        // Each .8m ramp needs at least8 physical metres, hence8.001 stored metres.
        ((ObjectNode)profile.path("points").path(1)).put("station_m",new BigDecimal("39.999"));
        ((ObjectNode)profile.path("points").path(3)).put("station_m",new BigDecimal("52.001"));
        ((ObjectNode)profile.path("points").path(4)).put("station_m",new BigDecimal("60.002"));
        ((ObjectNode)profile.path("points").path(5)).put("station_m",new BigDecimal("100.001"));
        ((ObjectNode)profile.path("crossings").path(0)).put("plateau_end_m",new BigDecimal("52.001"))
                .put("ramp_start_m",new BigDecimal("39.999")).put("ramp_end_m",new BigDecimal("60.002"));
        profile.put("profile_length_3d_m",BigDecimal.valueOf(83.999+2*Math.hypot(8.001,.8)).setScale(3,RoundingMode.HALF_UP));
        profile.put("depth_adjusted_cost_meters",new BigDecimal("101.177"));
        BigDecimal expected=BigDecimal.ZERO;
        List<BigDecimal> cuts=new ArrayList<>();
        for(String cut:List.of("0","39.999","48","48.00048","52.00052","52.001","60.002","100.001")) cuts.add(new BigDecimal(cut));
        for(int i=1;i<cuts.size();i++) {
            BigDecimal a=cuts.get(i-1),b=cuts.get(i);
            BigDecimal length=b.subtract(a).multiply(new BigDecimal("100")).divide(new BigDecimal("100.001"),18,RoundingMode.HALF_UP);
            java.util.function.UnaryOperator<BigDecimal> lawfulK = station -> {
                if(station.compareTo(new BigDecimal("39.999"))<=0 || station.compareTo(new BigDecimal("60.002"))>=0) return BigDecimal.ONE;
                if(station.compareTo(new BigDecimal("48"))<0) return BigDecimal.ONE.add(station.subtract(new BigDecimal("39.999")).multiply(new BigDecimal(".08")).divide(new BigDecimal("8.001"),18,RoundingMode.HALF_UP));
                if(station.compareTo(new BigDecimal("52.001"))<=0) return new BigDecimal("1.08");
                return BigDecimal.ONE.add(new BigDecimal("60.002").subtract(station).multiply(new BigDecimal(".08")).divide(new BigDecimal("8.001"),18,RoundingMode.HALF_UP));
            };
            BigDecimal k=lawfulK.apply(a).add(lawfulK.apply(b)).divide(new BigDecimal("2"));
            BigDecimal special=a.compareTo(new BigDecimal("48.00048"))>=0&&b.compareTo(new BigDecimal("52.00052"))<=0
                    ?new BigDecimal("1.05"):BigDecimal.ONE;
            expected=expected.add(pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM()
                    .multiply(length).multiply(k).multiply(special).setScale(2,RoundingMode.HALF_UP));
        }
        replaceCost(calculation,expected);
        ObjectNode saved=(ObjectNode)variant.path("economics");
        saved.put("new_network_length",new BigDecimal("100.001"));
        saved.put("score",economics.score(saved.path("construction_cost").decimalValue(),new BigDecimal("100.001")));
        exporter.validate(calculation,sources());
    }

    @Test void actualRoadAndTramPolygonsImposeCoverIncludingInteriorVertices() throws Exception {
        for (String type : List.of("road", "tram_tracks")) {
            String floor = "road".equals(type) ? "1.0" : "1.2";
            ImportedOfficialFeature source = new ImportedOfficialFeature("street","restriction",
                    mapper.createObjectNode().put("restriction_type",type),
                    new WKTReader().read("POLYGON ((500048 6099990,500052 6099990,500052 6100010,500048 6100010,500048 6099990))"));
            ObjectNode lawful = flatFixture(floor,floor);
            SavedDepthAssessment.verify(lawful.path("variants").path(0),List.of(source),pipes,null);
            ObjectNode invalid = flatFixture("0.9","0.9");
            assertThatThrownBy(() -> SavedDepthAssessment.verify(invalid.path("variants").path(0),List.of(source),pipes,null))
                    .hasMessageContaining("DEPTH_FLOOR_VIOLATION");
            ObjectNode dip = flatFixture(floor,floor);
            ArrayNode points = (ArrayNode) profile(dip).path("points");
            points.insert(1,mapper.createObjectNode().put("station_m",48).put("depth_m",new BigDecimal(floor)));
            points.insert(2,mapper.createObjectNode().put("station_m",50).put("depth_m",new BigDecimal(floor).subtract(new BigDecimal(".2"))));
            points.insert(3,mapper.createObjectNode().put("station_m",52).put("depth_m",new BigDecimal(floor)));
            assertThatThrownBy(() -> SavedDepthAssessment.verify(dip.path("variants").path(0),List.of(source),pipes,null))
                    .hasMessageContaining("DEPTH_FLOOR_VIOLATION");
        }
    }

    @Test void sourceNodeIdentityRequiresSameDepthAndPartialProfilesCannotDisappear() {
        ObjectNode calculation = flatFixture("3","3");
        ArrayNode edges = (ArrayNode) calculation.path("variants").path(0).path("edges");
        ObjectNode duplicate = ((ObjectNode)edges.path(0)).deepCopy();
        duplicate.put("id","other"); duplicate.put("upstream_node_id","end"); duplicate.put("downstream_node_id","other-end");
        for(JsonNode p:duplicate.path("coordinates")) ((ObjectNode)p).put("xm",p.path("xm").decimalValue().add(new BigDecimal("100")));
        edges.add(duplicate);
        SavedDepthAssessment.verify(calculation.path("variants").path(0),List.of(),pipes,null);
        ((ObjectNode)duplicate.path("depth_profile").path("points").path(0)).put("depth_m",new BigDecimal("2.9"));
        assertThatThrownBy(() -> SavedDepthAssessment.verify(calculation.path("variants").path(0),List.of(),pipes,null))
                .hasMessageContaining("DEPTH_NODE_DISCONTINUITY");
        duplicate.remove("depth_profile");
        assertThatThrownBy(() -> SavedDepthAssessment.verify(calculation.path("variants").path(0),List.of(),pipes,null))
                .hasMessageContaining("DEPTH_PROFILE_INCOMPLETE");
    }

    @Test void trustedRunParametersDetectFullyDeletedProfilesAndNarrowDepthBounds() {
        ObjectNode calculation = fixture(null);
        ru.lct.heatroute.domain.run.OfficialRunParameters narrow = new ru.lct.heatroute.domain.run.OfficialRunParameters(
                new BigDecimal(".7"),new BigDecimal("3.5"),true);
        assertThatThrownBy(() -> exporter.validate(calculation, uncheckedSources(), narrow)).hasMessageContaining("PROFILE_DEPTH_RANGE");
        ((ObjectNode)calculation.path("variants").path(0).path("edges").path(0)).remove("depth_profile");
        assertThatThrownBy(() -> exporter.validate(calculation, uncheckedSources(), narrow)).hasMessageContaining("DEPTH_PROFILE_INCOMPLETE");
    }

    @Test void savedPhysicalPlateauCrossesTechnicalSplitAtCrossingOrInsidePlateau() throws Exception {
        for(int split:List.of(50,51)) {
            ObjectNode calculation=splitFixture(split);
            exporter.validate(mapper.readTree(mapper.writeValueAsBytes(calculation)),sources());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            exporter.writeValidated(calculation,sources(),out);
            assertThat(out.size()).isPositive();
        }
    }

    @Test void deletingContinuationDecisionOrForgingItsDepthFailsBeforeBytes() throws Exception {
        ObjectNode missing=splitFixture(50);
        ((ObjectNode)missing.path("variants").path(0).path("edges").path(1).path("depth_profile")).putArray("crossings");
        rejected(missing,sources(),"CROSSING_DECISION_MISSING");
        ObjectNode forged=splitFixture(51);
        ArrayNode points=(ArrayNode)forged.path("variants").path(0).path("edges").path(1).path("depth_profile").path("points");
        ((ObjectNode)points.path(1)).put("depth_m",new BigDecimal("3.75"));
        rejected(forged,sources(),"CROSSING_PROFILE_MISMATCH");
    }

    @Test void internalSplitCannotHideUtilityByDeletingAllDecisions() throws Exception {
        ObjectNode calculation=splitFixture(50);
        for(JsonNode edge:calculation.path("variants").path(0).path("edges")) {
            ObjectNode p=(ObjectNode)edge.path("depth_profile");
            p.putArray("crossings");
            BigDecimal length=edge.path("length_m").decimalValue();
            p.set("points",mapper.valueToTree(List.of(point("0","3"),point(length.toPlainString(),"3"))));
            p.put("profile_length_3d_m",length).put("depth_adjusted_cost_meters",length);
        }
        // Forgery also keeps all derived totals consistent: only actual physical-source admission can catch it.
        replaceCost(calculation,pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM().multiply(new BigDecimal("100.2")));
        rejected(calculation,sources(),"CROSSING_DECISION_MISSING");
    }

    private ObjectNode splitFixture(int split) {
        ObjectNode calculation=fixture(Integer.toString(split));
        ObjectNode variant=(ObjectNode)calculation.path("variants").path(0);
        ((ArrayNode)variant.path("nodes")).add(mapper.valueToTree(new RouteNode("split","technical_node",xy(split),false,false,0,null)));
        ArrayNode edges=variant.putArray("edges");
        for(int side=0;side<2;side++) {
            int start=side==0?0:split,end=side==0?split:100;
            TreeSet<BigDecimal> cuts=new TreeSet<>();cuts.add(BigDecimal.valueOf(start));cuts.add(BigDecimal.valueOf(end));
            for(String value:List.of("40","48","52","60")) {
                BigDecimal cut=new BigDecimal(value);
                if(cut.compareTo(BigDecimal.valueOf(start))>0&&cut.compareTo(BigDecimal.valueOf(end))<0) cuts.add(cut);
            }
            List<BigDecimal> ordered=new ArrayList<>(cuts);List<DepthProfilePoint> points=new ArrayList<>();
            BigDecimal length3d=BigDecimal.ZERO,weighted=BigDecimal.ZERO;
            for(int i=0;i<ordered.size();i++) {
                BigDecimal x=ordered.get(i),h=new BigDecimal("3").add(analyticK(x).subtract(BigDecimal.ONE).multiply(BigDecimal.TEN));
                points.add(new DepthProfilePoint(x.subtract(BigDecimal.valueOf(start)),h));
                if(i==0) continue;
                BigDecimal previous=ordered.get(i-1),delta=x.subtract(previous);
                BigDecimal previousH=new BigDecimal("3").add(analyticK(previous).subtract(BigDecimal.ONE).multiply(BigDecimal.TEN));
                length3d=length3d.add(BigDecimal.valueOf(Math.hypot(delta.doubleValue(),h.subtract(previousH).doubleValue())));
                BigDecimal special=previous.compareTo(new BigDecimal("48"))>=0&&x.compareTo(new BigDecimal("52"))<=0?new BigDecimal("1.05"):BigDecimal.ONE;
                weighted=weighted.add(delta.multiply(analyticK(previous).add(analyticK(x)).divide(new BigDecimal("2"))).multiply(special));
            }
            DepthCrossingDecision decision=new DepthCrossingDecision("existing","heat_network","below",new BigDecimal("3.8"),
                    BigDecimal.valueOf(Math.max(start,40)-start),BigDecimal.valueOf(Math.max(start,48)-start),
                    BigDecimal.valueOf(Math.min(end,52)-start),BigDecimal.valueOf(Math.min(end,60)-start),new BigDecimal(".675"),new BigDecimal(".5"));
            DepthProfileResult profile=new DepthProfileResult(true,points,List.of(decision),List.of(),length3d,weighted);
            List<RouteSection> sections=new ArrayList<>();
            if(start<48) sections.add(section("base",start,Math.min(48,end)));
            sections.add(section("special",Math.max(start,48),Math.min(end,52)));
            if(end>52) sections.add(section("base",Math.max(start,52),end));
            edges.add(mapper.valueToTree(new RouteEdge("edge"+side,side==0?"start":"split",side==0?"split":"end",end-start,
                    List.of(xy(start),xy(end)),sections,BigDecimal.ONE,50,profile)));
        }
        return calculation;
    }

    private List<ImportedOfficialFeature> uncheckedSources() {
        try { return sources(); } catch(Exception e) { throw new IllegalStateException(e); }
    }

    private ObjectNode flatFixture(String start,String end) {
        ObjectNode calculation=fixture(null);
        ObjectNode edge=(ObjectNode)calculation.path("variants").path(0).path("edges").path(0);
        edge.set("sections",mapper.valueToTree(List.of(section("base",0,100))));
        ObjectNode profile=profile(calculation);
        profile.set("points",mapper.valueToTree(List.of(point("0",start),point("100",end))));
        profile.putArray("crossings");
        profile.put("profile_length_3d_m",BigDecimal.valueOf(Math.hypot(100,Double.parseDouble(end)-Double.parseDouble(start))).setScale(3,RoundingMode.HALF_UP));
        profile.put("depth_adjusted_cost_meters",new BigDecimal("100"));
        replaceCost(calculation,pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM().multiply(new BigDecimal("100")));
        return calculation;
    }

    private void replaceCost(ObjectNode calculation,BigDecimal pipeCost) {
        ObjectNode cost=(ObjectNode)calculation.path("variants").path(0).path("economics");
        BigDecimal construction=pipeCost.add(new BigDecimal("3000000"));
        cost.put("construction_cost",construction).put("calculated_cost",construction)
                .put("score",economics.score(construction,new BigDecimal("100")));
    }

    private void rejected(ObjectNode calculation, List<ImportedOfficialFeature> features, String code) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exporter.writeValidated(calculation, features, out))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(code);
        assertThat(out.size()).as("preflight rejects before first byte").isZero();
    }

    private ObjectNode profile(ObjectNode calculation) {
        return (ObjectNode) calculation.path("variants").path(0).path("edges").path(0).path("depth_profile");
    }

    private List<ImportedOfficialFeature> sources() throws Exception {
        return List.of(source("LINESTRING (500050 6099990,500050 6100010)"));
    }

    private ImportedOfficialFeature source(String wkt) throws Exception {
        return new ImportedOfficialFeature("existing", "heat_network", mapper.createObjectNode().put("diameter", 50),
                new WKTReader().read(wkt));
    }

    private ObjectNode fixture(String cut) {
        List<DepthProfilePoint> points = List.of(point("0","3"), point("40","3"), point("48","3.8"),
                point("52","3.8"), point("60","3"), point("100","3"));
        DepthCrossingDecision decision = new DepthCrossingDecision("existing", "heat_network", "below",
                new BigDecimal("3.8"), new BigDecimal("40"), new BigDecimal("48"), new BigDecimal("52"),
                new BigDecimal("60"), new BigDecimal(".675"), new BigDecimal(".5"));
        BigDecimal spatialLength = BigDecimal.valueOf(84 + 2 * Math.hypot(8, .8));
        // 100 ordinary metres + two triangular excess-depth ramps + four plateau metres + Kspecial.
        BigDecimal weighted = new BigDecimal("100").add(new BigDecimal(".64"))
                .add(new BigDecimal(".32")).add(new BigDecimal(".216"));
        DepthProfileResult profile = new DepthProfileResult(true, points, List.of(decision), List.of(), spatialLength, weighted);
        List<RouteSection> sections = new ArrayList<>();
        if (cut != null && new BigDecimal(cut).compareTo(new BigDecimal("48")) < 0) {
            sections.add(section("base",0,Double.parseDouble(cut))); sections.add(section("base",Double.parseDouble(cut),48));
        } else sections.add(section("base",0,48));
        sections.add(section("special",48,52));
        if (cut != null && new BigDecimal(cut).compareTo(new BigDecimal("52")) > 0) {
            sections.add(section("base",52,Double.parseDouble(cut))); sections.add(section("base",Double.parseDouble(cut),100));
        } else sections.add(section("base",52,100));
        RouteEdge edge = new RouteEdge("edge", "start", "end", 100, List.of(xy(0),xy(100)), sections, BigDecimal.ONE, 50, profile);
        List<RouteNode> nodes = List.of(new RouteNode("start", "new_branch_chamber", xy(0), true,true,0,null),
                new RouteNode("end", "demand_connection", xy(100), false,false,0,null));
        List<RouteConnection> connections = List.of(new RouteConnection("demand","end",BigDecimal.ONE,"connected",null));
        RouteVariant variant = new RouteVariant("one","one",nodes,List.of(edge),connections,edge.getLengthM(),List.of(),List.of(),
                ExistingNetworkReconstructionResult.empty(), calculator.calculate(nodes,List.of(edge),connections,ExistingNetworkReconstructionResult.empty()),1);
        ObjectNode calculation = mapper.createObjectNode().put("input_profile", "baseline_input");
        ObjectNode saved = mapper.valueToTree(variant);
        BigDecimal construction = analyticPrice(cut).add(new BigDecimal("3000000"));
        ((ObjectNode)saved.path("economics")).put("construction_cost", construction).put("calculated_cost",construction)
                .put("score", economics.score(construction,new BigDecimal("100")));
        calculation.putArray("variants").add(saved);
        return calculation;
    }

    private BigDecimal analyticPrice(String cut) {
        TreeSet<BigDecimal> cuts = new TreeSet<>();
        for(String s:List.of("0","40","48","52","60","100")) cuts.add(new BigDecimal(s));
        if (cut!=null) cuts.add(new BigDecimal(cut));
        List<BigDecimal> ordered=new ArrayList<>(cuts);
        BigDecimal result=BigDecimal.ZERO;
        for(int i=1;i<ordered.size();i++) {
            BigDecimal a=ordered.get(i-1), b=ordered.get(i);
            BigDecimal meanK=analyticK(a).add(analyticK(b)).divide(new BigDecimal("2"));
            BigDecimal special=a.compareTo(new BigDecimal("48"))>=0&&b.compareTo(new BigDecimal("52"))<=0
                    ?new BigDecimal("1.05"):BigDecimal.ONE;
            result=result.add(pipes.byDiameter(50).orElseThrow().getNewConstructionRubPerM()
                    .multiply(b.subtract(a)).multiply(meanK).multiply(special).setScale(2,RoundingMode.HALF_UP));
        }
        return result;
    }
    private BigDecimal analyticK(BigDecimal s) {
        if(s.compareTo(new BigDecimal("40"))<=0||s.compareTo(new BigDecimal("60"))>=0) return BigDecimal.ONE;
        if(s.compareTo(new BigDecimal("48"))<0) return BigDecimal.ONE.add(s.subtract(new BigDecimal("40")).multiply(new BigDecimal(".01")));
        if(s.compareTo(new BigDecimal("52"))<=0) return new BigDecimal("1.08");
        return BigDecimal.ONE.add(new BigDecimal("60").subtract(s).multiply(new BigDecimal(".01")));
    }
    private RouteSection section(String kind,double start,double end) {
        return new RouteSection(kind,"special".equals(kind)?"heat_network":null,"special".equals(kind)?"existing":null,
                List.of(xy(start),xy(end)),end-start,null);
    }
    private RouteCoordinate xy(double x) { return new RouteCoordinate(500000+x,6100000); }
    private DepthProfilePoint point(String s,String h) { return new DepthProfilePoint(new BigDecimal(s),new BigDecimal(h)); }
}
