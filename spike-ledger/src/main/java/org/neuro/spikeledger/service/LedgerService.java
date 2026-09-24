package org.neuro.spikeledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neuro.spikeledger.model.Diagnostic;
import org.neuro.spikeledger.model.Entities.Adjudication;
import org.neuro.spikeledger.model.Entities.ClusteringRun;
import org.neuro.spikeledger.model.Entities.Unit;
import org.neuro.spikeledger.model.Evidence;
import org.neuro.spikeledger.model.Ids;
import org.neuro.spikeledger.model.ImportResult;
import org.neuro.spikeledger.store.Db;
import org.neuro.spikeledger.store.DerivedStore;
import org.neuro.spikeledger.store.EventStore;
import org.neuro.spikeledger.store.MaterialStore;
import org.neuro.spikeledger.util.J;

/**
 * 应用服务边界：所有业务规则（校验、聚类、局部失效、冲突、时间旅行）
 * 都在服务端，可被测试直接调用，不依赖浏览器。
 */
public class LedgerService {
    public static final String MAIN_BRANCH = "br_main";

    private final Db db;
    private final EventStore events;
    private final MaterialStore material;
    private final DerivedStore derived;
    private long clock = 1_700_000_000_000L;

    public LedgerService(Db db) {
        this.db = db;
        this.events = new EventStore(db);
        this.material = new MaterialStore(db);
        this.derived = new DerivedStore(db);
        events.ensureBranch(MAIN_BRANCH, "main");
    }

    public EventStore events() {
        return events;
    }

    public MaterialStore material() {
        return material;
    }

    public DerivedStore derived() {
        return derived;
    }

    /** 可注入的单调时钟（重放等价：同一脚本 -> 同一时间戳）。 */
    public void setClock(long t) {
        this.clock = t;
    }

    public long tick() {
        return clock++;
    }

    // ---------- 事件追加 ----------

    public EventStore.Event appendMaterial(String type, Object payload, String inputDigest,
                                           String paramsVersion) {
        String head = events.head(MAIN_BRANCH);
        String id = Ids.derived("ev", (head == null ? "" : head) + "|" + type + "|"
                + HashingDigest(payload));
        EventStore.Event e = events.append(MAIN_BRANCH, id, head, type, J.to(payload),
                inputDigest, paramsVersion, tick());
        db.commit();
        return e;
    }

    public EventStore.Event appendRecompute(Object payload, String inputDigest,
                                            String paramsVersion) {
        String head = events.head(MAIN_BRANCH);
        String id = Ids.derived("ev", head + "|recompute|" + HashingDigest(payload));
        EventStore.Event e = events.append(MAIN_BRANCH, id, head, "RECOMPUTE", J.to(payload),
                inputDigest, paramsVersion, tick());
        db.commit();
        return e;
    }

    private static String HashingDigest(Object payload) {
        return org.neuro.spikeledger.model.Hashing.sha256Hex(J.to(payload));
    }
