package com.evops.coldchain.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 外部温控监测平台依赖（V15）。
 *
 * 评分周期关闭做温控合格率取证前必须先通过该依赖：
 * - {@link #checkHealth()} 无响应/不健康 → 关闭整体阻断，不产出快照、不假装成功；
 * - {@link #fetchTempVerdicts(List)} 必须返回每个待评批次的客观判定，缺一个都视为数据不完整→阻断。
 * 集成测试用脚本替身确定性制造“无响应→恢复”，无需真实网络（同 V6 通知通道测试口径）。
 */
public interface TempMonitorDependency {

    /** 依赖健康检查：healthy=FALSE 时 reason 必填可读原因 */
    HealthResult checkHealth();

    /**
     * 拉取批次温控客观判定（合格/不合格）。
     * 实现必须保证“无响应抛异常、返回缺批次抛异常”，调用方据此拒绝默认成功。
     *
     * @param batchIds 本周期全部待评已签收批次
     * @return 每批次是否温控合格（key=批次ID）；返回映射缺批次时调用方按不可用处理
     */
    TempVerdictResult fetchTempVerdicts(List<Long> batchIds);

    class HealthResult {
        private final boolean healthy;
        private final String reason;
        private final LocalDateTime checkedAt;

        private HealthResult(boolean healthy, String reason, LocalDateTime checkedAt) {
            this.healthy = healthy;
            this.reason = reason;
            this.checkedAt = checkedAt;
        }

        public static HealthResult ok(LocalDateTime at) {
            return new HealthResult(true, null, at);
        }

        public static HealthResult down(String reason, LocalDateTime at) {
            return new HealthResult(false, reason, at);
        }

        public boolean isHealthy() { return healthy; }
        public String getReason() { return reason; }
        public LocalDateTime getCheckedAt() { return checkedAt; }
    }

    class TempVerdictResult {
        private final Map<Long, Boolean> qualifiedByBatch;
        private final LocalDateTime fetchedAt;
        private final String rawBasis;

        public TempVerdictResult(Map<Long, Boolean> qualifiedByBatch, LocalDateTime fetchedAt, String rawBasis) {
            this.qualifiedByBatch = qualifiedByBatch;
            this.fetchedAt = fetchedAt;
            this.rawBasis = rawBasis;
        }

        public Map<Long, Boolean> getQualifiedByBatch() { return qualifiedByBatch; }
        public LocalDateTime getFetchedAt() { return fetchedAt; }
        public String getRawBasis() { return rawBasis; }
    }
}
