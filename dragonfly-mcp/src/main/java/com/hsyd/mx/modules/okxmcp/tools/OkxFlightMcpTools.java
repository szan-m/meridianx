package com.hsyd.mx.modules.okxmcp.tools;

import com.hsyd.mx.modules.okxmcp.facade.OkxFlightFacade;
import com.hsyd.mx.modules.okxmcp.support.x402.OkxUserIdResolver;
import com.hsyd.mx.modules.okxmcp.support.x402.X402PaymentRequiredException;
import com.hsyd.mx.modules.okxmcp.support.x402.X402PaymentService;
import com.hsyd.mx.modules.okxmcp.vo.OkxMcpToolResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * OKX AI MCP Tools（7 个工具，x402 计费即认证）。
 *
 * <p>每个工具通过 _payment 参数承接 x402 支付凭证，
 * 校验后从 payer 地址解析 userId，再调用业务 Facade。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxFlightMcpTools {

    private final OkxFlightFacade facade;
    private final X402PaymentService paymentService;
    private final OkxUserIdResolver userIdResolver;

    private static final String PAYMENT_PARAM_DESC =
            "x402 支付凭证（base64 JSON）。无凭证时服务端返回 402 报价单，"
                    + "用 Agentic Wallet 签名后携带此参数重试。";

    @McpTool(name = "okx_flight_search", description = "机票查询。按城市与日期查报价；aduPassengerCount/chdPassengerCount/babPassengerCount 决定成人/儿童/婴儿人数。0.1 USDT/次")
    public OkxMcpToolResultVO<?> search(
            @McpToolParam(description = "x402 支付凭证", required = true) String _payment,
            @McpToolParam(description = "出发城市三字码，如 SHA", required = true) String cityDeparture,
            @McpToolParam(description = "到达城市三字码，如 BJS", required = true) String cityDestination,
            @McpToolParam(description = "出发日期 yyyy-MM-dd", required = true) String date,
            @McpToolParam(description = "成人数，默认1；ageType=0") Integer aduPassengerCount,
            @McpToolParam(description = "儿童数，默认0；ageType=1") Integer chdPassengerCount,
            @McpToolParam(description = "婴儿数，默认0；ageType=2") Integer babPassengerCount) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_search", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.search(
                    cityDeparture, cityDestination, date, aduPassengerCount, chdPassengerCount, babPassengerCount));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_get_detail", description = "报价详情（可选）。返回行程与价格展示；routing 由服务端自动组装。免费")
    public OkxMcpToolResultVO<?> getDetail(
            @McpToolParam(description = "政策 ID，来自 okx_flight_search 结果的 policyId", required = true) String policyId,
            @McpToolParam(description = "成人数，默认1") Integer adultCount,
            @McpToolParam(description = "儿童数，默认0") Integer childCount,
            @McpToolParam(description = "婴儿数，默认0") Integer infantCount) {
        try {
            return OkxMcpToolResultVO.ok(facade.getDetail(policyId, adultCount, childCount, infantCount));
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(400, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_verify_price", description = "运价验价。工作流：okx_flight_search → 选 policyId → 本工具验价 → 返回 verifyResultId（15分钟内有效）。0 USDT（走 x402 拿身份不扣款）")
    public OkxMcpToolResultVO<?> verifyPrice(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "政策 ID，来自 okx_flight_search", required = true) String policyId,
            @McpToolParam(description = "报价 routing JSON，可省略；省略时按 policyId 自动组装") String routingJson,
            @McpToolParam(description = "成人数，默认1") Integer adultNumber,
            @McpToolParam(description = "儿童数，默认0") Integer childNumber,
            @McpToolParam(description = "婴儿数，默认0") Integer infantNumber) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_verify_price", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.verify(
                    userId, policyId, routingJson, adultNumber, childNumber, infantNumber));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_create_order", description = "创建机票订单。工作流：okx_flight_verify_price 后调用。乘机人可用扁平参数或 passengersJson（JSON 优先）。ageType：0成人/1儿童/2婴儿。按航班政策报价")
    public OkxMcpToolResultVO<?> createOrder(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "验价结果 ID，来自 okx_flight_verify_price 返回的 id", required = true) String verifyResultId,
            @McpToolParam(description = "政策 ID，可省略；省略时从 verifyResultId 反查") String policyId,
            @McpToolParam(description = "报价 routing JSON，可省略") String routingJson,
            @McpToolParam(description = "乘机人 JSON 数组，与扁平参数二选一（JSON 优先）") String passengersJson,
            @McpToolParam(description = "联系人 JSON，与扁平参数二选一") String contactJson,
            @McpToolParam(description = "乘机人姓名（扁平模式）") String passengerName,
            @McpToolParam(description = "证件号码（扁平模式）") String passengerCardNum,
            @McpToolParam(description = "证件类型，默认 ID；PP=护照") String passengerCardType,
            @McpToolParam(description = "证件有效期 yyyyMMdd（扁平模式）") String passengerCardExpired,
            @McpToolParam(description = "乘客类型：0成人/1儿童/2婴儿") Integer passengerAgeType,
            @McpToolParam(description = "生日 yyyyMMdd；儿童/婴儿必填") String passengerBirthday,
            @McpToolParam(description = "联系人姓名（扁平模式）") String contactName,
            @McpToolParam(description = "联系人手机（扁平模式）") String contactMobile,
            @McpToolParam(description = "联系人邮箱，可选") String contactEmail,
            @McpToolParam(description = "成人数，与验价一致") Integer adultNumber,
            @McpToolParam(description = "儿童数，与验价一致") Integer childNumber,
            @McpToolParam(description = "婴儿数，与验价一致") Integer infantNumber,
            @McpToolParam(description = "幂等键，可选；省略时自动生成") String idempotencyKey) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_create_order", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.createOrder(
                    userId, verifyResultId, policyId, routingJson, passengersJson, contactJson,
                    passengerName, passengerCardNum, passengerCardType, passengerCardExpired,
                    passengerAgeType, passengerBirthday,
                    contactName, contactMobile, contactEmail, adultNumber, childNumber, infantNumber, idempotencyKey));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_list_my_orders", description = "查询当前用户的机票订单列表，支持分页。0.01 USDT/次")
    public OkxMcpToolResultVO<?> listMyOrders(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "页码，默认1") Integer pageNo,
            @McpToolParam(description = "每页条数，默认10，最大50") Integer pageSize) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_list_my_orders", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.listMyOrders(userId, pageNo, pageSize));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_pay_preview", description = "机票链上支付预览：返回应付金额与可选链收款配置。0.01 USDT/次")
    public OkxMcpToolResultVO<?> payPreview(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "出票单 ID（okx_flight_create_order 返回的 issuingId）", required = true) Long issuingId,
            @McpToolParam(description = "业务类型，默认 ISSUING") String bizType) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_pay_preview", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.payPreview(userId, issuingId, bizType));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_pay_create", description = "创建链上支付单，返回 txIntent 供钱包 eth_sendTransaction。0.01 USDT/次")
    public OkxMcpToolResultVO<?> payCreate(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "出票单 ID", required = true) Long issuingId,
            @McpToolParam(description = "链 ID", required = true) Integer chainId,
            @McpToolParam(description = "支付方式：WALLET/MANUAL_TX/CONTRACT", required = true) String payMode,
            @McpToolParam(description = "链上支付金额明细 ID", required = true) Long chainPayAmountId,
            @McpToolParam(description = "线下收款配置 ID，可选") Long offlineAddressId,
            @McpToolParam(description = "差旅券 ID，可选") Long travelVoucherId,
            @McpToolParam(description = "钱包渠道，可选") String walletChannel,
            @McpToolParam(description = "业务类型，默认 ISSUING") String bizType) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_pay_create", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.payCreate(
                    userId, issuingId, bizType, chainId, payMode, chainPayAmountId,
                    offlineAddressId, travelVoucherId, walletChannel));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }

    @McpTool(name = "okx_flight_pay_confirm", description = "确认链上支付：钱包发交易后回传 txHash。0.01 USDT/次")
    public OkxMcpToolResultVO<?> payConfirm(
            @McpToolParam(description = PAYMENT_PARAM_DESC, required = true) String _payment,
            @McpToolParam(description = "支付订单 ID（okx_flight_pay_create 返回的 orderId）", required = true) String orderId,
            @McpToolParam(description = "链上交易哈希", required = true) String txHash,
            @McpToolParam(description = "txHash 来源：AUTO/MANUAL，默认 AUTO") String txHashSource,
            @McpToolParam(description = "币种 tokenSymbol") String tokenSymbol) {
        try {
            String payer = paymentService.verifyAndSettle("okx_flight_pay_confirm", _payment);
            Long userId = userIdResolver.resolveUserId(payer);
            return OkxMcpToolResultVO.ok(facade.payConfirm(
                    userId, orderId, txHash, txHashSource, tokenSymbol));
        } catch (X402PaymentRequiredException e) {
            return OkxMcpToolResultVO.paymentRequired(e.getQuoteJson());
        } catch (Exception e) {
            return OkxMcpToolResultVO.fail(500, e.getMessage());
        }
    }
}
