package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承运商评分快照（V15）：周期关闭时每承运商一行，只 INSERT 不可变。
 * 申诉/复核结论绝不回写本表；申诉成立的修正值在视图层并列展示。
 */
@TableName("t_cc_score_snapshot")
public class ScoreSnapshot extends CreateOnlyEntity {
    private Long periodId;
    private String periodCode;
    private Long carrierId;
    private String carrierNoSnap;
    private String carrierNameSnap;
    private Long ruleId;
    private Integer ruleVersionNo;
    private BigDecimal onTimeScore;
    private BigDecimal tempControlScore;
    private BigDecimal signIntegrityScore;
    private BigDecimal exceptionResponseScore;
    private BigDecimal totalScore;
    private Integer batchCount;
    private String snapshotJson;
    private String snapshotSha256;
    private LocalDateTime frozenTime;

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public String getPeriodCode() { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNoSnap() { return carrierNoSnap; }
    public void setCarrierNoSnap(String carrierNoSnap) { this.carrierNoSnap = carrierNoSnap; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public BigDecimal getOnTimeScore() { return onTimeScore; }
    public void setOnTimeScore(BigDecimal onTimeScore) { this.onTimeScore = onTimeScore; }
    public BigDecimal getTempControlScore() { return tempControlScore; }
    public void setTempControlScore(BigDecimal tempControlScore) { this.tempControlScore = tempControlScore; }
    public BigDecimal getSignIntegrityScore() { return signIntegrityScore; }
    public void setSignIntegrityScore(BigDecimal signIntegrityScore) { this.signIntegrityScore = signIntegrityScore; }
    public BigDecimal getExceptionResponseScore() { return exceptionResponseScore; }
    public void setExceptionResponseScore(BigDecimal exceptionResponseScore) { this.exceptionResponseScore = exceptionResponseScore; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public Integer getBatchCount() { return batchCount; }
    public void setBatchCount(Integer batchCount) { this.batchCount = batchCount; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getSnapshotSha256() { return snapshotSha256; }
    public void setSnapshotSha256(String snapshotSha256) { this.snapshotSha256 = snapshotSha256; }
    public LocalDateTime getFrozenTime() { return frozenTime; }
    public void setFrozenTime(LocalDateTime frozenTime) { this.frozenTime = frozenTime; }
}
