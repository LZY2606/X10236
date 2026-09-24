package org.neurospike.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neurospike.model.Diagnostic;
import org.neurospike.model.FailureResult;
import org.neurospike.model.FailureType;

import java.io.IOException;
import java.util.List;

/** 读取并校验案例清单。清单本身损坏 = CORRUPT_FORMAT。 */
public final class ManifestReader {
    private final ObjectMapper json = new ObjectMapper();

    public sealed interface Loaded {
        record Ok(CaseManifest manifest) implements Loaded {}
        record Failed(FailureResult failure) implements Loaded {}
    }

    public Loaded load(CaseSource source) {
        byte[] bytes;
        try {
            bytes = source.read("manifest.json");
        } catch (IOException e) {
            return new Loaded.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                    List.of(Diagnostic.of("corrupt-manifest-missing", "manifest.json",
                            e.getMessage()))));
        }
        try {
            CaseManifest m = json.readValue(bytes, CaseManifest.class);
            if (m.caseId() == null || m.probeId() == null || m.files() == null
                    || m.timeBasis() == null) {
                return new Loaded.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                        List.of(Diagnostic.of("corrupt-manifest-fields", "manifest.json",
                                "required fields missing"))));
            }
            return new Loaded.Ok(m);
        } catch (IOException e) {
            return new Loaded.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                    List.of(Diagnostic.of("corrupt-manifest-json", "manifest.json",
                            e.getOriginalMessage() == null ? e.getMessage() : e.getOriginalMessage()))));
        }
    }
}
