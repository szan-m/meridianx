package com.hsyd.mx.modules.okxmcp.support.x402;

/**
 * 携带 x402 402 报价单的异常。
 *
 * <p>调用方未提供支付凭证时抛出，message 为 JSON 格式的报价单，
 * 供 MCP 客户端（OKX AI Agent）签名后重试。</p>
 */
public class X402PaymentRequiredException extends RuntimeException {

    private final String toolName;
    private final String quoteJson;

    public X402PaymentRequiredException(String toolName, String quoteJson) {
        super(quoteJson);
        this.toolName = toolName;
        this.quoteJson = quoteJson;
    }

    public String getToolName() {
        return toolName;
    }

    public String getQuoteJson() {
        return quoteJson;
    }
}
