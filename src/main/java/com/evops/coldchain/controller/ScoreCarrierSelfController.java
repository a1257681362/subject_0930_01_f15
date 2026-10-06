package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ImprovementFinishRequest;
import com.evops.coldchain.dto.ScoreAppealEvidenceRequest;
import com.evops.coldchain.dto.ScoreAppealSubmitRequest;
import com.evops.coldchain.entity.ScoreAppealEvidence;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreMetricEvidence;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.service.CarrierAccountService;
import com.evops.coldchain.service.ImprovementTaskService;
import com.evops.coldchain.service.ScoreAppealService;
import com.evops.coldchain.service.ScorePeriodService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承运商自助评分视图与申诉入口（V15）。
 * 所有数据访问在服务层按登录账号绑定的 carrierId 强制隔离，
 * 路径里的 snapshotId/appealId/taskId 若属于别的企业，服务层抛 BusinessException 拒绝。
 */
@RestController
@RequestMapping("/api/cold-chain/my-scores")
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class ScoreCarrierSelfController {

    private final ScorePeriodService periodService;
    private final ScoreAppealService appealService;
    private final ImprovementTaskService taskService;
    private final CarrierAccountService carrierAccountService;

    public ScoreCarrierSelfController(ScorePeriodService periodService, ScoreAppealService appealService,
                                      ImprovementTaskService taskService, CarrierAccountService carrierAccountService) {
        this.periodService = periodService;
        this.appealService = appealService;
        this.taskService = taskService;
        this.carrierAccountService = carrierAccountService;
    }

    /** 本企业历史快照列表（只返回自己的，服务层过滤） */
    @GetMapping("/snapshots")
    public ApiResponse<List<ScoreSnapshot>> mySnapshots() {
        Long carrierId = carrierAccountService.requireCurrentCarrierId();
        return ApiResponse.ok(periodService.listSnapshotsByCarrier(carrierId));
    }

    /** 本企业快照详情：四指标取证明细 + 内嵌申诉入口状态（跨企业 snapshotId 被服务层拦截） */
    @GetMapping("/snapshots/{snapshotId}")
    public ApiResponse<Map<String, Object>> mySnapshotDetail(@PathVariable Long snapshotId) {
        Long carrierId = carrierAccountService.requireCurrentCarrierId();
        ScoreSnapshot snap = periodService.requireSnapshot(snapshotId);
        carrierAccountService.requireSelf(snap.getCarrierId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("snapshot", snap);
        view.put("metricEvidences", periodService.listEvidence(snapshotId));
        view.put("appealEntry", buildAppealEntry(snap, carrierId));
        view.put("originalScoreWriteProtected", true);
        return ApiResponse.ok(view);
    }

    /** 提交申诉（一次针对一个指标，申诉期内；原分不回写） */
    @PostMapping("/appeals")
    public ApiResponse<?> submitAppeal(@Valid @RequestBody ScoreAppealSubmitRequest req) {
        return ApiResponse.ok(appealService.submit(req));
    }

    /** 追加申诉证据（只追加） */
    @PostMapping("/appeals/evidences")
    public ApiResponse<ScoreAppealEvidence> appendEvidence(@Valid @RequestBody ScoreAppealEvidenceRequest req) {
        return ApiResponse.ok(appealService.appendEvidence(req));
    }

    /** 本企业申诉列表 */
    @GetMapping("/appeals")
    public ApiResponse<List<ScoreAppeal>> myAppeals() {
        return ApiResponse.ok(appealService.listForCurrentCarrier());
    }

    /** 本企业申诉详情（服务层校验归属；含原分快照、证据、复核结论与改进任务） */
    @GetMapping("/appeals/{appealId}")
    public ApiResponse<Map<String, Object>> myAppealDetail(@PathVariable Long appealId) {
        return ApiResponse.ok(appealService.detail(appealId, true));
    }

    /** 本企业改进任务列表 */
    @GetMapping("/tasks")
    public ApiResponse<List<?>> myTasks() {
        return ApiResponse.ok(taskService.list(true));
    }

    /** 完成本企业改进任务（只进不退） */
    @PostMapping("/tasks/finish")
    public ApiResponse<?> finishTask(@Valid @RequestBody ImprovementFinishRequest req) {
        return ApiResponse.ok(taskService.finish(req.getTaskId(), req.getFinishNote(), true));
    }

    private Map<String, Object> buildAppealEntry(ScoreSnapshot snap, Long carrierId) {
        List<ScoreAppeal> appeals = appealService.listBySnapshotForCarrier(snap.getId(), carrierId);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("metrics", com.evops.coldchain.CcConstants.METRIC_ALL);
        entry.put("appeals", appeals);
        entry.put("note", "申诉期间与复核后原分均不回写；申诉成立的修正值与原分并列展示");
        return entry;
    }
}
