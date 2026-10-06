package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 评分域通知尝试（V15）：只追加。阻断告警/逾期提醒的每次尝试独立一行，
 * 失败记 failReason，成功后去重状态置位不再重试（恢复后不重复通知）。
 */
@TableName("t_cc_score_notify_attempt")
public class ScoreNotifyAttempt extends CreateOnlyEntity {
    private String bizType;
    private Long periodId;
    private Long taskId;
    private String targetName;
    private Integer attemptNo;
    /** SITE / WEBHOOK */
    private String channel;
    /** SUCCESS / FAILED */
    private String sendStatus;
    private String failReason;
    private LocalDateTime sendTime;

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
}
