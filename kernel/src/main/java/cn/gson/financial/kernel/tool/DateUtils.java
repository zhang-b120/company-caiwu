package cn.gson.financial.kernel.tool;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

public class DateUtils {

    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 获取传入月份的季末
     *
     * @param inputDate
     * @return
     */
    public static LocalDate getLastDayOfQuarter(LocalDate inputDate) {
        if (inputDate == null) {
            inputDate = LocalDate.now(); // 可选：如果传入null则使用当前日期
        }
        int month = inputDate.getMonthValue();
        int quarterEndMonth = ((month - 1) / 3 + 1) * 3;
        YearMonth quarterEnd = YearMonth.of(inputDate.getYear(), quarterEndMonth);
        return quarterEnd.atEndOfMonth();
    }

    /**
     * 获取上个月的第一天
     *
     * @return LocalDate 上个月第一天的日期对象
     */
    public static LocalDate getFirstDayOfLastMonth() {
        return LocalDate.now().minusMonths(1).withDayOfMonth(1);
    }

    /**
     * 获取上个月的第一天（字符串格式）
     *
     * @return String 格式：yyyy-MM-dd
     */
    public static String getFirstDayOfLastMonthAsString() {
        return getFirstDayOfLastMonth().format(DEFAULT_FORMATTER);
    }


    /**
     * 获取上两个月的
     * @return
     */
    public static LocalDate getLastDayOfLastTwoMonth() {
            LocalDate today = LocalDate.now();
            LocalDate lastTwoMonth = today.minusMonths(2);
        return lastTwoMonth.withDayOfMonth(lastTwoMonth.lengthOfMonth());
    }

    /**
     * 获取年度第一天
     */
    public static LocalDate getFirstDayOfYear() {
        LocalDate current = LocalDate.now();
        return LocalDate.of(current.getYear(), 1, 1);
    }
}
