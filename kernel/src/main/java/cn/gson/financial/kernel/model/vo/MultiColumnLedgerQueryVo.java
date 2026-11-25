package cn.gson.financial.kernel.model.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年11月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Data
public class MultiColumnLedgerQueryVo {
    private Long subjectId;
    /**
     * 1:'只显示明细科目',2:'只显示下级科目'
     */
    private Integer subjectShow = 1;
    private Boolean showBalance = true;
    private LocalDate start;
    private LocalDate end;
    private Long accountSetsId;
    private Long localCurrencyId;

    public LocalDate getEnd() {
        return end.withDayOfMonth(end.lengthOfMonth());
    }

    public LocalDate getStart() {
        return start.withDayOfMonth(1);
    }
}
