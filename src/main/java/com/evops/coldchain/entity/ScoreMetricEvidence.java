package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标逐项取证（V15）：每快照四行（ON_TIME/TEMP_CONTROL/SIGN_INTEGRITY/EXCEPTION_RESPONSE），
 * 记录分子/分母/指标分、逐批次取值明细 JSON 与来源口径，分数可由取证重算复核。只追加。
 */
@TableName("t_cc_score_metric_evidence")
public class ScoreMetricEvidence extends CreateOnlyEntity {
    private Long snapshotId;
    private Long periodId;
    private Long carrierId;
    private String metricCode;
    private String metricName;
    private Integer numerator;
    private Integer denominator;
    private BigDecimal metricScore;
    private String detailJson;
    private String sourceBasis;
    private LocalDateTime evidenceTime;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public Integer getNumerator() { return numerator; }
    public void setNumerator(Integer numerator) { this.numerator = numerator; }
    public Integer getDenominator() { return denominator; }
    public void setDenominator(Integer denominator) { this.denominator = denominator; }
    public BigDecimal getMetricScore() { return metricScore; }
    public void setMetricScore(BigDecimal metricScore) { this.metricScore = metricScore; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getSourceBasis() { return sourceBasis; }
    public void setSourceBasis(String sourceBasis) { this.sourceBasis = sourceBasis; }
    public LocalDateTime getEvidenceTime() { return evidenceTime; }
    public void setEvidenceTime(LocalDateTime evidenceTime) { this.evidenceTime = evidenceTime; }
}
