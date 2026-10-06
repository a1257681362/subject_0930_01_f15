package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 承运商登录账号绑定（V15）：一个登录账号绑定一家承运商。
 * 承运口径所有查询/申诉强制按 carrierId 过滤，只能查看本企业指标与申诉入口。
 */
@TableName("t_cc_carrier_account")
public class CarrierAccount extends BaseEntity {
    private String username;
    private Long carrierId;
    private String carrierNoSnap;
    private String carrierNameSnap;
    /** ACTIVE / REVOKED */
    private String status;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNoSnap() { return carrierNoSnap; }
    public void setCarrierNoSnap(String carrierNoSnap) { this.carrierNoSnap = carrierNoSnap; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
