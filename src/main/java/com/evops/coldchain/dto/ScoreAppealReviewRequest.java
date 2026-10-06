package com.evops.coldchain.dto;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/** 申诉复核请求（V15）：结论必须关联本申诉证据；成立时给并列修正分，原快照不回写，并产生改进任务 */
public class ScoreAppealReviewRequest {
    @NotNull(message = "申诉单ID不能为空")
    private Long appealId;
    @NotNull(message = "复核结论不能为空")
    private Boolean approved;
    @NotNull(message = "复核意见不能为空")
    private String reviewNote;
    @NotEmpty(message = "复核结论必须关联申诉证据")
    private List<Long> evidenceIds;
    /** 申诉成立时：并列修正后的指标分（0~100）；驳回可不传 */
    private java.math.BigDecimal adjustedMetricScore;
    /** 申诉成立时：修正说明（必填） */
    private String adjustNote;
    /** 改进任务要求（成立时必填，驳回也可要求整改） */
    private String improvementContent;
    /** 整改期限天（空=按规则版本 improvementDueDays） */
    private Integer improvementDueDays;

    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public List<Long> getEvidenceIds() { return evidenceIds; }
    public void setEvidenceIds(List<Long> evidenceIds) { this.evidenceIds = evidenceIds; }
    public java.math.BigDecimal getAdjustedMetricScore() { return adjustedMetricScore; }
    public void setAdjustedMetricScore(java.math.BigDecimal adjustedMetricScore) { this.adjustedMetricScore = adjustedMetricScore; }
    public String getAdjustNote() { return adjustNote; }
    public void setAdjustNote(String adjustNote) { this.adjustNote = adjustNote; }
    public String getImprovementContent() { return improvementContent; }
    public void setImprovementContent(String improvementContent) { this.improvementContent = improvementContent; }
    public Integer getImprovementDueDays() { return improvementDueDays; }
    public void setImprovementDueDays(Integer improvementDueDays) { this.improvementDueDays = improvementDueDays; }
}
