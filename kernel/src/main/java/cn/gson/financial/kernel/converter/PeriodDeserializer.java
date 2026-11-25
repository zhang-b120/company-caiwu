package cn.gson.financial.kernel.converter;

import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : 期间日期格式化</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class PeriodDeserializer implements ObjectDeserializer {

    @Override
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object o) {
        Object parse = parser.parse();
        if (parse != null) {
            return (T) LocalDate.parse(parse.toString() + "01日", DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        }
        return null;
    }

    @Override
    public int getFastMatchToken() {
        return JSONToken.LITERAL_STRING;
    }
}

