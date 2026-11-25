package cn.gson.financial.kernel.common;

import cn.hutool.core.collection.CollUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-09-26
 * @Description
 */
public class MapToEntityUtils {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    /**
     * 将Map转换为实体类对象
     * @param map
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> T mapToEntity(Map<String, Object> map, Class<T> clazz) {
        if (CollUtil.isEmpty(map)) {
            return null;
        }
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();
            Field[] fields = getAllFields(clazz);

            Arrays.stream(fields)
                    .filter(field -> map.containsKey(field.getName()))
                    .forEach(field -> setFieldValue(instance, field, map.get(field.getName())));

            return instance;
        } catch (Exception e) {
            System.out.println("数据转换失败" + e);
            return null;
        }
    }

    /**
     * 批量转换
     * @param mapList
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> List<T> mapToList(List<Map<String, Object>> mapList, Class<T> clazz) {
        if (CollUtil.isEmpty(mapList)) {
            return new ArrayList<>();
        }
        return mapList.stream()
                .map(map -> mapToEntity(map, clazz))
                .toList();
    }

    /**
     * 获取实体类属性
     * @param clazz
     * @return
     */
    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;

        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }

    /**
     * 实体类属性赋值
     * @param instance
     * @param field
     * @param value
     * @param <T>
     */
    private static <T> void setFieldValue(T instance, Field field, Object value) {
        if (ObjectUtils.isEmpty(value)) {
            return;
        }
        try {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();

            Object convertedValue = convertValue(value, fieldType);
            if (ObjectUtils.isNotEmpty(convertedValue)) {
                field.set(instance, convertedValue);
            }
        } catch (Exception e) {
            System.out.println("设置字段值失败: " + field.getName() + ", 值: " + value);
        }
    }

    /**
     * 数据类型转换
     * @param value
     * @param targetType
     * @return
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        // 类型匹配直接返回
        if (targetType.isInstance(value)) {
            return value;
        }
        String str = value.toString();
        if(StringUtils.isEmpty(str)){
            return null;
        }
        try {
            // 字符串类型
            if (targetType == String.class) {
                return str;
            }
            // 数字类型
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.parseInt(cleanNumber(str));
            } else if (targetType == Long.class || targetType == long.class) {
                return Long.parseLong(cleanNumber(str));
            }else if (targetType == BigDecimal.class) {
                return new BigDecimal(cleanNumber(str));
            }
            // 日期时间类型
            else if (targetType == LocalDate.class) {
                return DateUtil.convertToLocalDate(str);
            }

        } catch (Exception e) {
            System.out.println("类型转换失败: " + value + " -> " + targetType.getSimpleName());
            return null;
        }
        return value;
    }

    /**
     * 清洗数值数据
     */
    private static String cleanNumber(String str) {
        if (str == null) {
            return null;
        }
        // 去除逗号,去除货币符号等特殊字符
        str = str.trim().replace(",", "").replaceAll("[￥¥$€£+-]", "");
        return str;
    }
}