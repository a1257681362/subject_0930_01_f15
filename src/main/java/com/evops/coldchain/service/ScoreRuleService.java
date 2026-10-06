package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ScoreRuleDraftRequest;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.mapper.ScoreRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评分规则版本（V15）：发布热替换。新版本只影响“此后创建”的评分周期——
 * 已存在周期（含关闭中/已关闭）绑定的 ruleId 不变，取证与快照永远按绑定版本。
 */
@Service
public class ScoreRuleService {

    @Resource
    private ScoreRuleMapper ruleMapper;
    @Resource
    private OpLogService opLogService;

    /** 当前生效规则版本（任何时候恰好一条；无则报错，种子 V1 兜底） */
    public ScoreRule requireEffective() {
        ScoreRule rule = ruleMapper.selectOne(new QueryWrapper<ScoreRule>()
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
        if (rule == null) {
            throw new BusinessException("缺少生效中的评分规则版本，请先发布");
        }
        return rule;
    }

    @Transactional
    public ScoreRule publish(ScoreRuleDraftRequest req) {
        validateWeights(req);
        ScoreRule current = ruleMapper.selectOne(new QueryWrapper<ScoreRule>()
                .eq("status", CcConstants.EFFECTIVE).last("LIMIT 1"));
        int nextVersion = (current == null ? 1 : current.getVersionNo() + 1);

        ScoreRule rule = new ScoreRule();
        rule.setVersionNo(nextVersion);
        rule.setVersionName(req.getVersionName().trim());
        rule.setOnTimeWeight(req.getOnTimeWeight());
        rule.setTempControlWeight(req.getTempControlWeight());
        rule.setSignIntegrityWeight(req.getSignIntegrityWeight());
        rule.setExceptionResponseWeight(req.getExceptionResponseWeight());
        rule.setOnTimeGraceMinutes(req.getOnTimeGraceMinutes() == null ? 0 : req.getOnTimeGraceMinutes());
        rule.setAppealOpenDays(req.getAppealOpenDays() == null ? 7 : req.getAppealOpenDays());
        rule.setImprovementDueDays(req.getImprovementDueDays() == null ? 7 : req.getImprovementDueDays());
        rule.setChangeReason(req.getChangeReason());
        rule.setStatus(CcConstants.EFFECTIVE);
        rule.setPublishTime(LocalDateTime.now());
        UserPrincipal principal = UserContext.current();
        rule.setPublishBy(principal.getId());
        rule.setPublishName(principal.getUsername());
        ruleMapper.insert(rule);

        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            ruleMapper.updateById(current);
        }
        opLogService.log(CcConstants.ENT_SCORE_RULE, rule.getId(), null, CcConstants.ACT_SCORE_RULE_PUBLISH,
                String.format("发布评分规则 V%d：%s（权重 准时%d/温控%d/签收%d/响应%d，准时宽限%d分钟，申诉期%d天，整改期限%d天）；"
                                + "旧版本置 SUPERSEDED，调整只影响此后创建的下一周期",
                        nextVersion, rule.getVersionName(),
                        rule.getOnTimeWeight(), rule.getTempControlWeight(),
                        rule.getSignIntegrityWeight(), rule.getExceptionResponseWeight(),
                        rule.getOnTimeGraceMinutes(), rule.getAppealOpenDays(), rule.getImprovementDueDays()));
        return rule;
    }

    private void validateWeights(ScoreRuleDraftRequest req) {
        int sum = nz(req.getOnTimeWeight()) + nz(req.getTempControlWeight())
                + nz(req.getSignIntegrityWeight()) + nz(req.getExceptionResponseWeight());
        if (sum != 100) {
            throw new BusinessException("四项指标权重合计必须=100，当前=" + sum);
        }
        if (nz(req.getOnTimeGraceMinutes()) < 0 || nz(req.getAppealOpenDays()) < 0
                || nz(req.getImprovementDueDays()) < 0) {
            throw new BusinessException("准时宽限/申诉期/整改期限不能为负");
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    public List<ScoreRule> listVersions() {
        return ruleMapper.selectList(new QueryWrapper<ScoreRule>().orderByDesc("version_no"));
    }

    public ScoreRule requireById(Long ruleId) {
        ScoreRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("评分规则版本不存在: " + ruleId);
        }
        return rule;
    }
}
