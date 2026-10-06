package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.BatchRejectRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.ScoreAppealEvidenceRequest;
import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.dto.ScoreAppealSubmitRequest;
import com.evops.coldchain.dto.ScoreRuleDraftRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ImprovementTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreAppealReview;
import com.evops.coldchain.entity.ScoreMetricEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.coldchain.mapper.ImprovementTaskMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.coldchain.service.CarrierAccountService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.ExceptionNotifyTransport;
import com.evops.coldchain.service.ImprovementTaskService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.ScoreAppealService;
import com.evops.coldchain.service.ScoreNotifyService;
import com.evops.coldchain.service.ScorePeriodService;
import com.evops.coldchain.service.ScoreRuleService;
import com.evops.coldchain.service.TempMonitorDependency;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V15 验收：承运商服务评分。
 *
 * 注意：各用例使用不同月份周期（共享内存库、周期按月全局唯一），且不依赖用例执行顺序——
 * 规则版本号、加权总分一律按用例内绑定版本动态计算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(CarrierScoreIntegrationTest.ScoreTestConfig.class)
class CarrierScoreIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;

    @Autowired private ScorePeriodService periodService;
    @Autowired private ScoreRuleService ruleService;
    @Autowired private com.evops.coldchain.service.ScoreClock scoreClock;
    @Autowired private ScoreAppealService appealService;
    @Autowired private ImprovementTaskService taskService;
    @Autowired private ScoreNotifyService notifyService;
    @Autowired private CarrierAccountService carrierAccountService;
    @Autowired private TransportBatchService batchService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private DrugService drugService;
    @Autowired private ExceptionLifecycleService lifecycle;
    @Autowired private CarrierMapper carrierMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private ImprovementTaskMapper improvementTaskMapper;
    @Autowired private TestSubjects testSubjects;
    @Autowired private ScriptedTempMonitor tempMonitor;
    @Autowired private ScriptedNotifyTransport notifyTransport;

    @org.springframework.boot.test.context.TestConfiguration
    static class ScoreTestConfig {
        @Bean @Primary
        public TempMonitorDependency scriptedTempMonitor() {
            return new ScriptedTempMonitor();
        }

        @Bean @Primary
        public ExceptionNotifyTransport scriptedScoreNotifyTransport() {
            return new ScriptedNotifyTransport();
        }
    }

    /** 外部温控平台脚本替身：可控健康/无响应/返回不完整，判定按批次注入 */
    static class ScriptedTempMonitor implements TempMonitorDependency {
        volatile boolean healthy = true;
        final Map<Long, Boolean> verdicts = new HashMap<>();
        volatile boolean returnIncomplete = false;

        @Override
        public synchronized HealthResult checkHealth() {
            LocalDateTime now = LocalDateTime.now();
            return healthy ? HealthResult.ok(now)
                    : HealthResult.down("模拟：外部温控监测平台连接超时无响应", now);
        }

        @Override
        public synchronized TempVerdictResult fetchTempVerdicts(List<Long> batchIds) {
            if (!healthy) {
                throw new com.evops.coldchain.service.ExternalDependencyUnavailableException("模拟：取数时平台无响应");
            }
            Map<Long, Boolean> copy = new HashMap<>();
            if (!returnIncomplete) {
                for (Long id : batchIds) {
                    copy.put(id, verdicts.getOrDefault(id, Boolean.FALSE));
                }
            }
            // returnIncomplete=TRUE 时返回空映射：任何批次都缺判定，上层必须阻断（不允许默认成功）
            return new TempVerdictResult(copy, LocalDateTime.now(),
                    returnIncomplete ? "SCRIPTED_INCOMPLETE" : "SCRIPTED_TEMP_PLATFORM");
        }
    }

    /** 评分通知通道脚本替身：可指定只对某任务失败（避免共享库全局扫描干扰其他用例遗留任务） */
    static class ScriptedNotifyTransport implements ExceptionNotifyTransport {
        int failuresRemaining = 0;
        Long failForTaskId = null;

        @Override
        public synchronized SendResult send(String endpoint, String payload) {
            if (failForTaskId != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"taskId\":(\\d+)").matcher(payload);
                long payloadTask = m.find() ? Long.parseLong(m.group(1)) : -1L;
                if (payloadTask != failForTaskId) {
                    return SendResult.ok();
                }
            }
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return SendResult.fail("模拟投递失败：评分通知端无响应");
            }
            return SendResult.ok();
        }
    }

    @AfterEach
    void resetDoubles() {
        tempMonitor.healthy = true;
        tempMonitor.returnIncomplete = false;
        tempMonitor.verdicts.clear();
        notifyTransport.failuresRemaining = 0;
        ReflectionTestUtils.setField(notifyService, "endpoint", "");
        scoreClock.reset();
    }

    // ====================================================================
    // 1) 四指标分别取证 + 不可变快照 + 规则版本只影响下一周期（用 2026-09 周期）
    // ====================================================================
    @Test
    void fourMetricsEvidence_immutableSnapshot_ruleChangeOnlyAffectsNextPeriod() {
        long c1 = carrierId("CY100000001");
        long c2 = carrierId("CY100000002");
        LocalDateTime sep = LocalDateTime.of(2026, 9, 15, 10, 0);

        ScorePeriod sepPeriod = periodService.ensurePeriod(sep);
        int vBefore = sepPeriod.getRuleVersionNo();
        ruleService.publish(ruleNextVersion(vBefore + 1));
        ScorePeriod octPeriod = periodService.ensurePeriod(LocalDateTime.of(2026, 10, 20, 9, 0));
        assertEquals(vBefore + 1, octPeriod.getRuleVersionNo(), "规则调整只影响下一周期");

        long batchA = signedCleanBatch(c1, sep, sep.plusHours(2));   // 承诺 12:00 实际 10:00 → 准时
        long batchB = signedBadBatch(c1, sep, sep.minusHours(2));   // 承诺 08:00 实际 10:00 → 延误
        tempMonitor.verdicts.put(batchA, true);
        tempMonitor.verdicts.put(batchB, false);
        long batchC = signedCleanBatch(c2, sep, sep.plusHours(3));
        tempMonitor.verdicts.put(batchC, true);

        Map<String, Object> closed = periodService.closePeriod(sepPeriod.getId(),
                CcConstants.TRIGGER_MANUAL, LocalDateTime.of(2026, 10, 6, 8, 0));
        assertEquals(CcConstants.CLOSE_CLOSED, closed.get("result"));

        ScoreSnapshot snap1 = snapshotOf(sepPeriod.getId(), c1);
        ScoreSnapshot snap2 = snapshotOf(sepPeriod.getId(), c2);

        assertEquals(0, snap1.getOnTimeScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, snap1.getTempControlScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, snap1.getSignIntegrityScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, snap1.getExceptionResponseScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, snap1.getTotalScore().compareTo(new BigDecimal("50.00")));
        assertEquals(2, snap1.getBatchCount());
        assertEquals(vBefore, snap1.getRuleVersionNo(), "已存在周期快照永远按绑定版本取证");

        List<ScoreMetricEvidence> ev1 = periodService.listEvidence(snap1.getId());
        assertEquals(4, ev1.size(), "每快照四行逐项取证");
        ScoreMetricEvidence temp = evidence(ev1, CcConstants.METRIC_TEMP_CONTROL);
        assertEquals(1, temp.getNumerator());
        assertEquals(2, temp.getDenominator());
        assertTrue(temp.getSourceBasis().contains("EXTERNAL_TEMP_PLATFORM"));
        assertTrue(temp.getDetailJson().contains("SCRIPTED_TEMP_PLATFORM"));
        ScoreMetricEvidence onTime = evidence(ev1, CcConstants.METRIC_ON_TIME);
        assertEquals(1, onTime.getNumerator());
        assertEquals(2, onTime.getDenominator());
        ScoreMetricEvidence resp = evidence(ev1, CcConstants.METRIC_EXCEPTION_RESPONSE);
        assertTrue(resp.getDetailJson().contains("manualExceptions"));

        assertEquals(0, snap2.getTotalScore().compareTo(new BigDecimal("100.00")));
        assertEquals(1, snap2.getBatchCount());

        // 不可变：SHA-256 摘要可重算一致；重复关闭不新增快照
        String digest = com.evops.coldchain.service.ProbeService.sha256Hex(
                snap1.getSnapshotJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(digest, snap1.getSnapshotSha256());
        Map<String, Object> again = periodService.closePeriod(sepPeriod.getId(),
                CcConstants.TRIGGER_MANUAL, LocalDateTime.of(2026, 10, 6, 9, 0));
        assertEquals(CcConstants.CLOSE_CLOSED, again.get("result"));
        assertEquals(2, periodService.listSnapshotsByPeriod(sepPeriod.getId()).size());
        assertEquals(CcConstants.SCORE_PERIOD_CLOSED, periodService.requirePeriod(sepPeriod.getId()).getStatus());
    }

    // ====================================================================
    // 2) 外部依赖无响应/返回不完整 → 阻断不假装成功；恢复后关闭，阻断告警只发一次（用 2026-08 周期）
    // ====================================================================
    @Test
    void externalDown_blocksNeverFakeSuccess_recoveryCloses_withoutDuplicateAlert() {
        long c1 = carrierId("CY100000001");
        LocalDateTime aug = LocalDateTime.of(2026, 8, 15, 10, 0);
        ScorePeriod period = periodService.ensurePeriod(aug);

        long batch = signedCleanBatch(c1, aug, aug.plusHours(1));
        tempMonitor.verdicts.put(batch, true);
        LocalDateTime closeAt = LocalDateTime.of(2026, 9, 5, 8, 0);

        // 依赖无响应：阻断，不产出快照、指标不假装成功
        tempMonitor.healthy = false;
        Map<String, Object> blocked1 = periodService.closePeriod(period.getId(),
                CcConstants.TRIGGER_SCHEDULED, closeAt);
        assertEquals(CcConstants.CLOSE_BLOCKED, blocked1.get("result"));
        assertTrue(String.valueOf(blocked1.get("blockedReason")).contains("无响应"));
        assertEquals(0, periodService.listSnapshotsByPeriod(period.getId()).size());

        // 站内信首告必达且只一次：再次阻断不重复通知
        assertTrue(notifyService.isNotified(CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null));
        assertEquals(1, notifyService.listAttempts(
                CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null).size());
        periodService.closePeriod(period.getId(), CcConstants.TRIGGER_SCHEDULED, closeAt.plusMinutes(1));
        assertEquals(1, notifyService.listAttempts(
                CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null).size(),
                "依赖持续无响应不得重复通知");

        // 恢复但数据不完整（缺批次判定）：仍阻断、不假装成功、不新增告警
        tempMonitor.healthy = true;
        tempMonitor.returnIncomplete = true;
        Map<String, Object> blocked2 = periodService.closePeriod(period.getId(),
                CcConstants.TRIGGER_SCHEDULED, closeAt.plusMinutes(2));
        assertEquals(CcConstants.CLOSE_BLOCKED, blocked2.get("result"));
        assertTrue(String.valueOf(blocked2.get("blockedReason")).contains("不完整"));
        assertEquals(0, periodService.listSnapshotsByPeriod(period.getId()).size());
        assertEquals(1, notifyService.listAttempts(
                CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null).size());

        // 真正恢复：关闭成功，阻断告警不补、不重复
        tempMonitor.returnIncomplete = false;
        Map<String, Object> closed = periodService.closePeriod(period.getId(),
                CcConstants.TRIGGER_SCHEDULED, closeAt.plusMinutes(3));
        assertEquals(CcConstants.CLOSE_CLOSED, closed.get("result"));
        assertEquals(1, periodService.listSnapshotsByPeriod(period.getId()).size());
        assertEquals(1, notifyService.listAttempts(
                CcConstants.SCORE_NOTIFY_PERIOD_BLOCKED, period.getId(), null).size(),
                "恢复后续算成功也不能重复发送阻断告警");
        // 三次阻断尝试均留痕：两次无响应 + 一次恢复后数据不完整（每次尝试独立一行，成功关闭一行）
        assertEquals(3, periodService.listCloseAttempts(period.getId()).stream()
                .filter(a -> CcConstants.CLOSE_BLOCKED.equals(a.getResult())).count());
    }

    // ====================================================================
    // 3) 承运商只能查看自己的指标与申诉入口（用 2026-07 周期）
    // ====================================================================
    @Test
    void carrierSeesOnlyOwnScore_andShiroEnforced() {
        long c1 = carrierId("CY100000001");
        long c2 = carrierId("CY100000002");
        LocalDateTime jul = LocalDateTime.of(2026, 7, 15, 10, 0);
        ScorePeriod period = periodService.ensurePeriod(jul);
        long b1 = signedCleanBatch(c1, jul, jul.plusHours(1));
        long b2 = signedCleanBatch(c2, jul, jul.plusHours(1));
        tempMonitor.verdicts.put(b1, true);
        tempMonitor.verdicts.put(b2, true);
        periodService.closePeriod(period.getId(), CcConstants.TRIGGER_MANUAL,
                LocalDateTime.of(2026, 8, 5, 8, 0));

        ScoreSnapshot snap1 = snapshotOf(period.getId(), c1);
        ScoreSnapshot snap2 = snapshotOf(period.getId(), c2);

        // carrier 绑定承运商1：本周期内只看得到自己的快照
        List<ScoreSnapshot> carrier1PeriodList = testSubjects.apply(3L, "carrier", () -> {
            Long bound = carrierAccountService.requireCurrentCarrierId();
            List<ScoreSnapshot> all = periodService.listSnapshotsByPeriod(period.getId());
            List<ScoreSnapshot> mine = new ArrayList<>();
            for (ScoreSnapshot s : all) {
                if (bound.equals(s.getCarrierId())) {
                    mine.add(s);
                }
            }
            return mine;
        });
        assertEquals(1, carrier1PeriodList.size());
        assertEquals(c1, carrier1PeriodList.get(0).getCarrierId());

        // carrier2 访问承运商1快照：服务层拒绝
        BusinessException cross = testSubjects.apply(11L, "carrier2", () -> {
            try {
                carrierAccountService.requireSelf(c1);
                return null;
            } catch (BusinessException e) {
                return e;
            }
        });
        assertNotNull(cross);
        assertTrue(cross.getMessage().contains("其他承运商"));

        // Shiro：未认证 401；承运账号无全量权限 403；管理口径 dispatcher 200
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity(url("/scores/snapshots"), String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                http.withBasicAuth("carrier", "carrier123")
                        .getForEntity(url("/scores/snapshots"), String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                http.withBasicAuth("dispatcher", "dispatcher123")
                        .getForEntity(url("/scores/snapshots"), String.class).getStatusCode());
        // 自助入口：本企业 200；carrier2 读 snap1 业务拒绝（success=false）；dispatcher 无自助权限 403
        assertEquals(HttpStatus.OK,
                http.withBasicAuth("carrier", "carrier123")
                        .getForEntity(url("/my-scores/snapshots/" + snap1.getId()), String.class).getStatusCode());
        org.springframework.http.ResponseEntity<String> steal = http.withBasicAuth("carrier2", "carrier123")
                .getForEntity(url("/my-scores/snapshots/" + snap1.getId()), String.class);
        assertEquals(HttpStatus.OK, steal.getStatusCode());
        assertTrue(steal.getBody().contains("其他承运商"));
        assertEquals(HttpStatus.FORBIDDEN,
                http.withBasicAuth("dispatcher", "dispatcher123")
                        .getForEntity(url("/my-scores/snapshots/" + snap2.getId()), String.class).getStatusCode());
    }

    // ====================================================================
    // 4) 申诉原分不回写 + 复核必须关联证据 + 成立产生改进任务（用 2026-06 周期）
    // ====================================================================
    @Test
    void appealNeverRewritesScore_reviewMustLinkEvidence_andCreatesImprovementTask() {
        long c1 = carrierId("CY100000001");
        LocalDateTime jun = LocalDateTime.of(2026, 6, 15, 10, 0);
        ScorePeriod period = periodService.ensurePeriod(jun);
        long a = signedCleanBatch(c1, jun, jun.plusHours(2));
        long b = signedBadBatch(c1, jun, jun.minusHours(2));
        tempMonitor.verdicts.put(a, true);
        tempMonitor.verdicts.put(b, false);
        periodService.closePeriod(period.getId(), CcConstants.TRIGGER_MANUAL,
                LocalDateTime.of(2026, 7, 5, 8, 0));
        ScoreSnapshot snap = snapshotOf(period.getId(), c1);
        assertEquals(0, snap.getOnTimeScore().compareTo(new BigDecimal("50.00")));
        String shaBefore = snap.getSnapshotSha256();
        ScoreRule boundRule = ruleService.requireById(snap.getRuleId());

        // 固定在申诉期内（快照冻结 7/5 + 7 天）
        scoreClock.setFixed(LocalDateTime.of(2026, 7, 6, 10, 0));
        ScoreAppealSubmitRequest submit = new ScoreAppealSubmitRequest();
        submit.setSnapshotId(snap.getId());
        submit.setMetricCode(CcConstants.METRIC_ON_TIME);
        submit.setReason("批次B延误因高速封闭管制，附管制通告，申请剔除该批次");
        ScoreAppeal appeal = testSubjects.apply(3L, "carrier", () -> appealService.submit(submit));
        assertEquals(CcConstants.APPEAL_SUBMITTED, appeal.getStatus());

        // 承运商2不能给别人的快照申诉
        BusinessException cross = assertThrows(BusinessException.class, () ->
                testSubjects.runAs(11L, "carrier2", () -> appealService.submit(submit)));
        assertTrue(cross.getMessage().contains("本企业"));

        ScoreAppealEvidenceRequest evReq = new ScoreAppealEvidenceRequest();
        evReq.setAppealId(appeal.getId());
        evReq.setFileName("高速管制通告.pdf");
        evReq.setFileUrl("/v15/road-closure.pdf");
        evReq.setNote("交警支队发布的封闭通告");
        testSubjects.runAs(3L, "carrier", () -> appealService.appendEvidence(evReq));
        Long evidenceId = appealService.listEvidence(appeal.getId()).get(0).getId();

        // 不关联证据 / 引用不存在的证据：拒绝
        BusinessException noEvidence = assertThrows(BusinessException.class, () ->
                testSubjects.runAs(6L, "qa",
                        () -> appealService.review(reviewReq(appeal.getId(), true, new ArrayList<>()))));
        assertTrue(noEvidence.getMessage().contains("证据"));
        ScoreAppealReviewRequest foreignReq = reviewReq(appeal.getId(), true,
                new ArrayList<>(Collections.singletonList(999999L)));
        foreignReq.setAdjustedMetricScore(new BigDecimal("100.00"));
        foreignReq.setAdjustNote("修正说明");
        BusinessException foreign = assertThrows(BusinessException.class, () ->
                testSubjects.runAs(6L, "qa", () -> appealService.review(foreignReq)));
        assertTrue(foreign.getMessage().contains("不存在或不属于"));
        // dispatcher 无复核权限：HTTP 403
        assertEquals(HttpStatus.FORBIDDEN, postReviewHttp("dispatcher", reviewReq(appeal.getId(), true,
                new ArrayList<>(Collections.singletonList(evidenceId)))));

        // QA 复核成立：必须给修正分、修正说明与整改要求
        ScoreAppealReviewRequest okReq = reviewReq(appeal.getId(), true,
                new ArrayList<>(Collections.singletonList(evidenceId)));
        okReq.setAdjustedMetricScore(new BigDecimal("100.00"));
        okReq.setAdjustNote("管制时段承诺到达不可归责承运商，准时率并列修正为 100（原分保留）");
        okReq.setImprovementContent("完善异常路况提前报备与在途预警机制");
        ScoreAppealReview review = testSubjects.apply(6L, "qa", () -> appealService.review(okReq));
        assertEquals(CcConstants.REVIEW_APPROVED, review.getResult());
        assertEquals(String.valueOf(evidenceId), review.getEvidenceRefs());
        assertNotNull(review.getGeneratedTaskId());

        // 并列修正总分按快照绑定版本权重动态计算（准时100，其余50）
        BigDecimal expectedAdjusted = weighted(100, boundRule.getOnTimeWeight())
                .add(weighted(50, boundRule.getTempControlWeight()))
                .add(weighted(50, boundRule.getSignIntegrityWeight()))
                .add(weighted(50, boundRule.getExceptionResponseWeight()));
        assertEquals(0, review.getAdjustedTotalScore().compareTo(expectedAdjusted));

        // 原分绝不回写：快照分数/SHA-256 不变；修正值只并列存在申诉单上
        ScoreSnapshot reloaded = periodService.requireSnapshot(snap.getId());
        assertEquals(0, reloaded.getOnTimeScore().compareTo(new BigDecimal("50.00")));
        assertEquals(0, reloaded.getTotalScore().compareTo(new BigDecimal("50.00")));
        assertEquals(shaBefore, reloaded.getSnapshotSha256());
        ScoreAppeal appealDone = appealService.requireAppeal(appeal.getId());
        assertEquals(CcConstants.REVIEW_APPROVED, appealDone.getStatus());
        assertEquals(0, appealDone.getAdjustedMetricScore().compareTo(new BigDecimal("100.00")));
        assertEquals(0, appealDone.getAdjustedTotalScore().compareTo(expectedAdjusted));

        ImprovementTask task = taskService.requireTask(review.getGeneratedTaskId());
        assertEquals(c1, task.getCarrierId());
        assertEquals(CcConstants.IMPROVE_OPEN, task.getStatus());
        assertNotNull(task.getDueTime());
        BusinessException stealTask = assertThrows(BusinessException.class, () ->
                testSubjects.runAs(11L, "carrier2",
                        () -> taskService.finish(task.getId(), "越权完成", true)));
        assertTrue(stealTask.getMessage().contains("其他承运商"));
    }

    // ====================================================================
    // 5) 改进任务逾期自动提醒：失败留痕重试，成功一次后不再重复（用 2026-05 周期）
    // ====================================================================
    @Test
    void overdueTaskReminderRetriesThenNotifiesOnce() {
        long c1 = carrierId("CY100000001");
        LocalDateTime may = LocalDateTime.of(2026, 5, 15, 10, 0);
        ScorePeriod period = periodService.ensurePeriod(may);
        long a = signedCleanBatch(c1, may, may.plusHours(2));
        tempMonitor.verdicts.put(a, true);
        periodService.closePeriod(period.getId(), CcConstants.TRIGGER_MANUAL,
                LocalDateTime.of(2026, 6, 5, 8, 0));
        ScoreSnapshot snap = snapshotOf(period.getId(), c1);

        scoreClock.setFixed(LocalDateTime.of(2026, 6, 6, 10, 0));
        ScoreAppealSubmitRequest submit = new ScoreAppealSubmitRequest();
        submit.setSnapshotId(snap.getId());
        submit.setMetricCode(CcConstants.METRIC_SIGN_INTEGRITY);
        submit.setReason("签收完整度存疑，申请复核");
        ScoreAppeal appeal = testSubjects.apply(3L, "carrier", () -> appealService.submit(submit));
        ScoreAppealEvidenceRequest ev = new ScoreAppealEvidenceRequest();
        ev.setAppealId(appeal.getId());
        ev.setFileName("sign-check.txt");
        ev.setFileUrl("/v15/sign-check");
        ev.setNote("签收核对记录");
        testSubjects.runAs(3L, "carrier", () -> appealService.appendEvidence(ev));
        Long evidenceId = appealService.listEvidence(appeal.getId()).get(0).getId();

        ScoreAppealReviewRequest req = reviewReq(appeal.getId(), false,
                new ArrayList<>(Collections.singletonList(evidenceId)));
        req.setReviewNote("申诉驳回，但要求承运商加强在途签收核对");
        req.setImprovementContent("整改签收核对流程并提交培训记录");
        ScoreAppealReview review = testSubjects.apply(6L, "qa", () -> appealService.review(req));
        Long taskId = review.getGeneratedTaskId();
        assertNotNull(taskId);

        ImprovementTask task = taskService.requireTask(taskId);
        task.setDueTime(LocalDateTime.now().minusDays(1));
        improvementTaskMapper.updateById(task);

        // webhook 对本任务前两次失败、第三次成功（其他用例遗留任务不受影响）
        ReflectionTestUtils.setField(notifyService, "endpoint", "http://score-notify.test/v15");
        notifyTransport.failForTaskId = taskId;
        notifyTransport.failuresRemaining = 2;

        taskService.scanOverdue(LocalDateTime.now());
        List<?> a1 = notifyService.listAttempts(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId);
        assertEquals(1, a1.size());
        assertEquals(CcConstants.NOTIFY_FAILED,
                ((com.evops.coldchain.entity.ScoreNotifyAttempt) a1.get(0)).getSendStatus());
        assertFalse(notifyService.isNotified(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId));

        taskService.scanOverdue(LocalDateTime.now());
        assertEquals(2, notifyService.listAttempts(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId).size());

        Map<String, Object> s3 = taskService.scanOverdue(LocalDateTime.now());
        assertNotNull(s3);
        List<com.evops.coldchain.entity.ScoreNotifyAttempt> a3 =
                notifyService.listAttempts(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId);
        assertEquals(3, a3.size(), "第三次尝试成功，提醒送达一次");
        assertEquals(CcConstants.NOTIFY_SUCCESS, a3.get(2).getSendStatus());
        assertTrue(notifyService.isNotified(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId));

        // 成功后再扫不重复；完成任务后再扫也不提醒
        taskService.scanOverdue(LocalDateTime.now());
        taskService.scanOverdue(LocalDateTime.now());
        assertEquals(3, notifyService.listAttempts(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId).size(),
                "逾期提醒成功送达后不得重复通知");
        testSubjects.runAs(3L, "carrier",
                () -> taskService.finish(taskId, "已完成签收培训并留存记录", true));
        taskService.scanOverdue(LocalDateTime.now());
        assertEquals(3, notifyService.listAttempts(CcConstants.SCORE_NOTIFY_TASK_OVERDUE, null, taskId).size());
        assertEquals(CcConstants.IMPROVE_DONE, taskService.requireTask(taskId).getStatus());
        notifyTransport.failForTaskId = null;
    }

    // ---------------- 辅助 ----------------

    private BigDecimal weighted(double score, int weight) {
        return BigDecimal.valueOf(score).multiply(BigDecimal.valueOf(weight))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private ScoreMetricEvidence evidence(List<ScoreMetricEvidence> rows, String code) {
        return rows.stream().filter(e -> code.equals(e.getMetricCode())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标取证: " + code));
    }

    private ScoreAppealReviewRequest reviewReq(Long appealId, boolean approved, List<Long> evidenceIds) {
        ScoreAppealReviewRequest req = new ScoreAppealReviewRequest();
        req.setAppealId(appealId);
        req.setApproved(approved);
        req.setReviewNote("QA 复核：" + (approved ? "申诉成立" : "申诉驳回"));
        req.setEvidenceIds(evidenceIds);
        return req;
    }

    private HttpStatus postReviewHttp(String user, ScoreAppealReviewRequest body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.withBasicAuth(user, user + "123")
                .postForEntity(url("/scores/appeals/reviews"),
                        new org.springframework.http.HttpEntity<>(body, headers), String.class).getStatusCode();
    }

    private ScoreSnapshot snapshotOf(Long periodId, long carrierId) {
        return periodService.listSnapshotsByPeriod(periodId).stream()
                .filter(s -> s.getCarrierId() == carrierId)
                .findFirst().orElseThrow(() -> new AssertionError("未找到承运商#" + carrierId + " 的快照"));
    }

    private long carrierId(String carrierNo) {
        Carrier c = carrierMapper.selectOne(new QueryWrapper<Carrier>()
                .eq("carrier_no", carrierNo).last("LIMIT 1"));
        assertNotNull(c, "种子承运商不存在: " + carrierNo);
        return c.getId();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/cold-chain" + path;
    }

    /** 干净批次：准时/温控合格由外部判定/无拒签无修正/无人工异常 */
    private long signedCleanBatch(long carrierId, LocalDateTime signTime, LocalDateTime committed) {
        long batchId = shipBatch(carrierId, signTime, "V15干净");
        signAndBackdate(batchId, signTime, committed);
        return batchId;
    }

    /** 问题批次：不准时 + 收货拒签（签收不完整）+ 人工延误确认严重滞后（异常响应失分） */
    private long signedBadBatch(long carrierId, LocalDateTime signTime, LocalDateTime committed) {
        long batchId = shipBatch(carrierId, signTime, "V15问题");

        BatchRejectRequest reject = new BatchRejectRequest();
        reject.setBatchId(batchId);
        reject.setRejectBy("院药库李老师");
        reject.setReason("到货外箱破损，现场拒收留痕后换货补送");
        batchService.reject(reject);

        ExceptionMarkRequest mark = new ExceptionMarkRequest();
        mark.setBatchId(batchId);
        mark.setExceptionType(CcConstants.EXC_DELAY);
        mark.setDescription("司机报备道路管制，确认处置严重滞后");
        mark.setOccurredTime(LocalDateTime.now().minusHours(5));
        ExceptionEvent delay = batchService.markException(mark);
        lifecycle.confirm(delay.getId());

        resolveAllExceptions(batchId);
        signAndBackdate(batchId, signTime, committed);
        return batchId;
    }

    private void resolveAllExceptions(long batchId) {
        for (ExceptionEvent e : new ArrayList<>(batchService.listExceptions(batchId))) {
            if (CcConstants.EXC_RESOLVED.equals(e.getStatus())) {
                continue;
            }
            if (CcConstants.EXC_OPEN.equals(e.getStatus())) {
                lifecycle.confirm(e.getId());
            }
            EvidenceAppendRequest ev = new EvidenceAppendRequest();
            ev.setExceptionId(e.getId());
            ev.setFileName("close-" + e.getId() + ".txt");
            ev.setFileUrl("/v15/close/" + e.getId());
            ev.setNote("关闭异常的处置证据");
            batchService.appendEvidence(ev);
            List<com.evops.coldchain.entity.ExceptionEvidence> evs = batchService.listEvidence(e.getId());
            Long evId = evs.get(evs.size() - 1).getId();
            if (CcConstants.EXC_CONFIRMED.equals(lifecycle.requireEvent(e.getId()).getStatus())) {
                lifecycle.conclude(e.getId(), "处置完成并评估药品质量无影响",
                        new ArrayList<>(Collections.singletonList(evId)));
            }
            if (CcConstants.EXC_REVIEWING.equals(lifecycle.requireEvent(e.getId()).getStatus())) {
                testSubjects.runAs(6L, "qa", () -> lifecycle.review(e.getId(), true, "QA 复核关闭"));
            }
        }
    }

    private void signAndBackdate(long batchId, LocalDateTime signTime, LocalDateTime committed) {
        TransportBatch b = batchService.requireBatch(batchId);
        com.evops.coldchain.dto.SignRequest sign = new com.evops.coldchain.dto.SignRequest();
        sign.setBatchId(batchId);
        sign.setSignBy("院药库签收人");
        sign.setReceivedApprovalNo(b.getApprovalNoSnap());
        sign.setReceivedPackageSpec(b.getPackageSpecSnap());
        sign.setConfirmQty(b.getShipQty());
        batchService.sign(sign);

        // 演示环境无补签接口：测试内把签收时间归位到评分周期内，并固化承诺到达时间（准时率取证锚点）
        TransportBatch signed = batchMapper.selectById(batchId);
        signed.setSignTime(signTime);
        signed.setCommittedArrivalTime(committed);
        batchMapper.updateById(signed);
    }

    private long shipBatch(long carrierId, LocalDateTime signTime, String label) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName(label + "-" + signTime.getMonthValue() + "-" + nano);
        dc.setApprovalNo("国药准字V15-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V15验收医院-" + signTime.getMonthValue());
        oc.setShipQty(10);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测V15·" + signTime.getMonthValue() + "·" + Math.abs(nano % 1000000));
        vr.setCarrierName("V15验收物流");
        vr.setDriverName("赵司机");
        Vehicle vehicle = vehicleService.create(vr);
        vehicle.setCarrierId(carrierId);
        vehicleMapper.updateById(vehicle);

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V15-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V15-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V15校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v15bind-" + nano);
        pb.setVehicleId(vehicle.getId());
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicle.getId());
        long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    private ScoreRuleDraftRequest ruleNextVersion(int versionNo) {
        ScoreRuleDraftRequest req = new ScoreRuleDraftRequest();
        req.setVersionName("V" + versionNo + " 准时率优先（40/20/20/20）");
        req.setOnTimeWeight(40);
        req.setTempControlWeight(20);
        req.setSignIntegrityWeight(20);
        req.setExceptionResponseWeight(20);
        req.setOnTimeGraceMinutes(0);
        req.setAppealOpenDays(7);
        req.setImprovementDueDays(7);
        req.setChangeReason("V15 验收：验证规则调整只影响下一周期");
        return req;
    }
}
