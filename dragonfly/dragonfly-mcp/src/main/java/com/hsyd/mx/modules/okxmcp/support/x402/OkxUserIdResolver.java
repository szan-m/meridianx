package com.hsyd.mx.modules.okxmcp.support.x402;

import com.hsyd.mx.modules.cfi.service.IProtocolUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 从 x402 payer 钱包地址解析 userId。
 *
 * <p>调用 {@link IProtocolUserService#getOrCreateUserIdByAddress(String)}
 * 查找或自动创建用户，返回 userId 供业务 Facade 使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxUserIdResolver {

    private final IProtocolUserService protocolUserService;

    /**
     * 根据 payer 钱包地址获取或创建用户，返回 userId。
     *
     * @param payerAddress x402 Facilitator 校验返回的买方钱包地址
     * @return 用户 ID
     */
    public Long resolveUserId(String payerAddress) {
        if (payerAddress == null || payerAddress.isBlank()) {
            throw new IllegalArgumentException("payer address is empty");
        }
        Long userId = protocolUserService.getOrCreateUserIdByAddress(payerAddress.trim());
        log.info("resolveUserId payer={} -> userId={}", payerAddress, userId);
        return userId;
    }
}
