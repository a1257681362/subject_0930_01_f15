package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 周期关闭尝试留痕（V15）：外部依赖无响应时记 BLOCKED 与原因（不产出快照、不假装成功），
 * 恢复后成功关闭记 CLOSED。每次尝试独立一行，只追加。
 */
@TableName("t_cc_score_close_attempt")
public class ScoreCloseAttempt extends CreateOnlyEntity {
    private Long periodId;
    private LocalDateTime attemptTime;
    /** BLOCKED / CLOSED */
    private String result;
    private String blockedReason;
    private Integer snapshotCount;
    /** SCHEDULED / MANUAL */
    private String triggerSource;

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public LocalDateTime getAttemptTime() { return attemptTime; }
    public void setAttemptTime(LocalDateTime attemptTime) { this.attemptTime = attemptTime; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public Integer getSnapshotCount() { return snapshotCount; }
    public void setSnapshotCount(Integer snapshotCount) { this.snapshotCount = snapshotCount; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
}
