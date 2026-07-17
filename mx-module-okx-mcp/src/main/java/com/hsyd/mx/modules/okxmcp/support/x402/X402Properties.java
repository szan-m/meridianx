package com.hsyd.mx.modules.okxmcp.support.x402;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * x402 计费配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mx.okx.mcp.x402")
public class X402Properties {

    /** 开发模式：跳过计费校验（上线置 false） */
    private boolean devMode = true;

    /** X Layer 收款地址 */
    private String payToAddress = "0xREPLACE_ME";

    /** OKX API Key */
    private String okxApiKey = "";

    /** OKX Secret Key */
    private String okxSecretKey = "";

    /** OKX Passphrase */
    private String okxPassphrase = "";

    /** OKX Facilitator 地址 */
    private String okxBaseUrl = "https://www.web3.okx.com";

    /** 结算方案：exact 或 aggr_deferred */
    private String scheme = "exact";

    /** 网络 CAIP-2（196 = X Layer Mainnet） */
    private String network = "eip155:196";
}
