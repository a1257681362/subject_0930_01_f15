package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 申诉证据追加请求（V15）：只追加，复核结论必须引用其中至少一条 */
public class ScoreAppealEvidenceRequest {
    @NotNull(message = "申诉单ID不能为空")
    private Long appealId;
    @NotBlank(message = "证据文件名不能为空")
    private String fileName;
    @NotBlank(message = "证据地址不能为空")
    private String fileUrl;
    private String note;

    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
