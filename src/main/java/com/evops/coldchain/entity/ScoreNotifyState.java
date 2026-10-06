package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 评分域通知去重状态（V15）：(bizType, periodId, taskId) 唯一。
 * notified=TRUE 后永不重复通知：外部依赖恢复后续算不补告、不重复告警；任务逾期只提醒一次。
 */
@TableName("t_cc_score_notify_state")
public class ScoreNotifyState extends BaseEntity {
    /** PERIOD_BLOCKED / TASK_OVERDUE */
    private String bizType;
    private Long periodId;
    private Long taskId;
    private Boolean notified;
    private LocalDateTime notifiedTime;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Boolean getNotified() { return notified; }
    public void setNotified(Boolean notified) { this.notified = notified; }
    public LocalDateTime getNotifiedTime() { return notifiedTime; }
    public void setNotifiedTime(LocalDateTime notifiedTime) { this.notifiedTime = notifiedTime; }
}
