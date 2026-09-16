package ru.lct.heatroute.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ContractSchemaSupport {
    private ContractSchemaSupport() {
    }

    public static Schema load(String resourceName) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema = registry.getSchema(readResource("/contracts/" + resourceName));
        schema.initializeValidators();
        return schema;
    }

    public static List<com.networknt.schema.Error> validate(Schema schema, JsonNode value) {
        return schema.validate(value.toString(), InputFormat.JSON);
    }

    public static String readResource(String path) {
        try (InputStream input = ContractSchemaSupport.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read test resource " + path, exception);
        }
    }
}
