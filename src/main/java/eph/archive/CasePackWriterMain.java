package eph.archive;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dev tool: regenerates src/main/resources/casepack/spike-dossier-offline-case.json. */
public final class CasePackWriterMain {
    private CasePackWriterMain() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/casepack/spike-dossier-offline-case.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, CasePackGenerator.toJson(CasePackGenerator.build()));
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
