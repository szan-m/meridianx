package com.hsyd.mx.modules.okxmcp.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OKX MCP Tool 统一返回包装。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OkxMcpToolResultVO<T> {

    private boolean success;
    private Integer code;
    private String message;
    private T result;

    public static <T> OkxMcpToolResultVO<T> ok(T result) {
        return new OkxMcpToolResultVO<>(true, 0, "success", result);
    }

    public static <T> OkxMcpToolResultVO<T> fail(int code, String message) {
        return new OkxMcpToolResultVO<>(false, code, message, null);
    }

    public static <T> OkxMcpToolResultVO<T> paymentRequired(String quoteJson) {
        return new OkxMcpToolResultVO<>(false, 402, quoteJson, null);
    }
}
