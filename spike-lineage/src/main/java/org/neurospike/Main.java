package org.neurospike;

import org.neurospike.adjudicate.AdjudicationService;
import org.neurospike.ingest.CaseSource;
import org.neurospike.ingest.IngestResult;
import org.neurospike.ingest.IngestService;
import org.neurospike.ingest.ResourceBudget;
import org.neurospike.store.ProjectStore;
import org.neurospike.web.HttpServer;

import java.nio.file.Files;
import java.nio.file.Path;

/** 离线应用入口：打开/初始化本地 SQLite，导入内置领域案例包，启动审阅服务。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5350;
        String dbPath = "spike-lineage.db";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db" -> dbPath = args[++i];
                default -> { }
            }
        }
        boolean fresh = Files.notExists(Path.of(dbPath))
                || Files.notExists(Path.of(dbPath + "-wal")) && Files.size(Path.of(dbPath)) == 0;
        ProjectStore store = new ProjectStore(dbPath);
        store.recoverPendingTransactions();

        if (store.decision().meta("case_id") == null) {
            IngestResult r = new IngestService().importCase(store,
                    CaseSource.classpath("/casepack/pulse-cluster-lineage"),
                    ResourceBudget.defaultBudget());
            if (!r.success()) {
                throw new IllegalStateException("built-in case import failed: "
                        + r.failure().type() + " " + r.failure().diagnostics());
            }
            System.out.printf("imported %d events (%d invalid rows retained in place)%n",
                    r.importedEvents(), r.retainedRows());
            new AdjudicationService(store).baseline();
        }
        System.out.println("review page: http://" + host + ":" + port);
        new HttpServer(store).start(host, port);
    }
}
