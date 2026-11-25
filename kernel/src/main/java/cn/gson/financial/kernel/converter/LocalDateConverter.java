package cn.gson.financial.kernel.converter;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.framework.converter</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年01月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
public class LocalDateConverter implements Converter<String, LocalDate> {

    @Override
    public LocalDate convert(String source) {
        if (StrUtil.isEmpty(source)) {
            return null;
        }
        return DateUtil.parse(source, "yyyy-MM-dd", "yyyy-MM", "yyyy年MM期", "yyyy年MM月").toSqlDate().toLocalDate();
    }
}
