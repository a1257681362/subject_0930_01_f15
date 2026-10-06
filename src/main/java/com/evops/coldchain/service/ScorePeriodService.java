package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.ScoreCloseAttempt;
import com.evops.coldchain.entity.ScoreMetricEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.coldchain.mapper.ScoreCloseAttemptMapper;
import com.evops.coldchain.mapper.ScoreMetricEvidenceMapper;
import com.evops.coldchain.mapper.ScorePeriodMapper;
import com.evops.coldchain.mapper.ScoreSnapshotMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 评分周期与不可变快照（V15）。
 *
 * 1) 周期按月懒创建，创建瞬间绑定当时生效规则版本；规则之后发布新版本只影响下一周期。
 * 2) 周期结束（period_end <= now）才能关闭；关闭前必须先通过外部温控平台健康检查并取回
 *    全部批次客观判定——依赖无响应/返回缺批次：本次关闭整体阻断（BLOCKED 留痕+告警一次），
 *    不产出任何快照、不给任何指标默认成功。
 * 3) 关闭事务内按承运商逐项取证、生成快照与四行取证，快照内容 SHA-256 摘要；
 *    快照/取证只 INSERT，应用层没有更新路径（申诉/复核绝不回写）。
 * 4) 关闭幂等：已 CLOSED 周期直接跳过；唯一约束 uk(period_id, carrier_id) 数据库层兜底重复执行。
 */
@Service
public class ScorePeriodService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
    private static final DateTimeFormatter PERIOD_CODE_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    @Resource private ScorePeriodMapper periodMapper;
    @Resource private ScoreSnapshotMapper snapshotMapper;
    @Resource private ScoreMetricEvidenceMapper evidenceMapper;
    @Resource private ScoreCloseAttemptMapper closeAttemptMapper;
    @Resource private CarrierMapper carrierMapper;
    @Resource private ScoreRuleService ruleService;
    @Resource private ScoreEvidenceService evidenceService;
    @Resource private TempMonitorDependency tempMonitor;
    @Resource private ScoreNotifyService notifyService;
    @Resource private OpLogService opLogService;

    /** 确保某时刻所属月份的评分周期存在（不存在则创建并绑定当前生效规则版本） */
    @Transactional
    public ScorePeriod ensurePeriod(LocalDateTime point) {
        String code = point.format(PERIOD_CODE_FMT);
        ScorePeriod period = periodMapper.selectOne(new QueryWrapper<ScorePeriod>()
                .eq("period_code", code).last("LIMIT 1"));
        if (period != null) {
            return period;
        }
        ScoreRule rule = ruleService.requireEffective();
        LocalDateTime start = point.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = start.plusMonths(1);
        period = new ScorePeriod();
        period.setPeriodCode(code);
        period.setPeriodStart(start);
        period.setPeriodEnd(end);
        period.setRuleId(rule.getId());
        period.setRuleVersionNo(rule.getVersionNo());
        period.setStatus(CcConstants.SCORE_PERIOD_OPEN);
        periodMapper.insert(period);
        opLogService.log(CcConstants.ENT_SCORE_PERIOD, period.getId(), null, CcConstants.ACT_SCORE_PERIOD_ENSURE,
                String.format("确保评分周期 %s 存在（%s ~ %s），创建时绑定评分规则 V%d，此后换版与本周期无关",
                        code, start, end, rule.getVersionNo()));
        return period;
    }

    /**
     * 定时/手动扫描：关闭所有“已到周期止仍 OPEN”的周期。
     * @return 汇总（closedPeriods/blockedPeriods/snapshots/blockedReasons）
     */
    public Map<String, Object> closeDuePeriods(LocalDateTime now, String triggerSource) {
        List<ScorePeriod> due = periodMapper.selectList(new QueryWrapper<ScorePeriod>()
                .eq("status", CcConstants.SCORE_PERIOD_OPEN)
                .le("period_end", now)
                .orderByAsc("period_end"));
        int closed = 0;
        int blocked = 0;
        int snapshots = 0;
        List<String> reasons = new ArrayList<>();
        for (ScorePeriod p : due) {
            Map<String, Object> r = closePeriod(p.getId(), triggerSource, now);
            if (CcConstants.CLOSE_CLOSED.equals(r.get("result"))) {
                closed++;
                snapshots += (int) r.getOrDefault("snapshotCount", 0);
            } else {
                blocked++;
                reasons.add(p.getPeriodCode() + ": " + r.get("blockedReason"));
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("duePeriods", due.size());
        summary.put("closedPeriods", closed);
        summary.put("blockedPeriods", blocked);
        summary.put("snapshots", snapshots);
        summary.put("blockedReasons", reasons);
        return summary;
    }

    /**
     * 关闭单个周期：外部依赖健康检查+取数 → 逐承运商取证 → 写不可变快照。
     * 依赖不可用时阻断（不产出快照、不假装成功），并对该周期只告警一次。
     */
    @Transactional
    public Map<String, Object> closePeriod(Long periodId, String triggerSource, LocalDateTime now) {
        ScorePeriod period = requirePeriod(periodId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (CcConstants.SCORE_PERIOD_CLOSED.equals(period.getStatus())) {
            result.put("result", CcConstants.CLOSE_CLOSED);
            result.put("alreadyClosed", true);
            result.put("snapshotCount", snapshotMapper.selectCount(
                    new QueryWrapper<ScoreSnapshot>().eq("period_id", periodId)));
            return result;
        }
        if (now.isBefore(period.getPeriodEnd())) {
            throw new BusinessException("评分周期 " + period.getPeriodCode() + " 尚未结束（"
                    + period.getPeriodEnd() + "），不能提前关闭");
        }

        // 1) 外部温控依赖健康检查：无响应绝不假装成功
        TempMonitorDependency.HealthResult health = tempMonitor.checkHealth();
        if (!health.isHealthy()) {
            return blocked(period, triggerSource, now,
                    "外部温控监测平台无响应/不健康：" + health.getReason(), result);
        }

        // 2) 取周期内已签收批次，按承运商分组；无批次也要能关闭（空周期）
        List<TransportBatch> batches = evidenceService.findSignedBatches(period.getPeriodStart(), period.getPeriodEnd());
        Map<Long, Vehicle> vehicleMap = evidenceService.loadVehicles(batches);
        Map<Long, LockedPrice> lockedPriceMap = evidenceService.loadLockedPrices(batches);
        Map<Long, List<TransportBatch>> byCarrier = new TreeMap<>();
        for (TransportBatch b : batches) {
            Long carrierId = evidenceService.resolveCarrierId(b, vehicleMap, lockedPriceMap);
            if (carrierId == null) {
                continue; // 无法归属到承运商主档的批次（历史自由文本车辆且无锁价）不纳入评分
            }
            byCarrier.computeIfAbsent(carrierId, k -> new ArrayList<>()).add(b);
        }

        // 3) 一次性取回全部批次客观判定；缺一批次即阻断，绝不默认合格
        TempMonitorDependency.TempVerdictResult verdicts;
        try {
            verdicts = tempMonitor.fetchTempVerdicts(new ArrayList<>(batchIds(byCarrier)));
            // 先整体校验覆盖度再写任何快照：缺任一批次判定→整体阻断，避免部分承运商已落快照
            for (Long bid : batchIds(byCarrier)) {
                if (verdicts == null || !verdicts.getQualifiedByBatch().containsKey(bid)) {
                    throw new ExternalDependencyUnavailableException(
                            "外部温控平台未返回批次#" + bid + " 的温控判定，取证数据不完整，阻断评分");
                }
            }
        } catch (ExternalDependencyUnavailableException e) {
            return blocked(period, triggerSource, now, e.getMessage(), result);
        }

        ScoreRule rule = ruleService.requireById(period.getRuleId());
        int created = 0;
        for (Map.Entry<Long, List<TransportBatch>> entry : byCarrier.entrySet()) {
            created += buildSnapshot(period, rule, entry.getKey(), entry.getValue(), verdicts, now);
        }

        period.setStatus(CcConstants.SCORE_PERIOD_CLOSED);
        period.setCloseTime(now);
        UserPrincipal principal = UserContext.current();
        period.setCloseBy(principal.getId());
        period.setCloseName(principal.getUsername());
        periodMapper.updateById(period);

        ScoreCloseAttempt attempt = new ScoreCloseAttempt();
        attempt.setPeriodId(periodId);
        attempt.setAttemptTime(now);
        attempt.setResult(CcConstants.CLOSE_CLOSED);
        attempt.setSnapshotCount(created);
        attempt.setTriggerSource(triggerSource);
        closeAttemptMapper.insert(attempt);

        opLogService.log(CcConstants.ENT_SCORE_PERIOD, periodId, null, CcConstants.ACT_SCORE_CLOSE,
                String.format("评分周期 %s 关闭：对 %d 家承运商生成不可变评分快照（共 %d 条），按绑定规则 V%d 取证",
                        period.getPeriodCode(), byCarrier.size(), created, rule.getVersionNo()));
        result.put("result", CcConstants.CLOSE_CLOSED);
        result.put("snapshotCount", created);
        result.put("carrierCount", byCarrier.size());
        return result;
    }

    /** 生成单承运商快照与四行取证；返回 1（唯一约束兜底重复） */
    private int buildSnapshot(ScorePeriod period, ScoreRule rule, Long carrierId,
                              List<TransportBatch> batches, TempMonitorDependency.TempVerdictResult verdicts,
                              LocalDateTime now) {
        Long exists = snapshotMapper.selectCount(new QueryWrapper<ScoreSnapshot>()
                .eq("period_id", period.getId()).eq("carrier_id", carrierId));
        if (exists != null && exists > 0) {
            return 0;
        }
        Carrier carrier = carrierMapper.selectById(carrierId);

        ScoreEvidenceService.EvidenceInput input = new ScoreEvidenceService.EvidenceInput();
        input.batches = batches;
        input.tempQualifiedByBatch = verdicts.getQualifiedByBatch();
        input.tempFetchedAt = verdicts.getFetchedAt();
        input.tempBasis = verdicts.getRawBasis();
        input.onTimeGraceMinutes = rule.getOnTimeGraceMinutes() == null ? 0 : rule.getOnTimeGraceMinutes();
        List<ScoreEvidenceService.MetricEval> evals = evidenceService.evaluate(input);

        Map<String, ScoreEvidenceService.MetricEval> metricMap = new LinkedHashMap<>();
        for (ScoreEvidenceService.MetricEval e : evals) {
            metricMap.put(e.metricCode, e);
        }
        BigDecimal total = weightedTotal(rule, metricMap);

        ScoreSnapshot snap = new ScoreSnapshot();
        snap.setPeriodId(period.getId());
        snap.setPeriodCode(period.getPeriodCode());
        snap.setCarrierId(carrierId);
        snap.setCarrierNoSnap(carrier == null ? String.valueOf(carrierId) : carrier.getCarrierNo());
        snap.setCarrierNameSnap(carrier == null ? ("承运商#" + carrierId) : carrier.getCarrierName());
        snap.setRuleId(rule.getId());
        snap.setRuleVersionNo(rule.getVersionNo());
        snap.setOnTimeScore(metricMap.get(CcConstants.METRIC_ON_TIME).metricScore);
        snap.setTempControlScore(metricMap.get(CcConstants.METRIC_TEMP_CONTROL).metricScore);
        snap.setSignIntegrityScore(metricMap.get(CcConstants.METRIC_SIGN_INTEGRITY).metricScore);
        snap.setExceptionResponseScore(metricMap.get(CcConstants.METRIC_EXCEPTION_RESPONSE).metricScore);
        snap.setTotalScore(total);
        snap.setBatchCount(batches.size());
        snap.setFrozenTime(now);

        String json;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("periodCode", period.getPeriodCode());
            body.put("periodStart", period.getPeriodStart());
            body.put("periodEnd", period.getPeriodEnd());
            body.put("carrierId", carrierId);
            body.put("carrierNo", snap.getCarrierNoSnap());
            body.put("carrierName", snap.getCarrierNameSnap());
            body.put("frozenTime", now);
            body.put("rule", ruleSnapshot(rule));
            Map<String, Object> scores = new LinkedHashMap<>();
            for (ScoreEvidenceService.MetricEval e : evals) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("numerator", e.numerator);
                s.put("denominator", e.denominator);
                s.put("score", e.metricScore);
                s.put("sourceBasis", e.sourceBasis);
                scores.put(e.metricCode, s);
            }
            scores.put("TOTAL", total);
            body.put("scores", scores);
            json = M.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("生成评分快照 JSON 失败", e);
        }
        snap.setSnapshotJson(json);
        snap.setSnapshotSha256(ProbeService.sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
        snapshotMapper.insert(snap);

        for (ScoreEvidenceService.MetricEval e : evals) {
            ScoreMetricEvidence row = new ScoreMetricEvidence();
            row.setSnapshotId(snap.getId());
            row.setPeriodId(period.getId());
            row.setCarrierId(carrierId);
            row.setMetricCode(e.metricCode);
            row.setMetricName(e.metricName);
            row.setNumerator(e.numerator);
            row.setDenominator(e.denominator);
            row.setMetricScore(e.metricScore);
            row.setSourceBasis(e.sourceBasis);
            row.setEvidenceTime(now);
            try {
                row.setDetailJson(M.writeValueAsString(e.details));
            } catch (Exception ex) {
                throw new IllegalStateException("生成指标取证明细 JSON 失败", ex);
            }
            evidenceMapper.insert(row);
        }
        return 1;
    }

    /** 阻断：本次关闭不产出快照，留 BLOCKED 痕迹；阻断告警同一周期只成功通知一次 */
    private Map<String, Object> blocked(ScorePeriod period, String triggerSource, LocalDateTime now,
                                        String reason, Map<String, Object> result) {
        ScoreCloseAttempt attempt = new ScoreCloseAttempt();
        attempt.setPeriodId(period.getId());
        attempt.setAttemptTime(now);
        attempt.setResult(CcConstants.CLOSE_BLOCKED);
        attempt.setBlockedReason(reason);
        attempt.setSnapshotCount(0);
        attempt.setTriggerSource(triggerSource);
        closeAttemptMapper.insert(attempt);

        String payload = String.format(
                "{\"event\":\"SCORE_PERIOD_BLOCKED\",\"periodCode\":\"%s\",\"periodId\":%d,\"reason\":\"%s\"}",
                period.getPeriodCode(), period.getId(), reason.replace("\"", "'"));
        boolean sent = notifyService.notifyIfNotSent(
                CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null,
                CcConstants.ROLE_ADMIN + "(评分管理员)", payload);

        opLogService.log(CcConstants.ENT_SCORE_PERIOD, period.getId(), null, CcConstants.ACT_SCORE_CLOSE_ATTEMPT,
                String.format("评分周期 %s 关闭被外部依赖阻断：%s；阻断告警本次=%s（已成功告警过则恢复后不再重复通知）",
                        period.getPeriodCode(), reason, sent ? "首次成功" : "未新增（失败待重试或已告警去重）"));
        result.put("result", CcConstants.CLOSE_BLOCKED);
        result.put("blockedReason", reason);
        result.put("snapshotCount", 0);
        return result;
    }

    private List<Long> batchIds(Map<Long, List<TransportBatch>> byCarrier) {
        List<Long> ids = new ArrayList<>();
        for (List<TransportBatch> list : byCarrier.values()) {
            for (TransportBatch b : list) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    private BigDecimal weightedTotal(ScoreRule rule, Map<String, ScoreEvidenceService.MetricEval> metrics) {
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(weighted(metrics.get(CcConstants.METRIC_ON_TIME).metricScore, rule.getOnTimeWeight()));
        total = total.add(weighted(metrics.get(CcConstants.METRIC_TEMP_CONTROL).metricScore, rule.getTempControlWeight()));
        total = total.add(weighted(metrics.get(CcConstants.METRIC_SIGN_INTEGRITY).metricScore, rule.getSignIntegrityWeight()));
        total = total.add(weighted(metrics.get(CcConstants.METRIC_EXCEPTION_RESPONSE).metricScore,
                rule.getExceptionResponseWeight()));
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal weighted(BigDecimal score, Integer weight) {
        return score.multiply(BigDecimal.valueOf(weight == null ? 0 : weight))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> ruleSnapshot(ScoreRule rule) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ruleId", rule.getId());
        r.put("versionNo", rule.getVersionNo());
        r.put("versionName", rule.getVersionName());
        r.put("onTimeWeight", rule.getOnTimeWeight());
        r.put("tempControlWeight", rule.getTempControlWeight());
        r.put("signIntegrityWeight", rule.getSignIntegrityWeight());
        r.put("exceptionResponseWeight", rule.getExceptionResponseWeight());
        r.put("onTimeGraceMinutes", rule.getOnTimeGraceMinutes());
        r.put("appealOpenDays", rule.getAppealOpenDays());
        r.put("improvementDueDays", rule.getImprovementDueDays());
        return r;
    }

    // ---------------- 查询 ----------------

    public ScorePeriod requirePeriod(Long periodId) {
        ScorePeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException("评分周期不存在: " + periodId);
        }
        return period;
    }

    public List<ScorePeriod> listPeriods() {
        return periodMapper.selectList(new QueryWrapper<ScorePeriod>().orderByDesc("period_code"));
    }

    public List<ScoreSnapshot> listSnapshotsByPeriod(Long periodId) {
        return snapshotMapper.selectList(new QueryWrapper<ScoreSnapshot>()
                .eq(periodId != null, "period_id", periodId)
                .orderByDesc("period_id").orderByAsc("carrier_id"));
    }

    public List<ScoreSnapshot> listSnapshotsByCarrier(Long carrierId) {
        return snapshotMapper.selectList(new QueryWrapper<ScoreSnapshot>()
                .eq("carrier_id", carrierId).orderByDesc("period_id"));
    }

    public ScoreSnapshot requireSnapshot(Long snapshotId) {
        ScoreSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null) {
            throw new BusinessException("评分快照不存在: " + snapshotId);
        }
        return snap;
    }

    public List<ScoreMetricEvidence> listEvidence(Long snapshotId) {
        return evidenceMapper.selectList(new QueryWrapper<ScoreMetricEvidence>()
                .eq("snapshot_id", snapshotId).orderByAsc("id"));
    }

    public List<ScoreCloseAttempt> listCloseAttempts(Long periodId) {
        return closeAttemptMapper.selectList(new QueryWrapper<ScoreCloseAttempt>()
                .eq("period_id", periodId).orderByAsc("id"));
    }
}
