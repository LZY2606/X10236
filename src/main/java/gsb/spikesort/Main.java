package gsb.spikesort;

import gsb.spikesort.domain.CasePackLoader;
import gsb.spikesort.server.Server;
import gsb.spikesort.store.Database;
import gsb.spikesort.store.Store;

import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point: local-only review server for the pulse-cluster-spectrum case. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5350;
        String dbPath = "data/spikesort.db";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> dbPath = args[i + 1];
                default -> { }
            }
        }
        Files.createDirectories(Path.of(dbPath).getParent() == null
                ? Path.of(".") : Path.of(dbPath).getParent());

        Database db = Database.open(dbPath);
        Store store = new Store(db);
        if (store.units().isEmpty()) {
            var report = new CasePackLoader(store).load();
            System.out.println("case pack loaded: " + report.ingest().imported()
                    + " rows, " + report.artifacts() + " artifacts, "
                    + report.decisionEventIds().size() + " scripted decisions");
        }
        Server server = new Server(store, () -> {
            // reset = wipe all tables and replay the case pack
            store.begin();
            try {
                for (String t : store.tableNames()) store.clearTable(t);
                store.commit();
            } catch (Exception e) {
                store.rollbackQuietly();
                throw e;
            }
            new CasePackLoader(store).load();
        });
        server.create().start(host, port);
        System.out.println("review page: http://" + host + ":" + port);
    }
}
