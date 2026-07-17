package com.hsyd.mx.modules.okxmcp.support;

import cn.hutool.core.util.StrUtil;
import com.hsyd.mx.modules.df.flight.dto.FarePriceDto;
import com.hsyd.mx.modules.df.flight.dto.FareRoutingDto;
import com.hsyd.mx.modules.df.flight.dto.FareSegmentDto;
import com.hsyd.mx.modules.df.flight.vo.OfferDetailsVO;
import com.hsyd.mx.modules.df.flight.vo.OfferVO;
import com.hsyd.mx.modules.df.flight.vo.display.FareCardDisplayVO;
import com.hsyd.mx.modules.df.flight.vo.pricing.FlightPassengerTypePriceVO;
import com.hsyd.mx.modules.df.flight.vo.pricing.FlightPriceVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 从报价详情组装验价/生单所需的 FareRoutingDto（OKX MCP 独立副本）。
 */
@Component
public class OkxRoutingBuilder {

    private static final String TYPE_ADU = "ADU";
    private static final String TYPE_CHI = "CHI";
    private static final String TYPE_INF = "INF";

    public FareRoutingDto build(OfferVO offer, int adultCount, int childCount, int infantCount) {
        if (offer == null || offer.getOffer() == null) {
            throw new IllegalArgumentException("报价详情为空，无法组装 routing");
        }
        OfferDetailsVO details = offer.getOffer();
        if (details.getShoppingResultList() == null || details.getShoppingResultList().isEmpty()) {
            throw new IllegalArgumentException("报价详情缺少 shoppingResultList");
        }
        if (details.getFlightList() == null || details.getFlightList().isEmpty()) {
            throw new IllegalArgumentException("报价详情缺少 flightList");
        }

        OfferDetailsVO.ShoppingResultVO shopping = details.getShoppingResultList().get(0);
        if (shopping.getTuList() == null || shopping.getTuList().isEmpty()) {
            throw new IllegalArgumentException("报价详情缺少 tuList");
        }
        OfferDetailsVO.TuVO tu = shopping.getTuList().get(0);

        Map<Integer, OfferDetailsVO.FlightVO> flightByRef = new HashMap<>();
        for (OfferDetailsVO.FlightVO flight : details.getFlightList()) {
            if (flight.getFlightRefNum() != null) {
                flightByRef.put(flight.getFlightRefNum(), flight);
            }
        }

        List<FareSegmentDto> fromSegments = new ArrayList<>();
        if (shopping.getFlightRefList() != null) {
            for (OfferDetailsVO.FlightRefVO ref : shopping.getFlightRefList()) {
                OfferDetailsVO.FlightVO flight = ref.getFlightRefNum() != null
                        ? flightByRef.get(ref.getFlightRefNum()) : null;
                if (flight == null) {
                    continue;
                }
                FareSegmentDto segment = new FareSegmentDto();
                segment.setMarketingCarrier(flight.getMarketingCarrier());
                segment.setDepAirport(flight.getDepAirport());
                segment.setDepTime(flight.getDepTime());
                segment.setArrAirport(flight.getArrAirport());
                segment.setArrTime(flight.getArrTime());
                segment.setSeatClass(ref.getSeatClass());
                segment.setFlightNumber(flight.getFlightNumber());
                segment.setCodeShare(flight.getCodeShare());
                segment.setAircraftCode(flight.getAircraftCode());
                segment.setOperatingCarrier(flight.getOperatingCarrier());
                segment.setOperatingFlightNo(flight.getOperatingFlightNo());
                segment.setSegmentNo(ref.getSegmentNo());
                segment.setStopCities("");
                fromSegments.add(segment);
            }
        }
        if (fromSegments.isEmpty()) {
            throw new IllegalArgumentException("无法从报价详情组装航段信息");
        }

        String seatClass = fromSegments.get(0).getSeatClass();
        FareRoutingDto routing = new FareRoutingDto();
        routing.setData(shopping.getData());
        routing.setFromSegments(fromSegments);
        routing.setRetSegments(new ArrayList<>());
        routing.setPriceList(buildPriceList(offer, adultCount, childCount, infantCount));
        routing.setEligibility(tu.getEligibility());
        routing.setValidatingCarrier(tu.getValidatingCarrier());
        routing.setProductType(tu.getProductType());
        routing.setFareBasis(resolveFareBasis(tu.getFareBasis(), seatClass));
        routing.setTariffNo(tu.getTariffNo());
        routing.setReservationType(tu.getReservationType());
        routing.setPosCode(tu.getPosCode());
        routing.setComplexTerm(tu.getComplexTerm());
        routing.setNationalityType(tu.getNationalityType());
        routing.setPlanCategory(tu.getPlanCategory());
        routing.setInvoiceType(tu.getInvoiceType());
        routing.setMinPassengerCount(tu.getMinPassengerCount());
        routing.setMaxPassengerCount(tu.getMaxPassengerCount());
        routing.setFormatBaggageDetailList(copyBaggageList(tu));
        routing.setRefundInfoList(copyRefundList(tu));
        routing.setChangesInfoList(copyChangesList(tu));
        routing.setCurrency("CNY");
        routing.setApplyType(tu.getApplyType());
        routing.setTicketTimeUnit(tu.getTicketTimeUnit());
        routing.setTuanType(tu.getTuanType());
        routing.setEndorsement(tu.getEndorsement());
        routing.setPenalties(tu.getPenalties() != null ? tu.getPenalties() : 0);
        return routing;
    }

    private List<FarePriceDto> buildPriceList(OfferVO offer, int adultCount, int childCount, int infantCount) {
        List<FarePriceDto> priceList = new ArrayList<>();
        FlightPriceVO pricing = resolvePricing(offer);
        Map<String, FlightPassengerTypePriceVO> byType = pricing != null && pricing.getByPassengerType() != null
                ? pricing.getByPassengerType() : Map.of();

        if (adultCount > 0) {
            priceList.add(toFarePrice(requireTypePrice(byType, TYPE_ADU, 0, "成人"), 0));
        }
        if (childCount > 0) {
            priceList.add(toFarePrice(requireTypePrice(byType, TYPE_CHI, 1, "儿童"), 1));
        }
        if (infantCount > 0) {
            priceList.add(toFarePrice(requireTypePrice(byType, TYPE_INF, 2, "婴儿"), 2));
        }
        if (priceList.isEmpty()) {
            throw new IllegalArgumentException("至少需要 1 名乘客才能组装 routing 价格");
        }
        return priceList;
    }

    private FlightPriceVO resolvePricing(OfferVO offer) {
        if (offer.getDisplay() == null || offer.getDisplay().getFare() == null) {
            return null;
        }
        FareCardDisplayVO fare = offer.getDisplay().getFare();
        return fare.getPricing();
    }

    private FlightPassengerTypePriceVO requireTypePrice(Map<String, FlightPassengerTypePriceVO> byType,
                                                        String code, int passengerType, String label) {
        FlightPassengerTypePriceVO price = byType.get(code);
        if (price == null || !Boolean.TRUE.equals(price.getAvailable())) {
            throw new IllegalArgumentException(label + "票价不可用，请更换政策或调整乘客人数");
        }
        if (price.getFare() == null) {
            throw new IllegalArgumentException(label + "票价缺少票面价，无法验价");
        }
        BigDecimal tax = price.getTax() != null ? price.getTax() : BigDecimal.ZERO;
        if (tax.compareTo(BigDecimal.ZERO) <= 0) {
            tax = BigDecimal.ONE;
        }
        price.setPassengerType(passengerType);
        return price;
    }

    private FarePriceDto toFarePrice(FlightPassengerTypePriceVO source, int passengerType) {
        BigDecimal fare = source.getFare();
        BigDecimal tax = source.getTax() != null && source.getTax().compareTo(BigDecimal.ZERO) > 0
                ? source.getTax() : BigDecimal.ONE;
        FarePriceDto dto = new FarePriceDto();
        dto.setPassengerType(passengerType);
        dto.setPrice(fare);
        dto.setPublishPrice(fare);
        dto.setTaxFeeAmount(tax);
        return dto;
    }

    private String resolveFareBasis(String fareBasis, String seatClass) {
        if (StrUtil.isNotBlank(fareBasis) && !"null".equalsIgnoreCase(fareBasis.trim())) {
            return fareBasis.trim();
        }
        return StrUtil.blankToDefault(seatClass, "Y");
    }

    private List<com.hsyd.mx.modules.df.flight.dto.FareVerifyFormatBaggageDto> copyBaggageList(OfferDetailsVO.TuVO tu) {
        if (tu.getFormatBaggageDetailList() == null) {
            return null;
        }
        List<com.hsyd.mx.modules.df.flight.dto.FareVerifyFormatBaggageDto> list = new ArrayList<>();
        for (OfferDetailsVO.FormatBaggageDetailVO item : tu.getFormatBaggageDetailList()) {
            com.hsyd.mx.modules.df.flight.dto.FareVerifyFormatBaggageDto dto =
                    new com.hsyd.mx.modules.df.flight.dto.FareVerifyFormatBaggageDto();
            dto.setBaggagePiece(item.getBaggagePiece());
            dto.setBaggageWeight(item.getBaggageWeight());
            dto.setFlightSeq(item.getFlightSeq());
            dto.setPassengerType(item.getPassengerType());
            dto.setSegmentNo(item.getSegmentNo());
            list.add(dto);
        }
        return list;
    }

    private List<com.hsyd.mx.modules.df.flight.dto.FareVerifyRefundInfoDto> copyRefundList(OfferDetailsVO.TuVO tu) {
        if (tu.getRefundInfoList() == null) {
            return null;
        }
        List<com.hsyd.mx.modules.df.flight.dto.FareVerifyRefundInfoDto> list = new ArrayList<>();
        for (OfferDetailsVO.RefundInfoVO item : tu.getRefundInfoList()) {
            com.hsyd.mx.modules.df.flight.dto.FareVerifyRefundInfoDto dto =
                    new com.hsyd.mx.modules.df.flight.dto.FareVerifyRefundInfoDto();
            dto.setPassengerType(item.getPassengerType());
            dto.setRefNoShowCondition(item.getRefNoShowCondition() != null ? item.getRefNoShowCondition() : 0);
            dto.setRefNoshow(item.getRefNoshow());
            dto.setRefNoshowFee(toIntFee(item.getRefNoshowFee()));
            dto.setRefundFee(toIntFee(item.getRefundFee()));
            dto.setRefundStatus(item.getRefundStatus());
            dto.setRefundType(item.getRefundType());
            list.add(dto);
        }
        return list;
    }

    private List<com.hsyd.mx.modules.df.flight.dto.FareVerifyChangeInfoDto> copyChangesList(OfferDetailsVO.TuVO tu) {
        if (tu.getChangesInfoList() == null) {
            return null;
        }
        List<com.hsyd.mx.modules.df.flight.dto.FareVerifyChangeInfoDto> list = new ArrayList<>();
        for (OfferDetailsVO.ChangesInfoVO item : tu.getChangesInfoList()) {
            com.hsyd.mx.modules.df.flight.dto.FareVerifyChangeInfoDto dto =
                    new com.hsyd.mx.modules.df.flight.dto.FareVerifyChangeInfoDto();
            dto.setChangesFee(toIntFee(item.getChangesFee()));
            dto.setChangesStatus(item.getChangesStatus());
            dto.setChangesType(item.getChangesType());
            dto.setPassengerType(item.getPassengerType());
            dto.setRevNoShowCondition(item.getRevNoShowCondition() != null ? item.getRevNoShowCondition() : 0);
            dto.setRevNoshow(item.getRevNoshow());
            dto.setRevNoshowFee(toIntFee(item.getRevNoshowFee()));
            list.add(dto);
        }
        return list;
    }

    private static int toIntFee(BigDecimal fee) {
        if (fee == null) {
            return 0;
        }
        return fee.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }
}
