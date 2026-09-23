package org.neuroreview.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new NeuroException(FailureKind.CORRUPT_FORMAT,
                    "案例包不是合法 JSON: " + e.getOriginalLocation(),
                    List.of("$"));
        }
    }

    public static JsonNode read(Path path) {
        try {
            return read(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new NeuroException(FailureKind.CORRUPT_FORMAT,
                    "无法读取案例包: " + path, List.of(path.toString()));
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path path, Object value) {
        try {
            Files.writeString(path, write(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
