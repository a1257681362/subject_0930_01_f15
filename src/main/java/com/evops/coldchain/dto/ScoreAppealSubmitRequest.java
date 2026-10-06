package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 评分申诉提交请求（V15）：一次申诉针对一个快照指标；申诉期内提交，原分不回写 */
public class ScoreAppealSubmitRequest {
    @NotNull(message = "评分快照ID不能为空")
    private Long snapshotId;
    @NotBlank(message = "被申诉指标不能为空")
    private String metricCode;
    @NotBlank(message = "申诉理由不能为空")
    private String reason;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
