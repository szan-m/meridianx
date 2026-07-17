package com.hsyd.mx.modules.okxmcp.support.x402;

import java.util.Map;

/**
 * OKX MCP 工具 x402 价目表（USDT）。
 *
 * <p>所有工具都走 x402 流程以统一获取 payer 钱包地址；
 * verify 报价 0 表示走流程但不扣款。</p>
 */
public final class X402PriceTable {

    /** 工具名 → USD 价格字符串 */
    private static final Map<String, String> PRICES = Map.of(
            "okx_flight_search", "$0.1",
            "okx_flight_verify_price", "$0",
            "okx_flight_create_order", "$0.5",
            "okx_flight_list_my_orders", "$0.01",
            "okx_flight_pay_preview", "$0.01",
            "okx_flight_pay_create", "$0.01",
            "okx_flight_pay_confirm", "$0.01"
    );

    private X402PriceTable() {
    }

    /**
     * 获取工具的 USD 价格字符串。
     *
     * @param toolName 工具名
     * @return 价格字符串如 "$0.1"，未知工具默认 "$0.01"
     */
    public static String getPrice(String toolName) {
        return PRICES.getOrDefault(toolName, "$0.01");
    }

    /**
     * 是否为 0 元工具（走 x402 拿 payer 但不扣款）。
     */
    public static boolean isFreeTool(String toolName) {
        String price = PRICES.get(toolName);
        return "$0".equals(price) || "$0.0".equals(price) || "$0.00".equals(price);
    }
}
