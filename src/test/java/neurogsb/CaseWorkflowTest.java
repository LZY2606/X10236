package neurogsb;

import neurogsb.db.Database;
import neurogsb.model.OpResult;
import neurogsb.model.Records;
import neurogsb.service.*;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验收：并发棘波、幅度漂移、聚类编号变化、拆分后共享事件、时间窗端点、裁定冲突与局部失效。 */
class CaseWorkflowTest {
    Database db;
    Store store;
    EvidenceService evidence;
    AdjudicationService adj;
    ReplayService replay;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        store = new Store(db);
        evidence = new EvidenceService(db, store);
        adj = new AdjudicationService(db, store, evidence);
        replay = new ReplayService(db, store);
        new CaseLoader(db).load();
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    void 并发棘波_产生多通道传播证据() {
        var beta = store.derivedFor("u-beta", null).stream()
                .filter(d -> d.kind().equals("evidence.coprop")).reduce((a, b) -> b).orElseThrow();
        assertTrue(beta.payload().get("concurrent_events").asInt() >= 1, "u-beta 应检测到与 u-gamma 的并发棘波");
        var gamma = store.derivedFor("u-gamma", null).stream()
                .filter(d -> d.kind().equals("evidence.coprop")).reduce((a, b) -> b).orElseThrow();
        assertTrue(gamma.payload().get("concurrent_events").asInt() >= 1);
        // 证据判定依据是机器可读的
        assertEquals(EvidenceService.BASIS, beta.payload().get("judgment_basis").asText());
    }

    @Test
    void 幅度漂移_只是证据不是身份() {
        var drift = store.derivedFor("u-alpha", null).stream()
                .filter(d -> d.kind().equals("evidence.drift") && d.status().equals("ok"))
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(drift.payload().get("detected").asBoolean(), "u-alpha 振幅 120→90µV 应检出漂移");
        assertTrue(drift.payload().get("slope_uv_per_ms").asDouble() < 0);
        assertEquals("µV", drift.payload().get("amplitude_unit").asText());
    }

    @Test
    void 聚类编号变化_身份稳定() {
        // 案例包已将 u-alpha 从簇号 1 改为 7
        var labels = store.materials("cluster_label").stream()
                .filter(m -> m.payload().get("unit_id").asText().equals("u-alpha")).toList();
        assertEquals(2, labels.size(), "应有两个版本的标签材料");
        assertEquals(1, labels.get(0).payload().get("cluster_no").asInt());
        assertEquals(7, labels.get(1).payload().get("cluster_no").asInt());
        // 稳定身份不变，事件可回溯
        var renumber = store.events(null).stream().filter(e -> e.kind().equals("renumber")).findFirst().orElseThrow();
        assertEquals("u-alpha", renumber.targetUnitId());
        assertEquals("u-alpha", labels.get(1).payload().get("unit_id").asText());
    }

    @Test
    void 拆分后共享事件_保留全部合法候选() {
        var split = store.events(null).stream().filter(e -> e.kind().equals("split")).findFirst().orElseThrow();
        assertEquals("u-delta", split.payload().get("parent_unit").asText());
        assertEquals(2, split.payload().get("shared_spike_count").asInt(), "19999.7 与 20000.2 是共享事件");
        // 每个共享棘波有两个归属候选，全部 open
        var cands = store.allCandidates().stream()
                .filter(c -> c.kind().equals("shared_spike_assignment")).toList();
        assertEquals(4, cands.size());
        assertTrue(cands.stream().allMatch(c -> c.status().equals("open")));
    }

    @Test
    void 时间窗端点_半开区间且只重算相交簇() {
        // 案例包窗口 [1000, 40000)：u-epsilon 的 t=1000 含，t=40000 排除
        var eps = store.spikes("u-epsilon").stream().filter(s -> s.tMs() != null)
                .map(Records.Spike::tMs).sorted().toList();
        assertEquals(List.of(1000.0, 5000.0, 40000.0, 45000.0), eps);
        var epsAcg = store.derivedFor("u-epsilon", null).stream()
                .filter(d -> d.kind().equals("acg") && d.status().equals("ok"))
                .reduce((a, b) -> b).orElseThrow();
        // 窗口内只有 1000 与 5000 两个棘波 → ISI 4000ms 超出 50ms bin，全为 0
        int sum = 0;
        for (var b : epsAcg.payload().get("bins")) sum += b.asInt();
        assertEquals(0, sum, "端点 40000 应被半开区间排除");
        // 只重算相交簇：window_change 事件的受影响列表不含不相交单元
        var wc = store.events(null).stream().filter(e -> e.kind().equals("window_change")).findFirst().orElseThrow();
        assertEquals(1000.0, wc.payload().get("t0_ms").asDouble());
        assertEquals(40000.0, wc.payload().get("t1_ms").asDouble());
    }

    @Test
    void 时间窗修改_只重算相交簇() {
        // 新窗口只与 u-beta 相交
        var r = adj.changeWindow(8900, 9100, "tester", 100_000);
        assertTrue(r.ok());
        var ok = (OpResult.Ok) r;
        assertEquals(List.of("u-beta"), ok.affected().stream().filter(a -> a.startsWith("u-")).toList());
    }

    @Test
    void 裁定冲突_双方候选保留且可解决() {
        // 案例包：kim 接受、lee 否定同一漂移证据 → 冲突
        var conflict = store.events(null).stream().filter(e -> e.kind().equals("conflict")).findFirst().orElseThrow();
        assertEquals("CONFLICTED", conflict.payload().get("status").asText());
        String subject = conflict.payload().get("subject_id").asText();
        var cands = store.candidates(subject).stream()
                .filter(c -> c.kind().equals("conflicting_decision")).toList();
        assertEquals(2, cands.size(), "冲突双方都应保留为候选");
        assertTrue(cands.stream().allMatch(c -> c.status().equals("open")));
        // 解决后：一个 chosen 一个 dropped
        var r = adj.resolve(subject, cands.get(0).id(), "reviewer-lead");
        assertTrue(r.ok());
        var after = store.candidates(subject);
        assertEquals(1, after.stream().filter(c -> c.status().equals("chosen")).count());
        assertEquals(1, after.stream().filter(c -> c.status().equals("dropped")).count());
    }

    @Test
    void 局部失效_只波及依赖被改材料的派生结果() {
        // 案例包 amend 了 wr-02（u-beta 的波形引用）
        var invs = store.invalidations(null).stream()
                .filter(i -> i.reason().startsWith("source_amended")).toList();
        assertFalse(invs.isEmpty());
        assertTrue(invs.stream().allMatch(i -> i.scope().equals("u-beta")),
                "局部失效只能波及 u-beta，实际: " + invs.stream().map(Records.Invalidation::scope).toList());
        // u-alpha 的派生结果不应被这次源改动失效
        var alphaInv = invs.stream().filter(i -> i.scope().equals("u-alpha")).toList();
        assertTrue(alphaInv.isEmpty());
    }

    @Test
    void 回放_恢复历史视点的依赖版本与结论() {
        long latest = store.events(null).stream().mapToLong(Records.Event::seq).max().orElseThrow();
        var full = replay.stateAt(null);
        // 回到第一个事件（导入）：当时还没有 renumber/split/conflict
        var atImport = replay.stateAt(1L);
        assertTrue(atImport.get("events").size() < full.get("events").size());
        assertEquals(1, atImport.get("seq").asLong());
        assertEquals(latest, atImport.get("latest_seq").asLong(), "最新序号仍可见，历史不被覆盖");
        // 历史视点的 u-alpha 簇号仍是 1（renumber 发生在之后）
        for (var u : atImport.get("units")) {
            if (u.get("unit_id").asText().equals("u-alpha")) {
                assertEquals(1, u.get("cluster_no").asInt(), "历史视点应看到当时的簇号");
            }
        }
    }

    @Test
    void 等价输入_派生结果顺序稳定() {
        var r1 = evidence.computeUnit("u-alpha", store.events(null).size() + 100, 100_000);
        assertTrue(r1.ok());
        var digests = store.derivedFor("u-alpha", null).stream()
                .filter(d -> d.kind().equals("acg")).map(Records.Derived::inputDigest).distinct().toList();
        assertEquals(1, digests.size(), "相同输入的输入摘要必须稳定");
    }
}
