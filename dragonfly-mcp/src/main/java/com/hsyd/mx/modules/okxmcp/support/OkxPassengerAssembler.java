package com.hsyd.mx.modules.okxmcp.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsyd.mx.common.enums.PassengerCertTypeEnum;
import com.hsyd.mx.modules.df.flight.dto.FareContactDto;
import com.hsyd.mx.modules.df.flight.dto.FarePassengerDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * OKX MCP 生单乘机人/联系人组装：支持 JSON 与扁平参数，自动补全可推理字段。
 */
@Component
@RequiredArgsConstructor
public class OkxPassengerAssembler {

    private static final String DEFAULT_NATIONALITY = "CN";
    private static final String DEFAULT_CARD_TYPE = "ID";
    private static final String DEFAULT_CONTACT_EMAIL = "passenger@example.com";
    private static final int AGE_TYPE_ADULT = 0;
    private static final int AGE_TYPE_CHILD = 1;
    private static final int AGE_TYPE_INFANT = 2;

    private final ObjectMapper objectMapper;

    public List<FarePassengerDto> resolvePassengers(String passengersJson,
                                                    String passengerName,
                                                    String passengerCardNum,
                                                    String passengerCardType,
                                                    String passengerCardExpired,
                                                    Integer passengerAgeType,
                                                    String passengerBirthday) {
        if (StrUtil.isNotBlank(passengersJson)) {
            return enrichPassengers(parsePassengersJson(passengersJson));
        }
        return List.of(buildFlatPassenger(
                passengerName, passengerCardNum, passengerCardType, passengerCardExpired,
                passengerAgeType, passengerBirthday));
    }

    public FareContactDto resolveContact(String contactJson,
                                         String contactName,
                                         String contactMobile,
                                         String contactEmail) {
        if (StrUtil.isNotBlank(contactJson)) {
            return parseContactJson(contactJson);
        }
        if (StrUtil.isBlank(contactName)) {
            throw new IllegalArgumentException("联系人姓名不能为空，请提供 contactName 或 contactJson");
        }
        if (StrUtil.isBlank(contactMobile)) {
            throw new IllegalArgumentException("联系人手机不能为空，请提供 contactMobile 或 contactJson");
        }
        FareContactDto contact = new FareContactDto();
        contact.setName(contactName.trim());
        contact.setMobile(contactMobile.trim());
        contact.setEmail(StrUtil.blankToDefault(contactEmail, DEFAULT_CONTACT_EMAIL).trim());
        contact.setMobileCountryCode("CN");
        contact.setMobileArea("+86");
        return contact;
    }

    public void validatePassengerCounts(List<FarePassengerDto> passengers,
                                        int adultNumber, int childNumber, int infantNumber) {
        int adults = 0;
        int children = 0;
        int infants = 0;
        for (FarePassengerDto passenger : passengers) {
            if (passenger == null || passenger.getAgeType() == null) {
                continue;
            }
            switch (passenger.getAgeType()) {
                case AGE_TYPE_ADULT -> adults++;
                case AGE_TYPE_CHILD -> children++;
                case AGE_TYPE_INFANT -> infants++;
                default -> throw new IllegalArgumentException("不支持的乘客类型 ageType=" + passenger.getAgeType()
                        + "，仅支持 0成人/1儿童/2婴儿");
            }
        }
        if (adults != adultNumber || children != childNumber || infants != infantNumber) {
            throw new IllegalArgumentException(String.format(
                    "乘机人类型数量与验价人数不一致：验价成人%d/儿童%d/婴儿%d，生单成人%d/儿童%d/婴儿%d",
                    adultNumber, childNumber, infantNumber, adults, children, infants));
        }
    }

    private List<FarePassengerDto> parsePassengersJson(String passengersJson) {
        try {
            List<FarePassengerDto> passengers = objectMapper.readValue(
                    passengersJson, new TypeReference<List<FarePassengerDto>>() {});
            if (passengers == null || passengers.isEmpty()) {
                throw new IllegalArgumentException("passengersJson 不能为空数组");
            }
            return passengers;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("passengersJson 格式无效: " + ex.getMessage());
        }
    }

    private FareContactDto parseContactJson(String contactJson) {
        try {
            FareContactDto contact = objectMapper.readValue(contactJson, FareContactDto.class);
            if (contact == null) {
                throw new IllegalArgumentException("contactJson 不能为空");
            }
            if (StrUtil.isBlank(contact.getName())) {
                throw new IllegalArgumentException("contactJson.name 不能为空");
            }
            if (StrUtil.isBlank(contact.getMobile())) {
                throw new IllegalArgumentException("contactJson.mobile 不能为空");
            }
            if (StrUtil.isBlank(contact.getEmail())) {
                contact.setEmail(DEFAULT_CONTACT_EMAIL);
            }
            return contact;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("contactJson 格式无效: " + ex.getMessage());
        }
    }

    private List<FarePassengerDto> enrichPassengers(List<FarePassengerDto> passengers) {
        List<FarePassengerDto> enriched = new ArrayList<>();
        for (FarePassengerDto passenger : passengers) {
            enriched.add(enrichPassenger(passenger));
        }
        return enriched;
    }

    private FarePassengerDto buildFlatPassenger(String passengerName,
                                                String passengerCardNum,
                                                String passengerCardType,
                                                String passengerCardExpired,
                                                Integer passengerAgeType,
                                                String passengerBirthday) {
        if (StrUtil.isBlank(passengerName)) {
            throw new IllegalArgumentException("乘机人姓名不能为空，请提供 passengerName 或 passengersJson");
        }
        if (StrUtil.isBlank(passengerCardNum)) {
            throw new IllegalArgumentException("证件号码不能为空，请提供 passengerCardNum 或 passengersJson");
        }
        FarePassengerDto passenger = new FarePassengerDto();
        passenger.setName(passengerName.trim());
        passenger.setCardNum(passengerCardNum.trim());
        passenger.setCardType(StrUtil.blankToDefault(passengerCardType, DEFAULT_CARD_TYPE).trim());
        passenger.setCardExpired(StrUtil.trim(passengerCardExpired));
        passenger.setAgeType(passengerAgeType != null ? passengerAgeType : AGE_TYPE_ADULT);
        passenger.setBirthday(StrUtil.trim(passengerBirthday));
        return enrichPassenger(passenger);
    }

    private FarePassengerDto enrichPassenger(FarePassengerDto passenger) {
        if (passenger == null) {
            throw new IllegalArgumentException("乘机人不能为空");
        }
        if (passenger.getAgeType() == null) {
            passenger.setAgeType(AGE_TYPE_ADULT);
        }
        if (StrUtil.isBlank(passenger.getNationality())) {
            passenger.setNationality(DEFAULT_NATIONALITY);
        }
        if (StrUtil.isBlank(passenger.getCardIssuePlace())) {
            passenger.setCardIssuePlace(DEFAULT_NATIONALITY);
        }
        if (StrUtil.isBlank(passenger.getCardType())) {
            passenger.setCardType(DEFAULT_CARD_TYPE);
        }

        boolean idCard = DEFAULT_CARD_TYPE.equalsIgnoreCase(passenger.getCardType())
                || "NI".equalsIgnoreCase(passenger.getCardType());
        if (idCard) {
            OkxIdCardParser.ParseResult parsed = OkxIdCardParser.parse(passenger.getCardNum());
            if (parsed.isParsed()) {
                if (StrUtil.isBlank(passenger.getBirthday())) {
                    passenger.setBirthday(parsed.getBirthday());
                }
                if (StrUtil.isBlank(passenger.getGender())) {
                    passenger.setGender(parsed.getGender());
                }
            }
        }

        if (passenger.getAgeType() == AGE_TYPE_CHILD || passenger.getAgeType() == AGE_TYPE_INFANT) {
            if (StrUtil.isBlank(passenger.getBirthday())) {
                String label = passenger.getAgeType() == AGE_TYPE_CHILD ? "儿童" : "婴儿";
                throw new IllegalArgumentException(label + "乘机人必须提供 birthday（yyyyMMdd）或 passengersJson");
            }
        }
        validateCardExpired(passenger);
        if (StrUtil.isBlank(passenger.getGender())) {
            passenger.setGender("M");
        }
        return passenger;
    }

    private void validateCardExpired(FarePassengerDto passenger) {
        PassengerCertTypeEnum certType = PassengerCertTypeEnum.resolveCertType(passenger.getCardType());
        if (certType == null || !certType.isNeedExpiry()) {
            return;
        }
        if (StrUtil.isBlank(passenger.getCardExpired())) {
            throw new IllegalArgumentException(String.format(
                    "证件类型 %s 必须提供证件有效期 cardExpired（yyyyMMdd），请通过 passengersJson.cardExpired 或 passengerCardExpired 传入",
                    passenger.getCardType()));
        }
    }
}
