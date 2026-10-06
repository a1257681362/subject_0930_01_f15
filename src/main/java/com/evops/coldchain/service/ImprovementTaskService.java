package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.CarrierAccount;
import com.evops.coldchain.entity.ImprovementTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreAppealReview;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.mapper.CarrierAccountMapper;
import com.evops.coldchain.mapper.ImprovementTaskMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 申诉复核改进任务（V15）：复核结论产生任务并落负责人/截止时间。
 * 逾期由定时任务自动提醒负责人——同一任务成功提醒只一次（去重状态表兜底），
 * 投递失败逐次留痕重试，恢复后不重复通知。完成只追加 finish* 字段，任务不删除。
 */
@Service
public class ImprovementTaskService {

    @Resource private ImprovementTaskMapper taskMapper;
    @Resource private CarrierAccountMapper accountMapper;
    @Resource private CarrierAccountService carrierAccountService;
    @Resource private ScoreNotifyService notifyService;
    @Resource private OpLogService opLogService;

    @Transactional
    public ImprovementTask createFromReview(ScoreAppeal appeal, ScoreSnapshot snap, ScoreAppealReview review,
                                            String content, int dueDays, LocalDateTime now) {
        Carrier carrier = carrierAccountService.requireCarrier(appeal.getCarrierId());
        // 负责人优先取承运商绑定登录账号，其次承运商联系人
        CarrierAccount account = accountMapper.selectOne(new QueryWrapper<CarrierAccount>()
                .eq("carrier_id", carrier.getId())
                .eq("status", CcConstants.ACTIVE)
                .last("LIMIT 1"));

        ImprovementTask task = new ImprovementTask();
        task.setTaskNo("IT" + sequenceMapper.nextImproveTaskNo());
        task.setAppealId(appeal.getId());
        task.setReviewId(review.getId());
        task.setSnapshotId(snap.getId());
        task.setPeriodId(snap.getPeriodId());
        task.setCarrierId(carrier.getId());
        task.setCarrierNameSnap(carrier.getCarrierName());
        task.setContent(content);
        task.setOwnerUsername(account == null ? null : account.getUsername());
        task.setOwnerName(carrier.getContactName() == null ? carrier.getCarrierName() : carrier.getContactName());
        task.setOwnerPhone(carrier.getContactPhone());
        task.setDueTime(now.plusDays(dueDays));
        task.setStatus(CcConstants.IMPROVE_OPEN);
        taskMapper.insert(task);

        opLogService.log(CcConstants.ENT_SCORE_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_SCORE_IMPROVE_CREATE,
                String.format("复核结论产生改进任务 %s：承运商=%s，负责人=%s，截止=%s（由申诉 %s 复核结论关联证据生成）",
                        task.getTaskNo(), carrier.getCarrierName(), task.getOwnerName(),
                        task.getDueTime(), appeal.getAppealNo()));
        return task;
    }

    @Resource(name = "sequenceMapper")
    private com.evops.coldchain.mapper.SequenceMapper sequenceMapper;

    /** 完成任务（只进不退）；承运商账号只能完成本企业任务 */
    @Transactional
    public ImprovementTask finish(Long taskId, String finishNote, boolean enforceSelf) {
        ImprovementTask task = requireTask(taskId);
        if (enforceSelf) {
            carrierAccountService.requireSelf(task.getCarrierId());
        }
        if (CcConstants.IMPROVE_DONE.equals(task.getStatus())) {
            throw new BusinessException("改进任务 " + task.getTaskNo() + " 已完成，不能重复确认");
        }
        if (finishNote == null || finishNote.trim().isEmpty()) {
            throw new BusinessException("完成改进任务必须填写整改情况说明");
        }
        UserPrincipal principal = UserContext.current();
        task.setStatus(CcConstants.IMPROVE_DONE);
        task.setFinishNote(finishNote.trim());
        task.setFinishBy(principal.getId());
        task.setFinishName(principal.getUsername());
        task.setFinishTime(LocalDateTime.now());
        taskMapper.updateById(task);
        opLogService.log(CcConstants.ENT_SCORE_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_SCORE_IMPROVE_FINISH,
                String.format("改进任务 %s 完成确认：%s（完成前截止=%s，逾期提醒事实保留）",
                        task.getTaskNo(), finishNote.trim(), task.getDueTime()));
        return task;
    }

    /**
     * 定时扫描：OPEN 且 due_time < now 的任务自动提醒负责人；
     * 同一任务成功提醒一次后去重（notified=TRUE），后续扫描/依赖恢复都不重复通知。
     * @return 本次新成功提醒数 / 仍待重试数
     */
    @Transactional
    public Map<String, Object> scanOverdue(LocalDateTime now) {
        List<ImprovementTask> overdue = taskMapper.selectList(new QueryWrapper<ImprovementTask>()
                .eq("status", CcConstants.IMPROVE_OPEN)
                .lt("due_time", now)
                .orderByAsc("due_time"));
        int notified = 0;
        int pendingRetry = 0;
        for (ImprovementTask task : overdue) {
            if (notifyService.isNotified(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, task.getId())) {
                continue; // 已成功提醒过：绝不重复通知
            }
            String target = task.getOwnerName() + (task.getOwnerPhone() == null ? "" : "/" + task.getOwnerPhone());
            String payload = String.format(
                    "{\"event\":\"IMPROVEMENT_TASK_OVERDUE\",\"taskNo\":\"%s\",\"taskId\":%d,"
                            + "\"carrier\":\"%s\",\"dueTime\":\"%s\",\"content\":\"%s\"}",
                    task.getTaskNo(), task.getId(), task.getCarrierNameSnap(),
                    task.getDueTime(), task.getContent().replace("\"", "'"));
            boolean sent = notifyService.notifyIfNotSent(
                    CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, task.getId(), target, payload);
            if (sent) {
                notified++;
            } else {
                pendingRetry++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("overdueTasks", overdue.size());
        summary.put("newlyNotified", notified);
        summary.put("pendingRetry", pendingRetry);
        return summary;
    }

    public ImprovementTask requireTask(Long id) {
        ImprovementTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("改进任务不存在: " + id);
        }
        return task;
    }

    public List<ImprovementTask> listByAppeal(Long appealId) {
        return taskMapper.selectList(new QueryWrapper<ImprovementTask>()
                .eq("appeal_id", appealId).orderByAsc("id"));
    }

    /** 承运商：本企业任务；管理侧：全部 */
    public List<ImprovementTask> list(boolean enforceSelf) {
        QueryWrapper<ImprovementTask> qw = new QueryWrapper<ImprovementTask>().orderByDesc("id");
        if (enforceSelf) {
            qw.eq("carrier_id", carrierAccountService.requireCurrentCarrierId());
        }
        return taskMapper.selectList(qw);
    }
}
