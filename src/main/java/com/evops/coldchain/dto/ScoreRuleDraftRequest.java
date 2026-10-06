package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 评分规则新版本发布请求（V15）：发布热替换旧版本，只影响此后创建的下一周期 */
public class ScoreRuleDraftRequest {
    @NotBlank(message = "版本名称不能为空")
    private String versionName;
    @NotNull(message = "准时率权重不能为空")
    private Integer onTimeWeight;
    @NotNull(message = "温控合格率权重不能为空")
    private Integer tempControlWeight;
    @NotNull(message = "签收完整度权重不能为空")
    private Integer signIntegrityWeight;
    @NotNull(message = "异常响应权重不能为空")
    private Integer exceptionResponseWeight;
    private Integer onTimeGraceMinutes = 0;
    private Integer appealOpenDays = 7;
    private Integer improvementDueDays = 7;
    private String changeReason;

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
}
