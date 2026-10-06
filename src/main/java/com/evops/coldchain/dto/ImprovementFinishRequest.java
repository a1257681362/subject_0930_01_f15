package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 改进任务完成请求（V15）：只追加完成事实 */
public class ImprovementFinishRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
    private String finishNote;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getFinishNote() { return finishNote; }
    public void setFinishNote(String finishNote) { this.finishNote = finishNote; }
}
