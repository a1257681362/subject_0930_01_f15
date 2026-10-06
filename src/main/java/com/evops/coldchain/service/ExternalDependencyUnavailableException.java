package com.evops.coldchain.service;

/**
 * 外部依赖不可用（V15）：冷链药品运输外部依赖（温控监测平台）无响应/返回不完整时抛出。
 * 评分取证遇到它必须整体阻断——不得给任何指标默认成功或满分，由关闭流程留 BLOCKED 痕迹。
 */
public class ExternalDependencyUnavailableException extends RuntimeException {
    public ExternalDependencyUnavailableException(String message) {
        super(message);
    }

    public ExternalDependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
