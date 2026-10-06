package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.CarrierAccount;
import com.evops.coldchain.mapper.CarrierAccountMapper;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 承运商登录账号绑定（V15）：承运口径（查看本企业指标/取证、提交申诉、完成本企业任务）
 * 一律经 {@link #requireCurrentCarrierId()} 取绑定 carrierId 做服务层强制隔离，
 * 不依赖前端传参，跨企业访问直接抛业务异常（HTTP 层由全局处理转 400/由 controller 转 403 语义）。
 */
@Service
public class CarrierAccountService {

    @Resource
    private CarrierAccountMapper accountMapper;
    @Resource
    private CarrierMapper carrierMapper;

    /** 当前登录账号绑定的承运商ID；未绑定/已停用一律拒绝（绝不返回 null 让查询漏过滤） */
    public Long requireCurrentCarrierId() {
        String username = UserContext.currentName();
        CarrierAccount account = accountMapper.selectOne(new QueryWrapper<CarrierAccount>()
                .eq("username", username).last("LIMIT 1"));
        if (account == null) {
            throw new BusinessException("当前账号[" + username + "]未绑定承运商主档，不能访问承运商评分口径");
        }
        if (com.evops.coldchain.CcConstants.REVOKED.equals(account.getStatus())) {
            throw new BusinessException("当前账号的承运商绑定已停用：" + account.getCarrierNameSnap());
        }
        return account.getCarrierId();
    }

    public CarrierAccount findByUsername(String username) {
        return accountMapper.selectOne(new QueryWrapper<CarrierAccount>()
                .eq("username", username).last("LIMIT 1"));
    }

    public Carrier requireCarrier(Long carrierId) {
        Carrier carrier = carrierMapper.selectById(carrierId);
        if (carrier == null) {
            throw new BusinessException("承运商不存在: " + carrierId);
        }
        return carrier;
    }

    /** 断言某承运商数据属于当前登录账号绑定企业（跨企业访问拦截） */
    public void requireSelf(Long carrierId) {
        Long bound = requireCurrentCarrierId();
        if (!bound.equals(carrierId)) {
            throw new BusinessException("禁止访问其他承运商的评分数据：当前账号绑定承运商#" + bound);
        }
    }
}
