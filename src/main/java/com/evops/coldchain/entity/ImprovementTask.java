package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 申诉复核结论产生的改进任务（V15）。OPEN 待整改，逾期由定时任务自动提醒负责人一次；
 * 完成只追加完成字段（finish*），事实只进不退，不提供删除路径。
 */
@TableName("t_cc_improvement_task")
public class ImprovementTask extends BaseEntity {
    private String taskNo;
    private Long appealId;
    private Long reviewId;
    private Long snapshotId;
    private Long periodId;
    private Long carrierId;
    private String carrierNameSnap;
    private String content;
    private String ownerUsername;
    private String ownerName;
    private String ownerPhone;
    private LocalDateTime dueTime;
    /** OPEN / DONE */
    private String status;
    private String finishNote;
    private Long finishBy;
    private String finishName;
    private LocalDateTime finishTime;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String s) { this.carrierNameSnap = s; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String username) { this.ownerUsername = username; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerPhone() { return ownerPhone; }
    public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
    public LocalDateTime getDueTime() { return dueTime; }
    public void setDueTime(LocalDateTime dueTime) { this.dueTime = dueTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFinishNote() { return finishNote; }
    public void setFinishNote(String finishNote) { this.finishNote = finishNote; }
    public Long getFinishBy() { return finishBy; }
    public void setFinishBy(Long finishBy) { this.finishBy = finishBy; }
    public String getFinishName() { return finishName; }
    public void setFinishName(String finishName) { this.finishName = finishName; }
    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
}
