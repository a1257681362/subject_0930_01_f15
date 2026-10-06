package com.evops.coldchain.job;

import com.evops.coldchain.service.ImprovementTaskService;
import com.evops.coldchain.service.ScorePeriodService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 承运商评分周期定时扫描（V15）。
 *
 * 两件事：
 * 1) 关闭已到期评分周期：外部温控依赖无响应时周期保持 OPEN、留 BLOCKED 痕迹且不产出快照
 *    （指标绝不假装成功）；本任务下轮继续尝试，恢复后正常关闭，阻断告警只发一次、恢复不重复通知。
 * 2) 改进任务逾期提醒：OPEN 且过截止时间的任务自动提醒负责人，成功提醒一次后去重不再重复。
 * 通过 coldchain.score-cycle.enabled=false 关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.score-cycle.enabled", havingValue = "true", matchIfMissing = true)
public class ScoreCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScoreCycleScheduler.class);

    @Resource
    private ScorePeriodService periodService;
    @Resource
    private ImprovementTaskService taskService;

    @Scheduled(fixedDelayString = "${coldchain.score-cycle.close-fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.score-cycle.initial-delay-ms:30000}")
    public void closeDuePeriods() {
        try {
            Map<String, Object> summary = periodService.closeDuePeriods(LocalDateTime.now(),
                    com.evops.coldchain.CcConstants.TRIGGER_SCHEDULED);
            int due = (int) summary.getOrDefault("duePeriods", 0);
            if (due > 0 || log.isDebugEnabled()) {
                log.info("评分周期关闭扫描完成: {}", summary);
            }
        } catch (Exception e) {
            // 单次失败不影响下次调度；阻断/关闭均幂等，下轮按当前状态重算
            log.error("评分周期关闭扫描失败，将在下个周期重试", e);
        }
    }

    @Scheduled(fixedDelayString = "${coldchain.score-cycle.remind-fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.score-cycle.initial-delay-ms:30000}")
    public void remindOverdueTasks() {
        try {
            Map<String, Object> summary = taskService.scanOverdue(LocalDateTime.now());
            int overdue = (int) summary.getOrDefault("overdueTasks", 0);
            if (overdue > 0 || log.isDebugEnabled()) {
                log.info("改进任务逾期提醒扫描完成: {}", summary);
            }
        } catch (Exception e) {
            log.error("改进任务逾期提醒扫描失败，将在下个周期重试", e);
        }
    }
}
