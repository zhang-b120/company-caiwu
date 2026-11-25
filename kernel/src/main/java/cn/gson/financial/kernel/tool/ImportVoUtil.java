package cn.gson.financial.kernel.tool;

import cn.gson.financial.kernel.annotation.ColName;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelWriter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.hr.kernel.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2021年08月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
public final class ImportVoUtil {

    /**
     * 获取列别名
     *
     * @param type
     * @return
     */
    public static void setHeaderAlias(Class<?> type, ExcelWriter writer) {
        setHeaderAlias(type, writer, false);
    }

    /**
     * @param type
     * @param revert
     * @return
     */
    public static void setHeaderAlias(Class<?> type, ExcelWriter writer, boolean revert) {
        Field[] colFields = ReflectUtil.getFields(type, field -> field.isAnnotationPresent(ColName.class));
        for (Field colField : colFields) {
            if (revert) {
                writer.addHeaderAlias(colField.getAnnotation(ColName.class).value(), colField.getName());
            } else {
                writer.addHeaderAlias(colField.getName(), colField.getAnnotation(ColName.class).value());
            }
        }
    }

    public static void setHeaderAlias(Class<?> type, ExcelReader reader) {
        Field[] colFields = ReflectUtil.getFields(type, field -> field.isAnnotationPresent(ColName.class));
        for (Field colField : colFields) {
            reader.addHeaderAlias(colField.getAnnotation(ColName.class).value(), colField.getName());
        }
    }
}
