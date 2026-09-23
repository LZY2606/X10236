# 脉冲簇谱 · 神经棘波候选、漂移簇与人工裁定谱系

面向神经电生理团队的本地审阅应用：导入多通道检测事件、波形片段和记录时间，
为单元聚类建立可回溯的合并、拆分和漂移证据。无账号、无云端、无外部服务。

## 运行

```bash
./gradlew build -x test        # 安装依赖并编译
./gradlew test                 # 25 个验收测试
./gradlew run --args='--host 127.0.0.1 --port 5350'
# 打开 http://127.0.0.1:5350 即进入带样例的审阅页
```

## 分层

- **不可变材料**（分开存储）：`waveform_refs` / `probe_geometries` / `detection_params`
  / `feature_sets` / `cluster_labels` —— 见 `src/main/java/gsb/spikesort/store/Database.java`
- **可修改决定**（追加式）：`adjudication_events`（合并/拆分/改窗/参数修订/冲突，含分支与状态）
- **派生产物**：`artifacts` 记录输入摘要、参数版本、父级依赖、有效区间与失效原因
- **证据**：`evidence_events` 机器可读（不应期违例、幅度漂移、多通道传播均标记
  `rule=evidence_only`、`clusterIndexIsIdentity=false`）

## 关键规则

- 聚类序号不是稳定身份；稳定身份在 `units.id`，改号只追加新标签
- 修改时间窗只重算与窗口边缘相交的簇（`AdjudicationService.applyWindowChange`）
- 拆分后无法唯一决定的棘波保留全部合法候选（`candidateUnitIds`）
- 冲突裁定双方都保留：先到的 `adopted`，后到的 `conflicted`
- 资源预算耗尽 / 格式损坏 / 语义无解是三种不同的 `IngestStatus`
- 导入不完整 → 带位置的诊断，不用空值或默认值伪装成功
- 导出→删除本地库→导入：身份、顺序、分支与决定历史保持不变；导入单事务，失败整体回滚

## 案例包

`src/main/resources/casepack/pulse-cluster-spectrum.json`：初始导入（四类材料分开存储）
→ 参数修订 → 局部源数据改动 → 改窗 → 聚类改号 → 拆分共享事件 → 冲突裁定，
全部通过真实服务重放，可从最终状态反查每条差异的材料、规则版本与决定。

## 测试

`src/test/java/gsb/spikesort/`：并发棘波、幅度漂移、改号、拆分共享、时间窗端点、
裁定冲突、局部失效、导出导入往返、半写恢复、确定性顺序、时间回溯。
