package com.hsyd.mx.modules.okxmcp.support;

/**
 * 18 位身份证号解析工具（OKX MCP 独立副本）。
 */
public final class OkxIdCardParser {

    private OkxIdCardParser() {
    }

    public static ParseResult parse(String idCardNum) {
        if (idCardNum == null || idCardNum.length() != 18) {
            return ParseResult.notParsed();
        }
        String birthday = idCardNum.substring(6, 14);
        char genderDigit = idCardNum.charAt(16);
        String gender = ((genderDigit - '0') % 2 == 1) ? "M" : "F";
        return new ParseResult(true, birthday, gender);
    }

    public static final class ParseResult {
        private final boolean parsed;
        private final String birthday;
        private final String gender;

        private ParseResult(boolean parsed, String birthday, String gender) {
            this.parsed = parsed;
            this.birthday = birthday;
            this.gender = gender;
        }

        static ParseResult notParsed() {
            return new ParseResult(false, null, null);
        }

        public boolean isParsed() {
            return parsed;
        }

        public String getBirthday() {
            return birthday;
        }

        public String getGender() {
            return gender;
        }
    }
}
