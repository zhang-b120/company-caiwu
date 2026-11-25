package cn.gson.financial.kernel.model.vo;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class TimeSeriesQueryVo {
    private LocalDate start;
    private LocalDate end;
    private String word;
    private Long createMember;
    private Long accountSetsId;
    private String summary;
    private String subjectId;
    private Double amountStart;
    private Double amountEnd;
    private Integer codeStart;
    private Integer codeEnd;
    private String sort;

    public String getSummary() {
        return StrUtil.isNotEmpty(summary) ? "%" + summary + "%" : null;
    }

    public LocalDate getEnd() {
        return end != null ? end.plusMonths(1).minusDays(1) : null;
    }

    public Double getAmountStart() {
        return amountStart != null ? amountStart : (amountEnd != null ? Double.MIN_VALUE : null);
    }

    public Double getAmountEnd() {
        return amountEnd != null ? amountEnd : (amountStart != null ? Double.MAX_VALUE : null);
    }

    public Integer getCodeStart() {
        return codeStart != null ? codeStart : (codeEnd != null ? Integer.MIN_VALUE : null);
    }

    public Integer getCodeEnd() {
        return codeEnd != null ? codeEnd : (codeStart != null ? Integer.MAX_VALUE : null);
    }
}
