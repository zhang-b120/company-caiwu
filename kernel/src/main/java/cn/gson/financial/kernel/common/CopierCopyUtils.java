package cn.gson.financial.kernel.common;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.hutool.core.bean.BeanUtil;
import org.apache.commons.collections.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-05-15
 * @Description
 */
public class CopierCopyUtils {
    /**
     * 对象转换
     *
     * @param source 源对象
     * @param targetClass 目标类
     * @return 目标对象
     */
    public static <T> T convertTo(Object source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }
        return BeanUtil.toBean(source, targetClass);
    }

    /**
     * 对象转换（带自定义转换器）
     *
     * @param source 源对象
     * @param targetClass 目标类
     * @param converter 转换器
     * @return 目标对象
     */
    public static <T> T convertTo(Object source, Class<T> targetClass, Function<T, T> converter) {
        T target = convertTo(source, targetClass);
        if (target != null && converter != null) {
            return converter.apply(target);
        }
        return target;
    }

    /**
     * 集合对象转换
     *
     * @param sourceList 源对象集合
     * @param targetClass 目标类
     * @return 目标对象集合
     */
    public static <T> List<T> convertTo(List<?> sourceList, Class<T> targetClass) {
        if (CollectionUtils.isEmpty(sourceList)) {
            return Collections.emptyList();
        }
        return sourceList.stream()
                .map(source -> convertTo(source, targetClass))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 集合对象转换（带自定义转换器）
     *
     * @param sourceList 源对象集合
     * @param targetClass 目标类
     * @param converter 转换器
     * @return 目标对象集合
     */
    public static <T> List<T> convertTo(List<?> sourceList, Class<T> targetClass, Function<T, T> converter) {
        if (CollectionUtils.isEmpty(sourceList)) {
            return Collections.emptyList();
        }
        return sourceList.stream()
                .map(source -> convertTo(source, targetClass, converter))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 对象属性拷贝
     *
     * @param source 源对象
     * @param target 目标对象
     */
    public static void copyProperties(Object source, Object target) {
        if (source == null || target == null) {
            return;
        }
        BeanUtil.copyProperties(source, target);
    }

    /**
     * 对象属性拷贝（忽略空值）
     *
     * @param source 源对象
     * @param target 目标对象
     */
    public static void copyPropertiesIgnoreNull(Object source, Object target) {
        if (source == null || target == null) {
            return;
        }
        BeanUtil.copyProperties(source, target, true);
    }
}