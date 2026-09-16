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

    OfficialExportPayload(
            OfficialGeoJsonExporter exporter,
            JsonNode calculation,
            List<ImportedOfficialFeature> inputFeatures) {
        this.exporter = exporter;
        this.calculation = calculation;
        this.inputFeatures = List.copyOf(inputFeatures);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        exporter.writeValidated(calculation, inputFeatures, outputStream);
    }
}
