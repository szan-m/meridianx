package com.hsyd.mx.modules.okxmcp.support.x402;

import com.okx.x402.facilitator.OKXFacilitatorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * x402 Facilitator 客户端 Spring Bean 配置。
 *
 * <p>创建官方 SDK {@link OKXFacilitatorClient} 单例，
 * 供 {@link X402PaymentService} 调用 verify/settle。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class X402FacilitatorConfig {

    private final X402Properties properties;

    @Bean
    public OKXFacilitatorClient okxFacilitatorClient() {
        log.info("Initializing OKXFacilitatorClient: baseUrl={}, devMode={}",
                properties.getOkxBaseUrl(), properties.isDevMode());
        return new OKXFacilitatorClient(
                properties.getOkxApiKey(),
                properties.getOkxSecretKey(),
                properties.getOkxPassphrase(),
                properties.getOkxBaseUrl()
        );
    }
}
