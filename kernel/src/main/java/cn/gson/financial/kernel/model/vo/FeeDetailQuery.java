package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.common.LocalDateRange;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年07月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FeeDetailQuery extends LocalDateRange {
    private Set<Long> subjectId;
}
