package cn.gson.financial.kernel.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@AllArgsConstructor
@ToString
public class BalanceKey {
    private Long subjectId;
    private Long currencyId;
    private Long auxiliaryDetailId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BalanceKey that = (BalanceKey) o;
        return Objects.equals(subjectId, that.subjectId) && Objects.equals(currencyId, that.currencyId) && Objects.equals(auxiliaryDetailId, that.auxiliaryDetailId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId, currencyId, auxiliaryDetailId);
    }
}
