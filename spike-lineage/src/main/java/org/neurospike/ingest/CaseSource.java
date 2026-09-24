package org.neurospike.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 案例包来源：文件系统目录或 classpath（jar 内 /casepack/...）。 */
public interface CaseSource {
    byte[] read(String relativePath) throws IOException;
    default String readUtf8(String relativePath) throws IOException {
        return new String(read(relativePath), StandardCharsets.UTF_8);
    }

    static CaseSource directory(Path root) {
        return rel -> Files.readAllBytes(root.resolve(rel));
    }

    static CaseSource classpath(String baseResource) {
        return rel -> {
            String name = baseResource + (baseResource.endsWith("/") ? "" : "/") + rel;
            try (InputStream in = CaseSource.class.getResourceAsStream(name)) {
                if (in == null) throw new IOException("case resource missing: " + name);
                return in.readAllBytes();
            }
        };
    }
}
