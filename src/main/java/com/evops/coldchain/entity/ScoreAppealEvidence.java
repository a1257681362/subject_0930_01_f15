package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/** 申诉证据（V15）：只追加；复核结论必须引用本申诉至少一条证据。 */
@TableName("t_cc_score_appeal_evidence")
public class ScoreAppealEvidence extends CreateOnlyEntity {
    private Long appealId;
    private String fileName;
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
