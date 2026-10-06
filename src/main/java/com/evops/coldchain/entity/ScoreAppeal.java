package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评分申诉（V15）：只追加。申诉期间与复核后快照原分都不回写；
 * 申诉成立只在本表追加 adjusted* 并列修正值与说明，视图中原分与修正并列展示。
 */
@TableName("t_cc_score_appeal")
public class ScoreAppeal extends BaseEntity {
    private String appealNo;
    private Long snapshotId;
    private Long periodId;
    private Long carrierId;
    private String carrierNameSnap;
    private String metricCode;
    private String reason;
    /** SUBMITTED / APPROVED / REJECTED */
    private String status;
    private Long submitBy;
    private String submitName;
    private LocalDateTime submitTime;
    private LocalDateTime appealDeadline;
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewResult;
    private String reviewNote;
    private BigDecimal adjustedMetricScore;
    private BigDecimal adjustedTotalScore;
    private String adjustNote;

    public String getAppealNo() { return appealNo; }
    public void setAppealNo(String appealNo) { this.appealNo = appealNo; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSubmitBy() { return submitBy; }
    public void setSubmitBy(Long submitBy) { this.submitBy = submitBy; }
    public String getSubmitName() { return submitName; }
    public void setSubmitName(String string) { this.submitName = string; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
    public LocalDateTime getAppealDeadline() { return appealDeadline; }
    public void setAppealDeadline(LocalDateTime appealDeadline) { this.appealDeadline = appealDeadline; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewResult() { return reviewResult; }
    public void setReviewResult(String reviewResult) { this.reviewResult = reviewResult; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public BigDecimal getAdjustedMetricScore() { return adjustedMetricScore; }
    public void setAdjustedMetricScore(BigDecimal adjustedMetricScore) { this.adjustedMetricScore = adjustedMetricScore; }
    public BigDecimal getAdjustedTotalScore() { return adjustedTotalScore; }
    public void setAdjustedTotalScore(BigDecimal adjustedTotalScore) { this.adjustedTotalScore = adjustedTotalScore; }
    public String getAdjustNote() { return adjustNote; }
    public void setAdjustNote(String adjustNote) { this.adjustNote = adjustNote; }
}
