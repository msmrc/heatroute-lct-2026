package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

public final class OfficialExportPayload {
    private final OfficialGeoJsonExporter exporter;
    private final JsonNode calculation;
    private final List<ImportedOfficialFeature> inputFeatures;
    private final String variantId;

    OfficialExportPayload(
            OfficialGeoJsonExporter exporter,
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures,
            String variantId) {
        this.exporter = exporter;
        this.calculation = calculation;
        this.inputFeatures = List.copyOf(inputFeatures);
        this.variantId = variantId;
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        if (variantId == null) {
            exporter.writeValidated(calculation, inputFeatures, outputStream);
        } else {
            exporter.writeValidatedVariant(calculation, inputFeatures, variantId, outputStream);
        }
    }
}
