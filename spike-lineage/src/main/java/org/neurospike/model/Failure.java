package org.neurospike.model;

import java.util.List;

/**
 * 三种必须可区分的失败结果：
 *  - RESOURCE_EXHAUSTED 资源预算耗尽
 *  - CORRUPT_FORMAT    格式损坏（结构/校验和层面，无法定位语义）
 *  - SEMANTIC_UNRESOLVABLE 语义无解（结构可读但业务上无唯一合法结论）
 */
public enum FailureType { RESOURCE_EXHAUSTED, CORRUPT_FORMAT, SEMANTIC_UNRESOLVABLE }
