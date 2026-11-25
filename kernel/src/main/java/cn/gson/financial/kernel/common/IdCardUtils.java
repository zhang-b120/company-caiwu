package cn.gson.financial.kernel.common;

import cn.gson.financial.kernel.model.enums.Gender;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 身份证号解析工具：从身份证号中解析出生日期、性别。
 * 支持15位和18位身份证。
 */
public final class IdCardUtils {

    private IdCardUtils() {
    }

    /**
     * 根据身份证号解析出生日期
     *
     * @param idCard 身份证号码（15位或18位）
     * @return 出生日期，解析失败返回 null
     */
    public static LocalDate parseBirthDate(String idCard) {
        if (idCard == null || idCard.trim().isEmpty()) {
            return null;
        }
        String s = idCard.trim();
        if (s.length() != 15 && s.length() != 18) {
            return null;
        }
        try {
            String birthDateStr;
            if (s.length() == 15) {
                birthDateStr = "19" + s.substring(6, 12);
            } else {
                birthDateStr = s.substring(6, 14);
            }
            return LocalDate.parse(birthDateStr, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据身份证号解析性别
     *
     * @param idCard 身份证号码（15位或18位）
     * @return 性别枚举，解析失败返回 null
     */
    public static Gender parseGender(String idCard) {
        if (idCard == null || idCard.trim().isEmpty()) {
            return null;
        }
        String s = idCard.trim();
        if (s.length() != 15 && s.length() != 18) {
            return null;
        }
        try {
            String genderStr = s.length() == 15 ? s.substring(14, 15) : s.substring(16, 17);
            int code = Integer.parseInt(genderStr);
            return code % 2 == 1 ? Gender.男 : Gender.女;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
