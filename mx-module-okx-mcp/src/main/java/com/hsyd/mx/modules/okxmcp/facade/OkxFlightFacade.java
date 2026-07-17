package com.hsyd.mx.modules.okxmcp.facade;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsyd.mx.modules.cfi.dto.NodePurchaseConfirmRequestDTO;
import com.hsyd.mx.modules.cfi.vo.ConfirmPurchaseResultVO;
import com.hsyd.mx.modules.df.flight.dto.FareContactDto;
import com.hsyd.mx.modules.df.flight.dto.FareOrderDto;
import com.hsyd.mx.modules.df.flight.dto.FarePassengerDto;
import com.hsyd.mx.modules.df.flight.dto.FareRoutingDto;
import com.hsyd.mx.modules.df.flight.dto.FareVerifyDto;
import com.hsyd.mx.modules.df.flight.dto.FlightChainPayOrderRequestDTO;
import com.hsyd.mx.modules.df.flight.dto.SearchRequestWebDTO;
import com.hsyd.mx.modules.df.flight.entity.FareVerifyResult;
import com.hsyd.mx.modules.df.flight.entity.Issuing;
import com.hsyd.mx.modules.df.flight.service.IFareVerifyResultService;
import com.hsyd.mx.modules.df.flight.service.IFlightChainPayService;
import com.hsyd.mx.modules.df.flight.service.IFlightOrderService;
import com.hsyd.mx.modules.df.flight.service.IFlightPassengerCertValidator;
import com.hsyd.mx.modules.df.flight.service.IFlightSearchService;
import com.hsyd.mx.modules.df.flight.service.IIssuingService;
import com.hsyd.mx.modules.df.flight.utils.FlightOrderRateLimiter;
import com.hsyd.mx.modules.df.flight.utils.FlightOrderSecurityValidator;
import com.hsyd.mx.modules.df.flight.utils.FlightPassengerRuleValidator;
import com.hsyd.mx.modules.df.flight.utils.FlightSearchRateLimiter;
import com.hsyd.mx.modules.df.flight.vo.FareOrderResultVO;
import com.hsyd.mx.modules.df.flight.vo.FareVerifyResultVO;
import com.hsyd.mx.modules.df.flight.vo.FlightChainPayOrderResponseVO;
import com.hsyd.mx.modules.df.flight.vo.FlightChainPayPreviewVO;
import com.hsyd.mx.modules.df.flight.vo.FlightPayTokenExchangeRateVO;
import com.hsyd.mx.modules.df.flight.vo.FlightSearchResultVO;
import com.hsyd.mx.modules.df.flight.vo.OfferVO;
import com.hsyd.mx.modules.df.flight.vo.UserOrderListVO;
import com.hsyd.mx.modules.okxmcp.support.OkxPassengerAssembler;
import com.hsyd.mx.modules.okxmcp.support.OkxRoutingBuilder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OKX AI MCP 机票业务 Facade。
 *
 * <p>独立于 {@code mx-module-mcp} 的 FlightMcpFacade，直接复用 cfi/df Service 层。
 * userId 由 x402 payer 地址解析而来，不走 Sa-Token。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxFlightFacade {

    private static final String MCP_IDENTIFIER = "okx-ai-mcp";

    private final IFlightSearchService flightSearchService;
    private final IFlightOrderService flightOrderService;
    private final IFareVerifyResultService fareVerifyResultService;
    private final IIssuingService issuingService;
    private final IFlightChainPayService flightChainPayService;
    private final FlightPassengerRuleValidator flightPassengerRuleValidator;
    private final FlightOrderSecurityValidator securityValidator;
    private final IFlightPassengerCertValidator flightPassengerCertValidator;
    private final FlightOrderRateLimiter rateLimiter;
    private final FlightSearchRateLimiter searchRateLimiter;
    private final ObjectMapper objectMapper;
    private final OkxRoutingBuilder routingBuilder;
    private final OkxPassengerAssembler passengerAssembler;

    public FlightSearchResultVO search(String cityDeparture, String cityDestination, String date,
                                       Integer aduPassengerCount, Integer chdPassengerCount,
                                       Integer babPassengerCount) {
        if (!searchRateLimiter.allowRequest(MCP_IDENTIFIER)) {
            throw new IllegalStateException("查询请求过于频繁，请稍后再试");
        }
        SearchRequestWebDTO dto = new SearchRequestWebDTO();
        dto.setCityDeparture(cityDeparture);
        dto.setCityDestination(cityDestination);
        dto.setDate(date);
        int adults = aduPassengerCount != null ? aduPassengerCount : 1;
        int children = chdPassengerCount != null ? chdPassengerCount : 0;
        int infants = babPassengerCount != null ? babPassengerCount : 0;
        dto.setAduPassengerCount(adults);
        dto.setChdPassengerCount(children);
        dto.setBabPassengerCount(infants);
        dto.setPassengerCount(adults + children + infants);
        flightPassengerRuleValidator.validateSearchPassengerCount(dto);
        return flightSearchService.searchWeb(dto, MCP_IDENTIFIER);
    }

    public OfferVO getDetail(String policyId, Integer adultCount, Integer childCount, Integer infantCount) {
        int adults = adultCount != null ? adultCount : 1;
        int children = childCount != null ? childCount : 0;
        int infants = infantCount != null ? infantCount : 0;
        return flightSearchService.detailForDisplay(policyId, adults, children, infants);
    }

    public FareVerifyResultVO verify(Long userId, String policyId, String routingJson,
                                     Integer adultNumber, Integer childNumber, Integer infantNumber) {
        if (!rateLimiter.allowVerifyByUser(userId)) {
            throw new IllegalStateException("验价请求过于频繁，请稍后再试");
        }
        int adults = adultNumber != null ? adultNumber : 1;
        int children = childNumber != null ? childNumber : 0;
        int infants = infantNumber != null ? infantNumber : 0;

        FareVerifyDto dto = new FareVerifyDto();
        dto.setPolicyId(policyId);
        dto.setRouting(resolveRouting(policyId, routingJson, adults, children, infants));
        dto.setAdultNumber(adults);
        dto.setChildNumber(children);
        dto.setInfantNumber(infants);
        securityValidator.validateVerify(dto);
        return flightOrderService.verify(dto);
    }

    public FareOrderResultVO createOrder(Long userId,
                                         String verifyResultId,
                                         String policyId,
                                         String routingJson,
                                         String passengersJson,
                                         String contactJson,
                                         String passengerName,
                                         String passengerCardNum,
                                         String passengerCardType,
                                         String passengerCardExpired,
                                         Integer passengerAgeType,
                                         String passengerBirthday,
                                         String contactName,
                                         String contactMobile,
                                         String contactEmail,
                                         Integer adultNumber,
                                         Integer childNumber,
                                         Integer infantNumber,
                                         String idempotencyKey) {
        if (!rateLimiter.allowOrderByUser(userId)) {
            throw new IllegalStateException("下单请求过于频繁，请稍后再试");
        }
        Issuing pending = issuingService.findPendingPayByUserId(userId);
        if (pending != null) {
            throw new IllegalStateException("存在待支付订单，请先完成支付或取消后再下单");
        }

        int[] counts = resolveVerifyPassengerCounts(verifyResultId, adultNumber, childNumber, infantNumber);
        int adults = counts[0];
        int children = counts[1];
        int infants = counts[2];

        String resolvedPolicyId = resolvePolicyId(verifyResultId, policyId);

        List<FarePassengerDto> passengers = passengerAssembler.resolvePassengers(
                passengersJson, passengerName, passengerCardNum, passengerCardType, passengerCardExpired,
                passengerAgeType, passengerBirthday);
        FareContactDto contact = passengerAssembler.resolveContact(
                contactJson, contactName, contactMobile, contactEmail);
        passengerAssembler.validatePassengerCounts(passengers, adults, children, infants);

        FareOrderDto dto = new FareOrderDto();
        dto.setVerifyResultId(verifyResultId);
        dto.setPolicyId(resolvedPolicyId);
        dto.setRouting(resolveRouting(resolvedPolicyId, routingJson, adults, children, infants));
        dto.setPassengers(passengers);
        dto.setContact(contact);
        flightPassengerRuleValidator.validateOrderPassengerCount(dto);
        securityValidator.validateOrder(dto);
        flightPassengerCertValidator.validateAndNormalizeForOrder(dto);

        String resolvedIdempotencyKey = StrUtil.isNotBlank(idempotencyKey)
                ? idempotencyKey.trim()
                : UUID.fastUUID().toString(true);
        return flightOrderService.createOrder(dto, userId, resolvedIdempotencyKey);
    }

    public IPage<UserOrderListVO> listMyOrders(Long userId, Integer pageNo, Integer pageSize) {
        int page = pageNo != null && pageNo > 0 ? pageNo : 1;
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 50) : 10;
        return flightOrderService.listMyOrders(userId, page, size);
    }

    public FlightChainPayPreviewVO payPreview(Long userId, Long issuingId, String bizType) {
        String resolvedBizType = StrUtil.blankToDefault(bizType, "ISSUING");
        return flightChainPayService.getPurchasePreview(userId, issuingId, resolvedBizType);
    }

    public FlightPayTokenExchangeRateVO payTokenRate(String oriCurrency, String targetCurrency) {
        return flightChainPayService.getPayTokenExchangeRate(oriCurrency, targetCurrency);
    }

    public FlightChainPayOrderResponseVO payCreate(Long userId, Long issuingId, String bizType,
                                                   Integer chainId, String payMode,
                                                   Long chainPayAmountId, Long offlineAddressId,
                                                   Long travelVoucherId, String walletChannel) {
        if (!rateLimiter.allowOrderByUser(userId)) {
            throw new IllegalStateException("操作过于频繁，请稍后再试");
        }
        FlightChainPayOrderRequestDTO dto = new FlightChainPayOrderRequestDTO();
        dto.setChainId(chainId);
        dto.setPayMode(payMode);
        dto.setChainPayAmountId(chainPayAmountId);
        dto.setOfflineAddressId(offlineAddressId);
        dto.setTravelVoucherId(travelVoucherId);
        dto.setWalletChannel(walletChannel);
        String resolvedBizType = StrUtil.blankToDefault(bizType, "ISSUING");
        return flightChainPayService.createPurchaseOrder(userId, issuingId, resolvedBizType, dto);
    }

    public ConfirmPurchaseResultVO payConfirm(Long userId, String orderId, String txHash,
                                               String txHashSource, String tokenSymbol) {
        if (!rateLimiter.allowOrderByUser(userId)) {
            throw new IllegalStateException("操作过于频繁，请稍后再试");
        }
        NodePurchaseConfirmRequestDTO dto = new NodePurchaseConfirmRequestDTO();
        dto.setOrderId(orderId);
        dto.setTxHash(txHash);
        dto.setTxHashSource(StrUtil.blankToDefault(txHashSource, "AUTO"));
        if (StrUtil.isNotBlank(tokenSymbol)) {
            dto.setTokenSymbol(StrUtil.trim(tokenSymbol));
        }
        return flightChainPayService.confirmPurchase(userId, dto);
    }

    private FareRoutingDto resolveRouting(String policyId, String routingJson,
                                          int adultCount, int childCount, int infantCount) {
        if (StrUtil.isNotBlank(routingJson)) {
            return parseRouting(routingJson);
        }
        if (StrUtil.isBlank(policyId)) {
            throw new IllegalArgumentException("routingJson 为空时必须提供 policyId");
        }
        OfferVO offer = getDetail(policyId, adultCount, childCount, infantCount);
        return routingBuilder.build(offer, adultCount, childCount, infantCount);
    }

    private String resolvePolicyId(String verifyResultId, String policyId) {
        if (StrUtil.isNotBlank(policyId)) {
            return policyId.trim();
        }
        if (StrUtil.isBlank(verifyResultId)) {
            throw new IllegalArgumentException("policyId 与 verifyResultId 不能同时为空");
        }
        FareVerifyResult verifyResult = fareVerifyResultService.findFareVerifyResultById(verifyResultId);
        if (verifyResult == null || StrUtil.isBlank(verifyResult.getPolicyId())) {
            throw new IllegalArgumentException("无法根据 verifyResultId 反查 policyId: " + verifyResultId);
        }
        return verifyResult.getPolicyId();
    }

    private int[] resolveVerifyPassengerCounts(String verifyResultId,
                                                Integer adultNumber,
                                                Integer childNumber,
                                                Integer infantNumber) {
        if (StrUtil.isBlank(verifyResultId)) {
            throw new IllegalArgumentException("verifyResultId 不能为空");
        }
        FareVerifyResult verifyResult = fareVerifyResultService.findFareVerifyResultById(verifyResultId);
        if (verifyResult != null && StrUtil.isNotBlank(verifyResult.getRequest())) {
            try {
                JsonNode root = objectMapper.readTree(verifyResult.getRequest());
                int adults = root.path("adultNumber").asInt(1);
                int children = root.path("childNumber").asInt(0);
                int infants = root.path("infantNumber").asInt(0);
                return new int[]{adults, children, infants};
            } catch (Exception ignored) {
            }
        }
        if (adultNumber != null || childNumber != null || infantNumber != null) {
            return new int[]{
                    adultNumber != null ? adultNumber : 1,
                    childNumber != null ? childNumber : 0,
                    infantNumber != null ? infantNumber : 0
            };
        }
        if (verifyResult == null) {
            throw new IllegalArgumentException("验价结果不存在或已过期: " + verifyResultId);
        }
        return new int[]{1, 0, 0};
    }

    private FareRoutingDto parseRouting(String routingJson) {
        if (StrUtil.isBlank(routingJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(routingJson, FareRoutingDto.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("routingJson 格式无效: " + ex.getMessage());
        }
    }
}
