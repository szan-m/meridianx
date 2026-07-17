package com.hsyd.mx.modules.okxmcp.support.x402;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okx.x402.facilitator.FacilitatorClient;
import com.okx.x402.model.v2.PaymentPayload;
import com.okx.x402.model.v2.PaymentRequirements;
import com.okx.x402.model.v2.SettleResponse;
import com.okx.x402.model.v2.VerifyResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * x402 计费服务（核心）。
 *
 * <p>基于 OKX 官方 SDK {@link FacilitatorClient} 实现 per-tool 定价：
 * <ol>
 *   <li>无凭证 → 抛 {@link X402PaymentRequiredException}（含 402 报价单）</li>
 *   <li>有凭证 → 解析 PaymentPayload → verify 校验签名 → settle 链上结算 → 返回 payer</li>
 * </ol>
 * 参考manifest-mcp 的 payment.py 模式。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class X402PaymentService {

    private static final String NETWORK = "eip155:196";
    private static final String ASSET_USDT0 = "0x779ded0c9e1022225f8e0630b35a9b54be713736";
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final long REPLAY_TTL_MS = 3600_000L;

    private final FacilitatorClient facilitator;
    private final X402Properties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    /**
     * 校验支付凭证并结算，返回买方钱包地址（payer）。
     *
     * <p>DEV_MODE 跳过校验，返回固定测试地址。</p>
     *
     * @param toolName      工具名（用于确定价格）
     * @param paymentProof  base64(JSON) 支付凭证，null 时抛 402 异常
     * @return 买方钱包地址
     * @throws X402PaymentRequiredException 无凭证时抛出（含 402 报价单）
     * @throws RuntimeException             凭证无效或结算失败
     */
    public String verifyAndSettle(String toolName, String paymentProof) {
        if (properties.isDevMode()) {
            log.info("verifyAndSettle tool={} DEV_MODE bypass", toolName);
            return "0xDEV_MODE_PAYER";
        }

        if (paymentProof == null || paymentProof.isBlank()) {
            log.info("verifyAndSettle tool={} no proof -> 402", toolName);
            throw buildPaymentRequiredException(toolName);
        }

        PaymentPayload payload;
        try {
            payload = PaymentPayload.fromHeader(paymentProof);
        } catch (Exception e) {
            log.warn("verifyAndSettle tool={} parse failed: {}", toolName, e.getMessage());
            throw buildPaymentRequiredException(toolName);
        }

        PaymentRequirements requirements = buildRequirements(toolName);

        VerifyResponse verifyResp;
        try {
            verifyResp = facilitator.verify(payload, requirements);
            log.info("verifyAndSettle tool={} verify isValid={} payer={}",
                    toolName, verifyResp.isValid, verifyResp.payer);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("verifyAndSettle tool={} verify failed: {}", toolName, e.getMessage());
            throw new RuntimeException("payment verification failed: " + e.getMessage(), e);
        }

        if (!verifyResp.isValid) {
            log.warn("verifyAndSettle tool={} invalid: {}", toolName, verifyResp.invalidReason);
            throw new RuntimeException("payment invalid: " + verifyResp.invalidReason);
        }

        String payer = verifyResp.payer;
        String replayKey = replayKey(payer, payload);
        pruneReplay();
        if (seenNonces.containsKey(replayKey)) {
            log.warn("verifyAndSettle tool={} replay hit key={}", toolName, replayKey);
            throw new RuntimeException("payment replay detected");
        }

        if (X402PriceTable.isFreeTool(toolName)) {
            log.info("verifyAndSettle tool={} free tool, skip settle", toolName);
            seenNonces.put(replayKey, System.currentTimeMillis());
            return payer;
        }

        SettleResponse settleResp;
        try {
            settleResp = facilitator.settle(payload, requirements, true);
            log.info("verifyAndSettle tool={} settle success={} status={} tx={}",
                    toolName, settleResp.success, settleResp.status, settleResp.transaction);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("verifyAndSettle tool={} settle failed: {}", toolName, e.getMessage());
            throw new RuntimeException("payment settlement failed: " + e.getMessage(), e);
        }

        if (!settleResp.success) {
            log.warn("verifyAndSettle tool={} settle failed: {} {}",
                    toolName, settleResp.errorReason, settleResp.errorMessage);
            throw new RuntimeException("settlement failed: " + settleResp.errorReason);
        }

        String status = settleResp.status;
        if (!"success".equals(status) && !"pending".equals(status)
                && !("timeout".equals(status) && settleResp.transaction != null)) {
            log.warn("verifyAndSettle tool={} unexpected status: {}", toolName, status);
            throw new RuntimeException("settlement unexpected status: " + status);
        }

        seenNonces.put(replayKey, System.currentTimeMillis());
        log.info("verifyAndSettle tool={} ACCEPT payer={} tx={}",
                toolName, payer, settleResp.transaction);
        return payer;
    }

    /**
     * 构造 x402 PaymentRequirements（报价单中的 accepts 条目）。
     */
    public PaymentRequirements buildRequirements(String toolName) {
        String price = X402PriceTable.getPrice(toolName);
        long amountAtomic = priceToAtomic(price);

        PaymentRequirements req = new PaymentRequirements();
        req.scheme = properties.getScheme();
        req.network = properties.getNetwork();
        req.payTo = properties.getPayToAddress();
        req.amount = String.valueOf(amountAtomic);
        req.maxTimeoutSeconds = MAX_TIMEOUT_SECONDS;
        req.asset = ASSET_USDT0;
        Map<String, Object> extra = new HashMap<>();
        extra.put("name", "USD\u20AE0");
        extra.put("version", "1");
        extra.put("transferMethod", "eip3009");
        req.extra = extra;
        return req;
    }

    /**
     * 构造 402 报价单异常。
     */
    private X402PaymentRequiredException buildPaymentRequiredException(String toolName) {
        PaymentRequirements req = buildRequirements(toolName);
        Map<String, Object> quote = new HashMap<>();
        quote.put("x402", "payment_required");
        quote.put("tool", toolName);
        quote.put("accepts", new Object[]{req});
        quote.put("note", "sign a PaymentPayload against accepts[0] and retry with _payment = base64(JSON)");
        try {
            String json = objectMapper.writeValueAsString(quote);
            return new X402PaymentRequiredException(toolName, json);
        } catch (Exception e) {
            return new X402PaymentRequiredException(toolName, "{\"x402\":\"payment_required\",\"tool\":\"" + toolName + "\"}");
        }
    }

    private long priceToAtomic(String price) {
        String cleaned = price.replace("$", "").replace("USD", "").trim();
        double usd = Double.parseDouble(cleaned);
        return Math.round(usd * 1_000_000L);
    }

    private String replayKey(String payer, PaymentPayload payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(body.getBytes(StandardCharsets.UTF_8));
            String fingerprint = bytesToHex(hash);
            return (payer != null ? payer : "?") + ":" + fingerprint;
        } catch (Exception e) {
            return (payer != null ? payer : "?") + ":" + System.nanoTime();
        }
    }

    private void pruneReplay() {
        long now = System.currentTimeMillis();
        seenNonces.entrySet().removeIf(e -> now - e.getValue() > REPLAY_TTL_MS);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
