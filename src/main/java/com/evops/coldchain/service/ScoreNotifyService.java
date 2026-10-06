package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ScoreNotifyAttempt;
import com.evops.coldchain.entity.ScoreNotifyState;
import com.evops.coldchain.mapper.ScoreNotifyAttemptMapper;
import com.evops.coldchain.mapper.ScoreNotifyStateMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 评分域通知（V15）：周期取证阻断告警 / 改进任务逾期提醒。
 *
 * 去重口径（恢复后不重复通知）：
 * - 每个业务键（bizType + periodId/taskId）有一条去重状态；
 * - 已成功通知（notified=TRUE）后任何扫描/重试都直接跳过，不再追加尝试行；
 * - 未成功时每次扫描追加一条尝试行，webhook 失败记原因，下轮继续重试直至成功一次；
 * - endpoint 为空走站内信（写库即送达）；非空走 {@link ExceptionNotifyTransport}（可能失败）。
 */
@Service
public class ScoreNotifyService {

    @Resource
    private ScoreNotifyStateMapper stateMapper;
    @Resource
    private ScoreNotifyAttemptMapper attemptMapper;
    @Resource
    private ExceptionNotifyTransport transport;
    @Resource
    private OpLogService opLogService;

    @Value("${coldchain.score-notify.endpoint:}")
    private String endpoint;

    /**
     * 若该业务尚未成功通知则发送一次；已成功通知直接跳过。
     * @return 本次调用是否真正发送成功（FALSE=已去重跳过或本次发送失败）
     */
    @Transactional
    public boolean notifyIfNotSent(String bizType, Long periodId, Long taskId, String targetName, String payload) {
        ScoreNotifyState state = findState(bizType, periodId, taskId);
        if (state == null) {
            state = new ScoreNotifyState();
            state.setBizType(bizType);
            state.setPeriodId(periodId);
            state.setTaskId(taskId);
            state.setNotified(false);
            try {
                stateMapper.insert(state);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发下另一线程已建状态行：重新读取（可能已被置为 notified），复用该行
                state = findState(bizType, periodId, taskId);
                if (state == null) {
                    throw e;
                }
            }
        }
        if (Boolean.TRUE.equals(state.getNotified())) {
            // 已成功通知过：外部依赖恢复后续算/再次扫描都不重复通知
            return false;
        }
        Long count = attemptMapper.selectCount(new QueryWrapper<ScoreNotifyAttempt>()
                .eq("biz_type", bizType)
                .eq(periodId != null, "period_id", periodId)
                .isNull(periodId == null, "period_id")
                .eq(taskId != null, "task_id", taskId)
                .isNull(taskId == null, "task_id"));
        int attemptNo = (count == null ? 0 : count.intValue()) + 1;

        ScoreNotifyAttempt row = new ScoreNotifyAttempt();
        row.setBizType(bizType);
        row.setPeriodId(periodId);
        row.setTaskId(taskId);
        row.setTargetName(targetName);
        row.setAttemptNo(attemptNo);
        row.setSendTime(LocalDateTime.now());
        if (endpoint == null || endpoint.trim().isEmpty()) {
            row.setChannel(CcConstants.NOTIFY_CHANNEL_SITE);
            row.setSendStatus(CcConstants.NOTIFY_SUCCESS);
        } else {
            row.setChannel(CcConstants.NOTIFY_CHANNEL_WEBHOOK);
            ExceptionNotifyTransport.SendResult result = transport.send(endpoint.trim(), payload);
            row.setSendStatus(result.isSuccess() ? CcConstants.NOTIFY_SUCCESS : CcConstants.NOTIFY_FAILED);
            if (!result.isSuccess()) {
                row.setFailReason(result.getFailReason());
            }
        }
        attemptMapper.insert(row);

        if (CcConstants.NOTIFY_SUCCESS.equals(row.getSendStatus())) {
            state.setNotified(true);
            state.setNotifiedTime(LocalDateTime.now());
            stateMapper.updateById(state);
            opLogService.log(CcConstants.ENT_SCORE_PERIOD, periodId, null, CcConstants.ACT_SCORE_NOTIFY,
                    String.format("评分域通知成功（仅一次）：业务=%s，周期=%s，任务=%s，目标=%s，渠道=%s，第%d次尝试",
                            bizType, periodId, taskId, targetName, row.getChannel(), attemptNo));
            return true;
        }
        opLogService.log(CcConstants.ENT_SCORE_PERIOD, periodId, null, CcConstants.ACT_SCORE_NOTIFY,
                String.format("评分域通知失败，下轮重试：业务=%s，目标=%s，第%d次尝试，失败原因=%s",
                        bizType, targetName, attemptNo, row.getFailReason()));
        return false;
    }

    /** 查询某业务是否已成功通知（调度/测试断言用） */
    public boolean isNotified(String bizType, Long periodId, Long taskId) {
        ScoreNotifyState state = findState(bizType, periodId, taskId);
        return state != null && Boolean.TRUE.equals(state.getNotified());
    }

    public java.util.List<ScoreNotifyAttempt> listAttempts(String bizType, Long periodId, Long taskId) {
        return attemptMapper.selectList(new QueryWrapper<ScoreNotifyAttempt>()
                .eq("biz_type", bizType)
                .eq(periodId != null, "period_id", periodId)
                .isNull(periodId == null, "period_id")
                .eq(taskId != null, "task_id", taskId)
                .isNull(taskId == null, "task_id")
                .orderByAsc("id"));
    }

    private ScoreNotifyState findState(String bizType, Long periodId, Long taskId) {
        // H2 唯一索引对多 NULL 不去重（同 V3 绑定幂等的已知限制），极端并发下可能有多行状态；
        // 优先取“已成功通知”行，保证只要成功过一次就绝不再发
        ScoreNotifyState notified = stateMapper.selectOne(new QueryWrapper<ScoreNotifyState>()
                .eq("biz_type", bizType)
                .eq(periodId != null, "period_id", periodId)
                .isNull(periodId == null, "period_id")
                .eq(taskId != null, "task_id", taskId)
                .isNull(taskId == null, "task_id")
                .eq("notified", true)
                .last("LIMIT 1"));
        if (notified != null) {
            return notified;
        }
        return stateMapper.selectOne(new QueryWrapper<ScoreNotifyState>()
                .eq("biz_type", bizType)
                .eq(periodId != null, "period_id", periodId)
                .isNull(periodId == null, "period_id")
                .eq(taskId != null, "task_id", taskId)
                .isNull(taskId == null, "task_id")
                .last("LIMIT 1"));
    }
}
