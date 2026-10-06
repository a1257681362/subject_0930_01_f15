package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.dto.ScoreRuleDraftRequest;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreMetricEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.service.ImprovementTaskService;
import com.evops.coldchain.service.ScoreAppealService;
import com.evops.coldchain.service.ScorePeriodService;
import com.evops.coldchain.service.ScoreRuleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承运商服务评分管理侧（V15）：规则版本、周期关闭、全量快照台账、申诉复核、改进任务督办。
 * 承运商只能看自己的口径在 {@link ScoreCarrierSelfController}。
 */
@RestController
@RequestMapping("/api/cold-chain/scores")
public class ScoreAdminController {

    private final ScoreRuleService ruleService;
    private final ScorePeriodService periodService;
    private final ScoreAppealService appealService;
    private final ImprovementTaskService taskService;

    public ScoreAdminController(ScoreRuleService ruleService, ScorePeriodService periodService,
                                ScoreAppealService appealService, ImprovementTaskService taskService) {
        this.ruleService = ruleService;
        this.periodService = periodService;
        this.appealService = appealService;
        this.taskService = taskService;
    }

    // —— 规则版本（调整只影响下一周期）——
    @GetMapping("/rules")
    public ApiResponse<List<?>> listRules() {
        return ApiResponse.ok(ruleService.listVersions());
    }

    @PostMapping("/rules/publish")
    public ApiResponse<?> publishRule(@Valid @RequestBody ScoreRuleDraftRequest req) {
        return ApiResponse.ok(ruleService.publish(req));
    }

    // —— 周期与关闭 ——
    @GetMapping("/periods")
    public ApiResponse<List<ScorePeriod>> listPeriods() {
        return ApiResponse.ok(periodService.listPeriods());
    }

    /** 手动扫描：关闭所有已到期未关闭周期（外部依赖阻断时返回 BLOCKED 原因，不产出快照） */
    @PostMapping("/periods/close-scan")
    public ApiResponse<Map<String, Object>> closeScan() {
        return ApiResponse.ok(periodService.closeDuePeriods(LocalDateTime.now(),
                com.evops.coldchain.CcConstants.TRIGGER_MANUAL));
    }

    @PostMapping("/periods/{periodId}/close")
    public ApiResponse<Map<String, Object>> closeOne(@PathVariable Long periodId) {
        return ApiResponse.ok(periodService.closePeriod(periodId,
                com.evops.coldchain.CcConstants.TRIGGER_MANUAL, LocalDateTime.now()));
    }

    @GetMapping("/periods/{periodId}/close-attempts")
    public ApiResponse<List<?>> closeAttempts(@PathVariable Long periodId) {
        return ApiResponse.ok(periodService.listCloseAttempts(periodId));
    }

    // —— 全量快照台账 ——
    @GetMapping("/snapshots")
    public ApiResponse<List<ScoreSnapshot>> listSnapshots(@RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(periodId == null
                ? periodService.listSnapshotsByPeriod(null)
                : periodService.listSnapshotsByPeriod(periodId));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public ApiResponse<Map<String, Object>> snapshotDetail(@PathVariable Long snapshotId) {
        return ApiResponse.ok(snapshotView(snapshotId, false, null));
    }

    // —— 申诉复核（QA）——
    @GetMapping("/appeals/pending")
    public ApiResponse<List<ScoreAppeal>> pendingAppeals() {
        return ApiResponse.ok(appealService.listPending());
    }

    @GetMapping("/appeals")
    public ApiResponse<List<ScoreAppeal>> allAppeals() {
        return ApiResponse.ok(appealService.listAll());
    }

    @GetMapping("/appeals/{appealId}")
    public ApiResponse<Map<String, Object>> appealDetail(@PathVariable Long appealId) {
        return ApiResponse.ok(appealService.detail(appealId, false));
    }

    @PostMapping("/appeals/reviews")
    public ApiResponse<?> reviewAppeal(@Valid @RequestBody ScoreAppealReviewRequest req) {
        return ApiResponse.ok(appealService.review(req));
    }

    // —— 改进任务督办 ——
    @GetMapping("/tasks")
    public ApiResponse<List<?>> listTasks() {
        return ApiResponse.ok(taskService.list(false));
    }

    private Map<String, Object> snapshotView(Long snapshotId, boolean self, Long carrierId) {
        ScoreSnapshot snap = periodService.requireSnapshot(snapshotId);
        List<ScoreMetricEvidence> evidences = periodService.listEvidence(snapshotId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("snapshot", snap);
        view.put("metricEvidences", evidences);
        view.put("immutable", true);
        if (self) {
            view.put("appeals", appealService.listBySnapshotForCarrier(snapshotId, carrierId));
        }
        return view;
    }
}
