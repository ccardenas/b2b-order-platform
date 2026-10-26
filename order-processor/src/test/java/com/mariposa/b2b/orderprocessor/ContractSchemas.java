package com.mariposa.b2b.orderprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Valida contra los JSON Schema de contracts/ (la fuente unica de contratos), no contra una copia. */
public final class ContractSchemas {

    public static final Path CONTRACTS = Path.of("..", "contracts");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContractSchemas() {
    }

    /** @return los errores de validacion; vacio si el documento cumple el esquema */
    public static Set<String> violations(String schemaName, byte[] json) {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (InputStream schemaFile = Files.newInputStream(CONTRACTS.resolve("events").resolve(schemaName + ".schema.json"))) {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaFile, config);
            JsonNode document = MAPPER.readTree(json);
            return schema.validate(document).stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] fixture(String relativePath) {
        try {
            return Files.readAllBytes(CONTRACTS.resolve("fixtures").resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
