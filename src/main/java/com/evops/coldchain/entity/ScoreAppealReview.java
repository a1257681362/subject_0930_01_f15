package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 申诉复核结论（V15）：只追加，每次复核一行；必须引用申诉证据（evidenceRefs），
 * 结论产生改进任务（generatedTaskId）。APPROVED 时记录并列修正分（不回写快照）。
 */
@TableName("t_cc_score_appeal_review")
public class ScoreAppealReview extends CreateOnlyEntity {
    private Long appealId;
    /** APPROVED / REJECTED */
    private String result;
    private String reviewNote;
    private String evidenceRefs;
    private BigDecimal adjustedMetricScore;
    private BigDecimal adjustedTotalScore;
    private String adjustNote;
    private Long generatedTaskId;
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;

    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public BigDecimal getAdjustedMetricScore() { return adjustedMetricScore; }
    public void setAdjustedMetricScore(BigDecimal adjustedMetricScore) { this.adjustedMetricScore = adjustedMetricScore; }
    public BigDecimal getAdjustedTotalScore() { return adjustedTotalScore; }
    public void setAdjustedTotalScore(BigDecimal adjustedTotalScore) { this.adjustedTotalScore = adjustedTotalScore; }
    public String getAdjustNote() { return adjustNote; }
    public void setAdjustNote(String adjustNote) { this.adjustNote = adjustNote; }
    public Long getGeneratedTaskId() { return generatedTaskId; }
    public void setGeneratedTaskId(Long generatedTaskId) { this.generatedTaskId = generatedTaskId; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
}
