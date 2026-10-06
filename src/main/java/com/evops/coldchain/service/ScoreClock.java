package com.evops.coldchain.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 评分域时间源（V15）：默认走系统时钟；集成测试可固定到历史时间，
 * 以确定性地验证“申诉期内/外、改进任务逾期”等与时间相关的规则。
 */
@Component
public class ScoreClock {

    private volatile Clock clock = Clock.systemDefaultZone();

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 测试用：固定时间 */
    public void setFixed(LocalDateTime at) {
        this.clock = Clock.fixed(at.atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
    }

    /** 测试用：恢复系统时钟 */
    public void reset() {
        this.clock = Clock.systemDefaultZone();
    }
}
