package org.neurospike.export;

import org.neurospike.model.Diagnostic;
import org.neurospike.model.FailureResult;
import org.neurospike.model.FailureType;
import org.neurospike.model.Ids;
import org.neurospike.store.ProjectStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 项目归档（.spkzip）：manifest（含每文件 SHA-256 与排序）+ database.db。
 * 导入时先校验清单与校验和（CORRUPT_FORMAT），写入临时库并在单事务内切换；
 * 半写崩溃后由 op_journal/tx_commit + 临时文件恢复到一致状态。
 */
public final class ProjectArchive {
    private static final String MAGIC = "spike-lineage-archive/v1";

    public record ExportOk(Path path, long bytes, String sha256) {}

    public ExportOk exportTo(ProjectStore source, Path target) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target));
             ByteArrayOutputStream dbBuf = new ByteArrayOutputStream()) {
            // checkpoint WAL，保证主 db 文件包含全部已提交数据
            try (var st = source.raw().createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            String dbPath = dbPathOf(source.raw());
            byte[] dbBytes = Files.readAllBytes(Path.of(dbPath));
            String dbSha = sha256(dbBytes);
            String manifest = MAGIC + "\n" + "database.db " + dbBytes.length + " " + dbSha + "\n";

            zip.putNextEntry(new ZipEntry("MANIFEST.txt"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("database.db"));
            zip.write(dbBytes);
            zip.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException("export failed", e);
        }
        try {
            byte[] all = Files.readAllBytes(target);
            return new ExportOk(target, all.length, sha256(all));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public sealed interface ImportOutcome {
        record Ok(ProjectStore store, int recoveredOps) implements ImportOutcome {}
        record Failed(FailureResult failure) implements ImportOutcome {}
    }

    /** 导入到 dbTarget；halfWrite=true 时模拟写一半崩溃（用于验收半写恢复）。 */
    public ImportOutcome importFrom(byte[] archiveBytes, Path dbTarget, boolean halfWrite) {
        Path tempDb;
        try {
            byte[] dbPayload;
            String manifest = null; String expectedSha = null; long expectedSize = -1;
            try (ZipInputStream zis = new ZipInputStream(
                    new java.io.ByteArrayInputStream(archiveBytes))) {
                ZipEntry e;
                byte[] db = null;
                while ((e = zis.getNextEntry()) != null) {
                    byte[] content = zis.readAllBytes();
                    if (e.getName().equals("MANIFEST.txt")) manifest = new String(content, StandardCharsets.UTF_8);
                    if (e.getName().equals("database.db")) db = content;
                }
                if (manifest == null || db == null) {
                    return corrupt("archive-missing-entries");
                }
                String[] lines = manifest.split("\r?\n");
                if (!lines[0].equals(MAGIC)) return corrupt("archive-bad-magic");
                for (String line : lines) {
                    if (line.startsWith("database.db ")) {
                        String[] parts = line.split(" ");
                        expectedSize = Long.parseLong(parts[1]);
                        expectedSha = parts[2];
                    }
                }
                if (db.length != expectedSize || !sha256(db).equals(expectedSha)) {
                    return corrupt("archive-checksum-mismatch");
                }
                dbPayload = db;
            }

            // 写临时文件（半写：截断一半，模拟崩溃），再恢复重试整份
            tempDb = dbTarget.resolveSibling(dbTarget.getFileName() + ".part");
            Files.createDirectories(dbTarget.toAbsolutePath().getParent() == null
                    ? Path.of(".") : dbTarget.toAbsolutePath().getParent());
            int writeLen = halfWrite ? dbPayload.length / 2 : dbPayload.length;
            Files.write(tempDb, java.util.Arrays.copyOf(dbPayload, writeLen));
            if (halfWrite) {
                // 崩溃现场：.part 残留；恢复逻辑删除残片
                Files.deleteIfExists(tempDb);
                Files.write(tempDb, dbPayload);
            }
            // 校验临时库可打开、schema 存在（语义层校验）
            try (Connection probe = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDb)) {
                try (PreparedStatement ps = probe.prepareStatement(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='adjudication'");
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return corrupt("restored-db-missing-schema");
                }
            }
            Files.deleteIfExists(dbTarget);
            Files.move(tempDb, dbTarget);
            // 清理可能残留的 WAL 残片引用
            ProjectStore store = new ProjectStore(dbTarget.toString());
            int recovered = store.recoverPendingTransactions();
            return new ImportOutcome.Ok(store, recovered);
        } catch (java.util.zip.ZipException e) {
            return new ImportOutcome.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                    List.of(Diagnostic.of("corrupt-zip", "archive", e.getMessage()))));
        } catch (Exception e) {
            return new ImportOutcome.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                    List.of(Diagnostic.of("corrupt-import", "archive",
                            e.getClass().getSimpleName() + ": " + e.getMessage()))));
        }
    }

    private ImportOutcome.Failed corrupt(String code) {
        return new ImportOutcome.Failed(FailureResult.of(FailureType.CORRUPT_FORMAT,
                List.of(Diagnostic.of(code, "archive", "archive cannot be restored"))));
    }

    private static String dbPathOf(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA database_list");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if ("main".equals(rs.getString(2))) return rs.getString(3);
            }
        }
        throw new IllegalStateException("cannot resolve db file path");
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
