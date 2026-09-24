package neurogsb;

import neurogsb.db.Database;
import neurogsb.service.CaseLoader;
import neurogsb.web.Server;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5350;
        String dbPath = "data/neuro-gsb.db";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> dbPath = args[i + 1];
            }
        }
        if (!":memory:".equals(dbPath)) Files.createDirectories(Path.of(dbPath).getParent());
        Database db = new Database(dbPath);
        var recovered = db.recover();
        recovered.forEach(r -> System.out.println("[recover] " + r));
        CaseLoader loader = new CaseLoader(db);
        if (loader.isEmpty()) {
            System.out.println("[casepack] 载入离线案例包：脉冲簇谱");
            loader.load().forEach(l -> System.out.println("[casepack] " + l));
        }
        Server server = new Server(db);
        server.create().start(host, port);
        System.out.println("[server] http://" + host + ":" + port);
    }
}
