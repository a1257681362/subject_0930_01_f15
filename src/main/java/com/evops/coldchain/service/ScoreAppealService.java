package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ScoreAppealEvidenceRequest;
import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.dto.ScoreAppealSubmitRequest;
import com.evops.coldchain.entity.ImprovementTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreAppealEvidence;
import com.evops.coldchain.entity.ScoreAppealReview;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.mapper.ScoreAppealEvidenceMapper;
import com.evops.coldchain.mapper.ScoreAppealMapper;
import com.evops.coldchain.mapper.ScoreAppealReviewMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评分申诉与复核（V15）。
 *
 * 铁律：申诉期间与复核后，快照原分/原取证都绝不回写（快照表无更新路径）。
 * 申诉成立只追加：复核结论行（必须引用申诉证据）+ 申诉单上的并列修正值（adjusted*），
 * 视图层把“原分”与“修正后”并列展示；同时产生改进任务。
 */
@Service
public class ScoreAppealService {

    @Resource private ScoreAppealMapper appealMapper;
    @Resource private ScoreAppealEvidenceMapper evidenceMapper;
    @Resource private ScoreAppealReviewMapper reviewMapper;
    @Resource private ScorePeriodService periodService;
    @Resource private ScoreRuleService ruleService;
    @Resource private CarrierAccountService carrierAccountService;
    @Resource private ImprovementTaskService taskService;
    @Resource private SequenceMapper sequenceMapper;
    @Resource private OpLogService opLogService;
    @Resource private ScoreClock scoreClock;

    /** 承运商提交申诉：只能申诉本企业快照、限一个指标、须在申诉期内；提交后原分不回写 */
    @Transactional
    public ScoreAppeal submit(ScoreAppealSubmitRequest req) {
        Long carrierId = carrierAccountService.requireCurrentCarrierId();
        ScoreSnapshot snap = periodService.requireSnapshot(req.getSnapshotId());
        if (!carrierId.equals(snap.getCarrierId())) {
            throw new BusinessException("只能对本企业的评分快照提交申诉，禁止跨企业访问");
        }
        if (!CcConstants.METRIC_ALL.contains(req.getMetricCode())) {
            throw new BusinessException("被申诉指标代码非法: " + req.getMetricCode());
        }
        if (req.getReason() == null || req.getReason().trim().isEmpty()) {
            throw new BusinessException("申诉理由不能为空");
        }
        ScoreRule rule = ruleService.requireById(snap.getRuleId());
        LocalDateTime deadline = snap.getFrozenTime().plusDays(rule.getAppealOpenDays() == null ? 0 : rule.getAppealOpenDays());
        LocalDateTime now = scoreClock.now();
        if (now.isAfter(deadline)) {
            throw new BusinessException("申诉期已截止（" + deadline + "），该快照不再受理申诉");
        }
        Long dup = appealMapper.selectCount(new QueryWrapper<ScoreAppeal>()
                .eq("snapshot_id", snap.getId())
                .eq("metric_code", req.getMetricCode())
                .ne("status", CcConstants.REVIEW_REJECTED));
        if (dup != null && dup > 0) {
            throw new BusinessException("该快照的指标[" + req.getMetricCode() + "]已有在途/成立申诉，不得重复提交");
        }

        ScoreAppeal appeal = new ScoreAppeal();
        appeal.setAppealNo("AP" + sequenceMapper.nextAppealNo());
        appeal.setSnapshotId(snap.getId());
        appeal.setPeriodId(snap.getPeriodId());
        appeal.setCarrierId(carrierId);
        appeal.setCarrierNameSnap(snap.getCarrierNameSnap());
        appeal.setMetricCode(req.getMetricCode());
        appeal.setReason(req.getReason().trim());
        appeal.setStatus(CcConstants.APPEAL_SUBMITTED);
        UserPrincipal principal = UserContext.current();
        appeal.setSubmitBy(principal.getId());
        appeal.setSubmitName(principal.getUsername());
        appeal.setSubmitTime(now);
        appeal.setAppealDeadline(deadline);
        appealMapper.insert(appeal);

        opLogService.log(CcConstants.ENT_SCORE_APPEAL, appeal.getId(), null, CcConstants.ACT_SCORE_APPEAL_SUBMIT,
                String.format("承运商 %s 对周期 %s 快照#%d 指标 %s 提交申诉（单号 %s）；申诉期间原分不回写",
                        snap.getCarrierNameSnap(), snap.getPeriodCode(), snap.getId(),
                        req.getMetricCode(), appeal.getAppealNo()));
        return appeal;
    }

    /** 追加申诉证据（只追加；必须是本企业且申诉在途） */
    @Transactional
    public ScoreAppealEvidence appendEvidence(ScoreAppealEvidenceRequest req) {
        Long carrierId = carrierAccountService.requireCurrentCarrierId();
        ScoreAppeal appeal = requireAppeal(req.getAppealId());
        if (!carrierId.equals(appeal.getCarrierId())) {
            throw new BusinessException("只能为本企业申诉追加证据");
        }
        if (!CcConstants.APPEAL_SUBMITTED.equals(appeal.getStatus())) {
            throw new BusinessException("申诉已复核（" + appeal.getStatus() + "），不能再追加证据");
        }
        ScoreAppealEvidence ev = new ScoreAppealEvidence();
        ev.setAppealId(appeal.getId());
        ev.setFileName(req.getFileName().trim());
        ev.setFileUrl(req.getFileUrl().trim());
        ev.setNote(req.getNote());
        evidenceMapper.insert(ev);
        return ev;
    }

    /**
     * QA 复核：结论必须关联本申诉证据（至少一条且属于该申诉）。
     * 成立→并列修正分（不改快照）+ 产生改进任务；驳回→可选产生改进任务。
     */
    @Transactional
    public ScoreAppealReview review(ScoreAppealReviewRequest req) {
        ScoreAppeal appeal = requireAppeal(req.getAppealId());
        if (!CcConstants.APPEAL_SUBMITTED.equals(appeal.getStatus())) {
            throw new BusinessException("申诉已复核（" + appeal.getStatus() + "），不能重复复核");
        }
        if (req.getReviewNote() == null || req.getReviewNote().trim().isEmpty()) {
            throw new BusinessException("复核意见不能为空");
        }
        List<Long> evidenceIds = req.getEvidenceIds() == null ? new ArrayList<>() : req.getEvidenceIds();
        if (evidenceIds.isEmpty()) {
            throw new BusinessException("复核结论必须关联申诉证据（至少引用一条）");
        }
        List<ScoreAppealEvidence> owned = evidenceMapper.selectBatchIds(evidenceIds);
        if (owned.size() != evidenceIds.size()
                || owned.stream().anyMatch(e -> !appeal.getId().equals(e.getAppealId()))) {
            throw new BusinessException("引用的申诉证据不存在或不属于本申诉，复核结论必须关联本申诉证据");
        }

        boolean approved = Boolean.TRUE.equals(req.getApproved());
        BigDecimal adjustedMetric = null;
        BigDecimal adjustedTotal = null;
        String adjustNote = null;
        ScoreSnapshot snap = periodService.requireSnapshot(appeal.getSnapshotId());
        if (approved) {
            if (req.getAdjustedMetricScore() == null) {
                throw new BusinessException("申诉成立必须给出并列修正后的指标分（快照原分不变）");
            }
            adjustedMetric = req.getAdjustedMetricScore().setScale(2, RoundingMode.HALF_UP);
            if (adjustedMetric.compareTo(BigDecimal.ZERO) < 0 || adjustedMetric.compareTo(new BigDecimal("100.00")) > 0) {
                throw new BusinessException("修正后指标分必须在 0~100 之间");
            }
            if (req.getAdjustNote() == null || req.getAdjustNote().trim().isEmpty()) {
                throw new BusinessException("申诉成立必须填写修正说明");
            }
            adjustNote = req.getAdjustNote().trim();
            adjustedTotal = recalcTotal(snap, appeal.getMetricCode(), adjustedMetric);
        }

        // 先落复核结论行（任务还未创建，generatedTaskId 稍后回填）
        ScoreAppealReview review = new ScoreAppealReview();
        review.setAppealId(appeal.getId());
        review.setResult(approved ? CcConstants.REVIEW_APPROVED : CcConstants.REVIEW_REJECTED);
        review.setReviewNote(req.getReviewNote().trim());
        review.setEvidenceRefs(evidenceIds.stream().map(String::valueOf).sorted().collect(Collectors.joining(",")));
        review.setAdjustedMetricScore(adjustedMetric);
        review.setAdjustedTotalScore(adjustedTotal);
        review.setAdjustNote(adjustNote);
        LocalDateTime now = scoreClock.now();
        UserPrincipal principal = UserContext.current();
        review.setReviewBy(principal.getId());
        review.setReviewName(principal.getUsername());
        review.setReviewTime(now);
        reviewMapper.insert(review);

        // 结论产生改进任务：成立必须给整改要求；驳回且给了整改要求也生成
        String content = req.getImprovementContent() == null ? null : req.getImprovementContent().trim();
        if (approved && (content == null || content.isEmpty())) {
            throw new BusinessException("申诉成立的复核结论必须明确整改要求并产生改进任务");
        }
        if (content != null && !content.isEmpty()) {
            ScoreRule rule = ruleService.requireById(snap.getRuleId());
            int dueDays = req.getImprovementDueDays() != null ? req.getImprovementDueDays()
                    : (rule.getImprovementDueDays() == null ? 7 : rule.getImprovementDueDays());
            if (dueDays < 0) {
                throw new BusinessException("整改期限不能为负");
            }
            ImprovementTask task = taskService.createFromReview(appeal, snap, review, content, dueDays, now);
            // 复核结论行整体只追加；此处 generatedTaskId 是同一复核事务内、行对外可见前的关联回填
            // （任务外键 review_id 要求结论行先落库），不改变结论/证据引用等任何已成立事实
            review.setGeneratedTaskId(task.getId());
            reviewMapper.updateById(review);
        }

        // 申诉单状态推进与并列修正回执（快照原分仍不动）
        appeal.setStatus(approved ? CcConstants.REVIEW_APPROVED : CcConstants.REVIEW_REJECTED);
        appeal.setReviewBy(principal.getId());
        appeal.setReviewName(principal.getUsername());
        appeal.setReviewTime(now);
        appeal.setReviewResult(review.getResult());
        appeal.setReviewNote(review.getReviewNote());
        if (approved) {
            appeal.setAdjustedMetricScore(adjustedMetric);
            appeal.setAdjustedTotalScore(adjustedTotal);
            appeal.setAdjustNote(adjustNote);
        }
        appealMapper.updateById(appeal);

        opLogService.log(CcConstants.ENT_SCORE_APPEAL, appeal.getId(), null, CcConstants.ACT_SCORE_APPEAL_REVIEW,
                String.format("申诉 %s 复核%s：关联证据=%s；快照原分不回写%s",
                        appeal.getAppealNo(), approved ? "成立" : "驳回", review.getEvidenceRefs(),
                        review.getGeneratedTaskId() == null ? "" : "；产生改进任务#" + review.getGeneratedTaskId()));
        return review;
    }

    /** 用快照权重把被申诉指标替换为修正分，重算并列修正总分（仅作展示，不落快照） */
    private BigDecimal recalcTotal(ScoreSnapshot snap, String metricCode, BigDecimal adjustedMetric) {
        ScoreRule rule = ruleService.requireById(snap.getRuleId());
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put(CcConstants.METRIC_ON_TIME, snap.getOnTimeScore());
        scores.put(CcConstants.METRIC_TEMP_CONTROL, snap.getTempControlScore());
        scores.put(CcConstants.METRIC_SIGN_INTEGRITY, snap.getSignIntegrityScore());
        scores.put(CcConstants.METRIC_EXCEPTION_RESPONSE, snap.getExceptionResponseScore());
        scores.put(metricCode, adjustedMetric);
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(weighted(scores.get(CcConstants.METRIC_ON_TIME), rule.getOnTimeWeight()));
        total = total.add(weighted(scores.get(CcConstants.METRIC_TEMP_CONTROL), rule.getTempControlWeight()));
        total = total.add(weighted(scores.get(CcConstants.METRIC_SIGN_INTEGRITY), rule.getSignIntegrityWeight()));
        total = total.add(weighted(scores.get(CcConstants.METRIC_EXCEPTION_RESPONSE), rule.getExceptionResponseWeight()));
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal weighted(BigDecimal score, Integer weight) {
        return score.multiply(BigDecimal.valueOf(weight == null ? 0 : weight))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    // ---------------- 查询 ----------------

    public ScoreAppeal requireAppeal(Long id) {
        ScoreAppeal appeal = appealMapper.selectById(id);
        if (appeal == null) {
            throw new BusinessException("评分申诉不存在: " + id);
        }
        return appeal;
    }

    /** 承运商：只看本企业申诉（强制按绑定 carrierId 过滤） */
    public List<ScoreAppeal> listForCurrentCarrier() {
        Long carrierId = carrierAccountService.requireCurrentCarrierId();
        return appealMapper.selectList(new QueryWrapper<ScoreAppeal>()
                .eq("carrier_id", carrierId).orderByDesc("id"));
    }

    /** 承运商：某张本企业快照下自己的申诉（快照详情内嵌“申诉入口/申诉状态”用） */
    public List<ScoreAppeal> listBySnapshotForCarrier(Long snapshotId, Long carrierId) {
        return appealMapper.selectList(new QueryWrapper<ScoreAppeal>()
                .eq("snapshot_id", snapshotId)
                .eq("carrier_id", carrierId)
                .orderByAsc("id"));
    }

    /** 管理侧：全部申诉 */
    public List<ScoreAppeal> listAll() {
        return appealMapper.selectList(new QueryWrapper<ScoreAppeal>().orderByDesc("id"));
    }

    /** 待复核列表（QA） */
    public List<ScoreAppeal> listPending() {
        return appealMapper.selectList(new QueryWrapper<ScoreAppeal>()
                .eq("status", CcConstants.APPEAL_SUBMITTED).orderByAsc("id"));
    }

    public List<ScoreAppealEvidence> listEvidence(Long appealId) {
        return evidenceMapper.selectList(new QueryWrapper<ScoreAppealEvidence>()
                .eq("appeal_id", appealId).orderByAsc("id"));
    }

    public List<ScoreAppealReview> listReviews(Long appealId) {
        return reviewMapper.selectList(new QueryWrapper<ScoreAppealReview>()
                .eq("appeal_id", appealId).orderByAsc("id"));
    }

    /** 详情（承运商调用时服务层先做本企业校验） */
    public Map<String, Object> detail(Long appealId, boolean enforceSelf) {
        ScoreAppeal appeal = requireAppeal(appealId);
        if (enforceSelf) {
            carrierAccountService.requireSelf(appeal.getCarrierId());
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("appeal", appeal);
        view.put("evidences", listEvidence(appealId));
        view.put("reviews", listReviews(appealId));
        view.put("tasks", taskService.listByAppeal(appealId));
        ScoreSnapshot snap = periodService.requireSnapshot(appeal.getSnapshotId());
        // 原分与并列修正同时给出，明确“原分不回写”
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("snapshotId", snap.getId());
        original.put("metricCode", appeal.getMetricCode());
        original.put("originalMetricScore", metricScore(snap, appeal.getMetricCode()));
        original.put("originalTotalScore", snap.getTotalScore());
        original.put("immutable", true);
        original.put("snapshotSha256", snap.getSnapshotSha256());
        view.put("originalScore", original);
        return view;
    }

    private BigDecimal metricScore(ScoreSnapshot snap, String metricCode) {
        switch (metricCode) {
            case CcConstants.METRIC_ON_TIME: return snap.getOnTimeScore();
            case CcConstants.METRIC_TEMP_CONTROL: return snap.getTempControlScore();
            case CcConstants.METRIC_SIGN_INTEGRITY: return snap.getSignIntegrityScore();
            case CcConstants.METRIC_EXCEPTION_RESPONSE: return snap.getExceptionResponseScore();
            default: throw new BusinessException("未知指标: " + metricCode);
        }
    }
}
