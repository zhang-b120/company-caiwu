package cn.gson.financial.kernel.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class NumBigDecimalSerializer extends JsonSerializer<BigDecimal> {
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializerProvider) throws IOException {
        if (Objects.nonNull(value)) {
            if (BigDecimal.ZERO.compareTo(value) == 0) {
                gen.writeNumber(0);
            } else if (value.scale() >= 5) {
                gen.writeString(value.setScale(5, RoundingMode.DOWN).stripTrailingZeros().toPlainString());
            } else {
                gen.writeString(value.stripTrailingZeros().toPlainString());
            }
        }
    }
}
