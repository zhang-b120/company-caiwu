package cn.gson.financial.kernel.common;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 *
 * <p><b>Copyright © 2010-2018开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.flyemu.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2018年11月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
public final class DateUtil {

    public static final String YYYYMMDD = "yyyyMMdd";
    public static final String YYYY_MM_DD = "yyyy-MM-dd";

    // 定义可能的时间格式
    private static final List<DateTimeFormatter> DATE_FORMATTERS = new ArrayList<>();

    static {
        // 初始化所有可能的格式
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMddHH:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMddHH:mm"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMdd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("MM/dd/yy"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("MM/dd/yy HH:mm"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("MM/d/yy"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("M/dd/yy"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("M/d/yy"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMdd H:mm:ss"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
    }

    /**
     * 获取给定日期月的开始时间
     *
     * @param date
     * @return
     */
    public static Date getMonthBegin(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.DATE, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 获取给定日期年的开始时间
     *
     * @param date
     * @return
     */
    public static Date getYearBegin(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.MONTH, 0);
        calendar.set(Calendar.DATE, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 获取给定日期年的开始时间
     *
     * @param date
     * @return
     */
    public static Date getYearEnd(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.MONTH, 11);
        calendar.set(Calendar.DATE, 31);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    /**
     * 获取当前月的开始时间
     *
     * @return
     */
    public static Date getMonthBegin() {
        return getMonthBegin(new Date());
    }

    /**
     * 获取给定日期月的结束时间
     *
     * @param date
     * @return
     */
    public static Date getMonthEndWithTime(Date date, boolean withTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.DATE, calendar.getActualMaximum(Calendar.DATE));
        if (withTime) {
            calendar.set(Calendar.HOUR_OF_DAY, 23);
            calendar.set(Calendar.MINUTE, 59);
            calendar.set(Calendar.SECOND, 59);
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
        }
        return calendar.getTime();
    }

    public static Date getMonthEnd(Date date) {
        return getMonthEndWithTime(date, true);
    }

    public static Date getMonthEnd(LocalDate date) {
        return getMonthEndWithTime(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()), true);
    }

    /**
     * 获取当前月的结束时间
     *
     * @return
     */
    public static Date getMonthEnd() {
        return getMonthEnd(new Date());
    }

    public static Date getYearBegin(LocalDate date) {
        return getYearBegin(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    public static Date getMonthBegin(LocalDate date) {
        return getMonthBegin(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    /**
     * 字符串转成LocalDate
     * @param time
     * @return
     */
    public static LocalDate timeStrToLocalDate(String time, String pattern) {
        return LocalDate.parse(time, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * localDateTime转成Date
     * @param localDateTime
     * @return
     */
    public static Date localDateTimeToDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone( ZoneId.systemDefault()).toInstant());
    }

    /**
     * 根据日期所属季度的第一天
     * @param localDate
     * @return
     */
    public static LocalDate getFirstDayOfQuarter(LocalDate localDate) {
        if(ObjectUtils.isEmpty(localDate) || localDate == null){
            return null;
        }
        int quarter = (localDate.getMonthValue() - 1) / 3 + 1;
        Month firstMonthOfQuarter = Month.of((quarter - 1) * 3 + 1);
        return LocalDate.of(localDate.getYear(), firstMonthOfQuarter, 1);
    }

    /**
     * 根据当前日期返回相应月份的第一天
     * 1-15号返回上个月1号，16号至月底返回当月1号
     * @return
     */
    public static LocalDate getReferenceDate() {
        LocalDate today = LocalDate.now();
        int dayOfMonth = today.getDayOfMonth();

        if (dayOfMonth <= 15) {
            // 返回上个月1号
            return today.minusMonths(1).withDayOfMonth(1);
        } else {
            // 返回当月1号
            return today.withDayOfMonth(1);
        }
    }

    /**
     * LocalDate格式化为string
     * @param localDate
     * @param pattern
     * @return
     */
    public static String localDateToString(LocalDate localDate,String pattern) {
        if (null == localDate || null==pattern) {
            return null;
        }
        return  DateTimeFormatter.ofPattern(pattern).format(localDate);
    }

    /**
     * 将时间字符串转换为LocalDate
     * @param dateStr
     * @return
     */
    public static LocalDate convertToLocalDate(String dateStr) {
        if (StringUtils.isEmpty(dateStr)) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }
}
