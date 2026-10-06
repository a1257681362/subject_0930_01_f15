package com.evops.coldchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部温控监测平台默认实现（V15）：HTTP GET 健康检查 + POST 批量客观判定。
 *
 * 无响应（连接异常/超时/非 2xx/响应缺批次）一律视为不可用：
 * - 健康检查返回 {@link HealthResult#down}（不抛异常也绝不返回健康）；
 * - 取数遇到上述问题抛 {@link ExternalDependencyUnavailableException}，
 *   由评分关闭流程阻断，绝不让评分指标“假装成功”。
 * 端点为空时（演示环境默认）同样视为不可用，避免静默回退成自评满分。
 */
@Component
public class HttpTempMonitorDependency implements TempMonitorDependency {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @Value("${coldchain.temp-monitor.base-url:}")
    private String baseUrl;
    @Value("${coldchain.temp-monitor.connect-timeout-ms:2000}")
    private int connectTimeoutMs;
    @Value("${coldchain.temp-monitor.read-timeout-ms:3000}")
    private int readTimeoutMs;

    @Override
    public HealthResult checkHealth() {
        LocalDateTime now = LocalDateTime.now();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return HealthResult.down("外部温控监测平台地址未配置（coldchain.temp-monitor.base-url 为空），不做默认成功判定", now);
        }
        try {
            int code = invoke("GET", baseUrl + "/health", null);
            if (code >= 200 && code < 300) {
                return HealthResult.ok(now);
            }
            return HealthResult.down("温控平台健康检查返回非成功状态码: " + code, now);
        } catch (Exception e) {
            return HealthResult.down("温控平台无响应：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), now);
        }
    }

    @Override
    public TempVerdictResult fetchTempVerdicts(List<Long> batchIds) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new ExternalDependencyUnavailableException(
                    "外部温控监测平台地址未配置，温控合格率取证阻断（不允许默认成功）");
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            String reqJson = M.writeValueAsString(new LinkedHashMap<String, Object>() {{
                put("batchIds", batchIds);
            }});
            String resp = post(baseUrl + "/temp-verdicts", reqJson);
            JsonNode root = M.readTree(resp);
            JsonNode items = root.path("verdicts");
            if (!items.isArray()) {
                throw new ExternalDependencyUnavailableException("温控平台返回缺少 verdicts 数组，数据不完整");
            }
            Map<Long, Boolean> map = new LinkedHashMap<>();
            for (JsonNode item : items) {
                long bid = item.path("batchId").asLong(0);
                JsonNode qualified = item.path("qualified");
                if (bid == 0L || qualified.isMissingNode() || qualified.isNull()) {
                    throw new ExternalDependencyUnavailableException(
                            "温控平台返回存在缺批次或缺判定的数据行，取证数据不完整");
                }
                map.put(bid, qualified.asBoolean());
            }
            for (Long bid : batchIds) {
                if (!map.containsKey(bid)) {
                    throw new ExternalDependencyUnavailableException(
                            "温控平台未返回批次#" + bid + " 的温控判定，取证数据不完整，阻断评分");
                }
            }
            return new TempVerdictResult(map, now, "EXTERNAL_TEMP_PLATFORM@" + baseUrl);
        } catch (ExternalDependencyUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalDependencyUnavailableException("拉取温控客观判定失败："
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
    }

    private String post(String urlStr, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new ExternalDependencyUnavailableException("温控平台取数返回非成功状态码: " + code);
            }
            java.io.InputStream is = conn.getInputStream();
            byte[] bytes = readAll(is);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private int invoke(String method, String urlStr, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            return conn.getResponseCode();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
