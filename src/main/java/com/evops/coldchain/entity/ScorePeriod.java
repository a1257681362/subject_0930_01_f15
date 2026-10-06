package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 评分周期（V15）：按月开闭。创建时绑定评分规则版本（ruleId/ruleVersionNo），
 * 周期内取证一律按绑定版本；关闭后每承运商生成不可变快照，周期冻结。
 */
@TableName("t_cc_score_period")
public class ScorePeriod extends BaseEntity {
    private String periodCode;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Long ruleId;
    private Integer ruleVersionNo;
    /** OPEN / CLOSED */
    private String status;
    private LocalDateTime closeTime;
    private Long closeBy;
    private String closeName;

    public String getPeriodCode() { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalDateTime closeTime) { this.closeTime = closeTime; }
    public Long getCloseBy() { return closeBy; }
    public void setCloseBy(Long closeBy) { this.closeBy = closeBy; }
    public String getCloseName() { return closeName; }
    public void setCloseName(String closeName) { this.closeName = closeName; }
}
