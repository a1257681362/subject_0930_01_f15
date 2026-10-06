package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 承运商评分规则版本（V15）：版本只追加，周期创建瞬间绑定当时生效版本，
 * 此后规则调整（发布新版本）只影响此后创建的下一周期。
 */
@TableName("t_cc_score_rule")
public class ScoreRule extends BaseEntity {
    private Integer versionNo;
    private String versionName;
    private Integer onTimeWeight;
    private Integer tempControlWeight;
    private Integer signIntegrityWeight;
    private Integer exceptionResponseWeight;
    private Integer onTimeGraceMinutes;
    private Integer appealOpenDays;
    private Integer improvementDueDays;
    private String changeReason;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    private java.time.LocalDateTime publishTime;
    private Long publishBy;
    private String publishName;

    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public Integer getOnTimeWeight() { return onTimeWeight; }
    public void setOnTimeWeight(Integer onTimeWeight) { this.onTimeWeight = onTimeWeight; }
    public Integer getTempControlWeight() { return tempControlWeight; }
    public void setTempControlWeight(Integer tempControlWeight) { this.tempControlWeight = tempControlWeight; }
    public Integer getSignIntegrityWeight() { return signIntegrityWeight; }
    public void setSignIntegrityWeight(Integer signIntegrityWeight) { this.signIntegrityWeight = signIntegrityWeight; }
    public Integer getExceptionResponseWeight() { return exceptionResponseWeight; }
    public void setExceptionResponseWeight(Integer exceptionResponseWeight) { this.exceptionResponseWeight = exceptionResponseWeight; }
    public Integer getOnTimeGraceMinutes() { return onTimeGraceMinutes; }
    public void setOnTimeGraceMinutes(Integer onTimeGraceMinutes) { this.onTimeGraceMinutes = onTimeGraceMinutes; }
    public Integer getAppealOpenDays() { return appealOpenDays; }
    public void setAppealOpenDays(Integer appealOpenDays) { this.appealOpenDays = appealOpenDays; }
    public Integer getImprovementDueDays() { return improvementDueDays; }
    public void setImprovementDueDays(Integer improvementDueDays) { this.improvementDueDays = improvementDueDays; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public java.time.LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(java.time.LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishName() { return publishName; }
    public void setPublishName(String publishName) { this.publishName = publishName; }
}
