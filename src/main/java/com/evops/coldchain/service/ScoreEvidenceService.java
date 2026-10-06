package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.SignCorrection;
import com.evops.coldchain.entity.SignReceipt;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.SignCorrectionMapper;
import com.evops.coldchain.mapper.SignReceiptMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四指标逐项取证（V15）：准时率 / 温控合格率 / 签收完整度 / 异常响应。
 *
 * 每个指标独立给出分子（达标数）/分母（应评数）/逐批次取值明细与来源锚点，
 * 指标分=100×分子/分母（分母为 0 的指标给 100 并在明细注明“无应评样本”，
 * 但 {@link #TEMP_CONTROL} 例外：其判定全部来自外部温控平台，平台必须覆盖全部批次，
 * 覆盖不了整个周期关闭就在上层阻断，绝不会落到“无样本默认满分”）。
 */
@Service
public class ScoreEvidenceService {

    @Resource private TransportBatchMapper batchMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private LockedPriceMapper lockedPriceMapper;
    @Resource private ExceptionEventMapper exceptionMapper;
    @Resource private TempAlertMapper tempAlertMapper;
    @Resource private SignReceiptMapper signReceiptMapper;
    @Resource private SignCorrectionMapper signCorrectionMapper;

    /** 一次取证的输入：某承运商在周期内全部已签收批次 + 全量外部温控判定 */
    public static class EvidenceInput {
        public List<TransportBatch> batches;
        public Map<Long, Vehicle> vehicleMap;
        public Map<Long, LockedPrice> lockedPriceMap;
        public Map<Long, Boolean> tempQualifiedByBatch;
        public LocalDateTime tempFetchedAt;
        public String tempBasis;
        public int onTimeGraceMinutes;
    }

    /** 单个指标的取证结果 */
    public static class MetricEval {
        public String metricCode;
        public String metricName;
        public int numerator;
        public int denominator;
        public BigDecimal metricScore;
        public String sourceBasis;
        public List<Map<String, Object>> details = new ArrayList<>();
    }

    /**
     * 取齐某承运商四个指标的证据。温控指标以外部平台客观判定为准；
     * 若该承运商任一批次缺少平台判定，直接抛 {@link ExternalDependencyUnavailableException}
     * （上层整体阻断关闭，绝不默认成功）。
     */
    public List<MetricEval> evaluate(EvidenceInput in) {
        List<MetricEval> evals = new ArrayList<>();
        evals.add(evalOnTime(in));
        evals.add(evalTempControl(in));
        evals.add(evalSignIntegrity(in));
        evals.add(evalExceptionResponse(in));
        return evals;
    }

    // ---------------- 准时率 ----------------
    private MetricEval evalOnTime(EvidenceInput in) {
        MetricEval m = base(CcConstants.METRIC_ON_TIME, "准时率",
                "BATCH_LEDGER:t_cc_transport_batch.committed_arrival_time/sign_time + 规则准时宽限");
        for (TransportBatch b : in.batches) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("batchId", b.getId());
            d.put("batchNo", b.getBatchNo());
            d.put("committedArrivalTime", b.getCommittedArrivalTime());
            d.put("actualSignTime", b.getSignTime());
            d.put("graceMinutes", in.onTimeGraceMinutes);
            if (b.getCommittedArrivalTime() == null || b.getSignTime() == null) {
                // 历史批次没有承诺到达时间：不可判定，剔除分母（明细留痕），不算准时也不算违约
                d.put("excluded", true);
                d.put("excludedReason", "缺少承诺到达时间或签收时间，准时率不可判定，剔除应评分母");
            } else {
                LocalDateTime deadline = b.getCommittedArrivalTime().plusMinutes(in.onTimeGraceMinutes);
                boolean onTime = !b.getSignTime().isAfter(deadline);
                d.put("deadlineWithGrace", deadline);
                d.put("qualified", onTime);
                m.denominator++;
                if (onTime) {
                    m.numerator++;
                }
            }
            m.details.add(d);
        }
        finish(m);
        return m;
    }

    // ---------------- 温控合格率（外部依赖客观判定） ----------------
    private MetricEval evalTempControl(EvidenceInput in) {
        MetricEval m = base(CcConstants.METRIC_TEMP_CONTROL, "温控合格率",
                "EXTERNAL_TEMP_PLATFORM 客观判定；内部交叉锚点 t_cc_temp_alert/t_cc_transport_batch.has_temp_excursion");
        for (TransportBatch b : in.batches) {
            Boolean qualified = in.tempQualifiedByBatch == null ? null : in.tempQualifiedByBatch.get(b.getId());
            if (qualified == null) {
                // 外部依赖没给这个批次判定：禁止默认成功/默认合格，整体阻断
                throw new ExternalDependencyUnavailableException(
                        "外部温控平台缺少批次 " + b.getBatchNo() + "(#" + b.getId() + ") 的客观判定，温控合格率取证阻断");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("batchId", b.getId());
            d.put("batchNo", b.getBatchNo());
            d.put("externalQualified", qualified);
            d.put("externalFetchedAt", in.tempFetchedAt);
            d.put("externalBasis", in.tempBasis);
            // 内部事实仅作交叉核对锚点，打分以外部客观判定为准
            d.put("internalHasTempExcursion", b.getHasTempExcursion());
            List<TempAlert> alerts = tempAlertMapper.selectList(new QueryWrapper<TempAlert>()
                    .eq("batch_id", b.getId()).orderByAsc("id"));
            d.put("internalAlertCount", alerts.size());
            d.put("internalAlertIds", idList(alerts.size(), i -> alerts.get(i).getId()));
            d.put("qualified", qualified);
            m.denominator++;
            if (qualified) {
                m.numerator++;
            }
            m.details.add(d);
        }
        // 分母为 0（该承运商本周期无批次）时不允许走“无样本满分”，由上层保证不会为零批次建快照
        finish(m);
        return m;
    }

    // ---------------- 签收完整度 ----------------
    private MetricEval evalSignIntegrity(EvidenceInput in) {
        MetricEval m = base(CcConstants.METRIC_SIGN_INTEGRITY, "签收完整度",
                "ESIGN:t_cc_sign_receipt/t_cc_sign_correction + EXCEPTION:REJECTION + BATCH:sign_frozen_from");
        for (TransportBatch b : in.batches) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("batchId", b.getId());
            d.put("batchNo", b.getBatchNo());
            d.put("shipQty", b.getShipQty());

            List<String> defects = new ArrayList<>();

            // 1) 收货拒签异常
            List<ExceptionEvent> exceptions = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                    .eq("batch_id", b.getId()).orderByAsc("id"));
            boolean rejection = exceptions.stream()
                    .anyMatch(e -> CcConstants.EXC_REJECTION.equals(e.getExceptionType()));
            if (rejection) {
                defects.add("存在收货拒签异常(REJECTION)");
            }

            // 2) 电子签收数量核对 / 冲突裁决 / 人工修正
            List<SignReceipt> receipts = signReceiptMapper.selectList(new QueryWrapper<SignReceipt>()
                    .eq("batch_id", b.getId()).orderByAsc("id"));
            boolean qtyMismatch = false;
            boolean conflict = b.getSignFrozenFrom() != null;
            for (SignReceipt r : receipts) {
                if (r.getConfirmQty() != null && b.getShipQty() != null
                        && !r.getConfirmQty().equals(b.getShipQty())) {
                    qtyMismatch = true;
                }
            }
            if (qtyMismatch) {
                defects.add("签收回执实收数量与发运数量不一致");
            }
            if (conflict) {
                defects.add("出现两份不同签收触发批次冻结裁决");
            }
            List<SignCorrection> corrections = signCorrectionMapper.selectList(new QueryWrapper<SignCorrection>()
                    .eq("batch_id", b.getId()).orderByAsc("id"));
            if (!corrections.isEmpty()) {
                defects.add("签收数据发生过人工修正(" + corrections.size() + "条)");
            }

            d.put("rejectionException", rejection);
            d.put("receiptQtyMismatch", qtyMismatch);
            d.put("signConflictFrozen", conflict);
            d.put("correctionCount", corrections.size());
            d.put("defects", defects);
            boolean qualified = defects.isEmpty();
            d.put("qualified", qualified);
            m.denominator++;
            if (qualified) {
                m.numerator++;
            }
            m.details.add(d);
        }
        finish(m);
        return m;
    }

    // ---------------- 异常响应 ----------------
    private MetricEval evalExceptionResponse(EvidenceInput in) {
        MetricEval m = base(CcConstants.METRIC_EXCEPTION_RESPONSE, "异常响应",
                "EXCEPTION:t_cc_exception(found_time/confirm_time/response_minutes_snap/found_source)");
        for (TransportBatch b : in.batches) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("batchId", b.getId());
            d.put("batchNo", b.getBatchNo());
            List<ExceptionEvent> exceptions = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                    .eq("batch_id", b.getId()).orderByAsc("id"));

            // 系统客观判定（AUTO）成立即确认，天然在响应时限内；只考核需要人工确认的 MANUAL 异常
            List<Map<String, Object>> manualRows = new ArrayList<>();
            boolean batchQualified = true;
            boolean hasManual = false;
            for (ExceptionEvent e : exceptions) {
                if (!"MANUAL".equals(e.getFoundSource())) {
                    continue;
                }
                hasManual = true;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("exceptionId", e.getId());
                row.put("exceptionType", e.getExceptionType());
                row.put("foundTime", e.getFoundTime());
                row.put("confirmTime", e.getConfirmTime());
                row.put("responseMinutesSnap", e.getResponseMinutesSnap());
                boolean onTime;
                if (e.getConfirmTime() == null || e.getFoundTime() == null
                        || e.getResponseMinutesSnap() == null) {
                    // 已签收批次理论上不存在未确认异常（签收卡口）；出现这种数据说明响应事实缺失，不允许算达标
                    onTime = false;
                    row.put("reason", "缺少确认时间/发现时间/响应时限快照，响应事实不完整");
                } else {
                    LocalDateTime due = e.getFoundTime().plusMinutes(e.getResponseMinutesSnap());
                    onTime = !e.getConfirmTime().isAfter(due);
                    row.put("responseDue", due);
                }
                row.put("qualified", onTime);
                if (!onTime) {
                    batchQualified = false;
                }
                manualRows.add(row);
            }
            d.put("manualExceptionCount", hasManual ? manualRows.size() : 0);
            d.put("manualExceptions", manualRows);
            // 无人工待响应异常的批次视为达标（响应维度没有失分项）
            d.put("qualified", batchQualified);
            m.denominator++;
            if (batchQualified) {
                m.numerator++;
            }
            m.details.add(d);
        }
        finish(m);
        return m;
    }

    // ---------------- 公共 ----------------
    private MetricEval base(String code, String name, String sourceBasis) {
        MetricEval m = new MetricEval();
        m.metricCode = code;
        m.metricName = name;
        m.sourceBasis = sourceBasis;
        return m;
    }

    /** 指标分=100×分子/分母，四舍五入两位；分母为 0 记 100 分并在明细注明无应评样本 */
    private void finish(MetricEval m) {
        if (m.denominator == 0) {
            m.metricScore = new BigDecimal("100.00");
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("noSample", true);
            note.put("note", "本周期该指标无应评样本，指标分列 100.00（不计违约），总分按权重计算时该指标无样本支撑");
            m.details.add(0, note);
        } else {
            m.metricScore = BigDecimal.valueOf(100L * m.numerator)
                    .divide(BigDecimal.valueOf(m.denominator), 2, RoundingMode.HALF_UP);
        }
    }

    private List<Long> idList(int size, java.util.function.IntFunction<Long> getter) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ids.add(getter.apply(i));
        }
        return ids;
    }

    /** 周期内候选已签收批次（sign_time 落在 [start,end)） */
    public List<TransportBatch> findSignedBatches(LocalDateTime start, LocalDateTime end) {
        return batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("status", CcConstants.BATCH_SIGNED)
                .ge("sign_time", start)
                .lt("sign_time", end)
                .orderByAsc("id"));
    }

    /** 解析批次承运商：车辆绑定优先，其次锁价单；都没有返回 null（不纳入承运商评分） */
    public Long resolveCarrierId(TransportBatch b, Map<Long, Vehicle> vehicleMap,
                                 Map<Long, LockedPrice> lockedPriceMap) {
        Vehicle v = vehicleMap.get(b.getVehicleId());
        if (v != null && v.getCarrierId() != null) {
            return v.getCarrierId();
        }
        if (b.getLockedPriceId() != null) {
            LockedPrice lp = lockedPriceMap.get(b.getLockedPriceId());
            if (lp != null) {
                return lp.getCarrierId();
            }
        }
        return null;
    }

    public Map<Long, Vehicle> loadVehicles(List<TransportBatch> batches) {
        Map<Long, Vehicle> map = new HashMap<>();
        for (TransportBatch b : batches) {
            if (b.getVehicleId() != null && !map.containsKey(b.getVehicleId())) {
                map.put(b.getVehicleId(), null);
            }
        }
        if (!map.isEmpty()) {
            for (Vehicle v : vehicleMapper.selectBatchIds(map.keySet())) {
                map.put(v.getId(), v);
            }
        }
        return map;
    }

    public Map<Long, LockedPrice> loadLockedPrices(List<TransportBatch> batches) {
        Map<Long, LockedPrice> map = new HashMap<>();
        for (TransportBatch b : batches) {
            if (b.getLockedPriceId() != null && !map.containsKey(b.getLockedPriceId())) {
                map.put(b.getLockedPriceId(), null);
            }
        }
        if (!map.isEmpty()) {
            for (LockedPrice lp : lockedPriceMapper.selectBatchIds(map.keySet())) {
                map.put(lp.getId(), lp);
            }
        }
        return map;
    }
}
