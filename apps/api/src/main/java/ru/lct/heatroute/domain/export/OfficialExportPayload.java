package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

public final class OfficialExportPayload {
    private final OfficialGeoJsonExporter exporter;
    private final JsonNode calculation;
    private final List<ImportedOfficialFeature> inputFeatures;
    private final String variantId;
    private final OfficialRunParameters parameters;

    OfficialExportPayload(
            OfficialGeoJsonExporter exporter,
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String variantId,
            OfficialRunParameters parameters) {
        this.exporter = exporter;
        this.calculation = calculation;
        this.inputFeatures = List.copyOf(inputFeatures);
        this.variantId = variantId;
        this.parameters = Objects.requireNonNull(parameters, "Saved run parameters are required");
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        if (variantId == null) {
            exporter.writeValidated(calculation, inputFeatures, parameters, outputStream);
        } else {
            exporter.writeValidatedVariant(calculation, inputFeatures, variantId, parameters, outputStream);
        }
    }
}
