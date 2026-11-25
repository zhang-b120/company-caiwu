package cn.gson.financial.kernel.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LocalDateRange {
    private LocalDate start;
    private LocalDate end;

    public boolean isEmpty() {
        return start == null && end == null;
    }

    public boolean isNotEmpty() {
        return start != null && end != null;
    }

    public String title(String format) {
        if (start.isEqual(end)) {
            return start.format(DateTimeFormatter.ofPattern(format));
        }
        return start.format(DateTimeFormatter.ofPattern(format)) + "至" + end.format(DateTimeFormatter.ofPattern(format));
    }
}
