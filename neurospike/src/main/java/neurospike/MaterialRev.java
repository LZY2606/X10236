package neurospike;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;

/**
 * One immutable version of one material item. contentHash covers the material
 * only; versionId is stable across export/import (it depends on key+version).
 * The payload type is tagged because a snapshot mixes waveform/detection/
 * feature/label revisions in the same logical stores.
 */
@JsonSerialize(using = MaterialRev.Serializer.class)
@JsonDeserialize(using = MaterialRev.Deserializer.class)
public record MaterialRev<T>(String versionId, String key, int version,
                             String introducedByEvent, String contentHash, T material) {

    public static final String WAVEFORM = "WaveformRef";
    public static final String DETECTION = "Detection";
    public static final String FEATURE_SPEC = "FeatureVersionSpec";
    public static final String LABEL_SET = "LabelSet";

    public static class Serializer extends JsonSerializer<MaterialRev<?>> {
        @Override
        public void serialize(MaterialRev<?> value, com.fasterxml.jackson.core.JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            String type = typeOf(value.material());
            gen.writeStartObject();
            gen.writeStringField("versionId", value.versionId());
            gen.writeStringField("key", value.key());
            gen.writeNumberField("version", value.version());
            gen.writeStringField("introducedByEvent", value.introducedByEvent());
            gen.writeStringField("contentHash", value.contentHash());
            gen.writeStringField("materialType", type);
            gen.writeFieldName("material");
            gen.writeObject(value.material());
            gen.writeEndObject();
        }

        private String typeOf(Object o) {
            if (o instanceof WaveformRef) {
                return WAVEFORM;
            }
            if (o instanceof Detection) {
                return DETECTION;
            }
            if (o instanceof FeatureVersionSpec) {
                return FEATURE_SPEC;
            }
            if (o instanceof LabelSet) {
                return LABEL_SET;
            }
            throw new IllegalStateException("unknown material type: " + o.getClass());
        }
    }

    public static class Deserializer extends JsonDeserializer<MaterialRev<?>> {
        @Override
        public MaterialRev<?> deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonNode node = p.readValueAsTree();
            String type = node.path("materialType").asText();
            Object material = switch (type) {
                case WAVEFORM -> Json.MAPPER.treeToValue(node.get("material"), WaveformRef.class);
                case DETECTION -> Json.MAPPER.treeToValue(node.get("material"), Detection.class);
                case FEATURE_SPEC -> Json.MAPPER.treeToValue(node.get("material"),
                        FeatureVersionSpec.class);
                case LABEL_SET -> Json.MAPPER.treeToValue(node.get("material"), LabelSet.class);
                default -> throw new IOException("unknown materialType " + type);
            };
            return new MaterialRev<>(node.get("versionId").asText(),
                    node.get("key").asText(), node.get("version").asInt(),
                    node.get("introducedByEvent").asText(),
                    node.get("contentHash").asText(), material);
        }
    }
}
