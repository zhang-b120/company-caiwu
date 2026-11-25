package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.entity.Subject;
import lombok.Data;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class BiVarVo {
    private String aging;
    private String field;
    private String varName;
    private Subject subject;
    private AccountingCategory category;
    private AcDetails auxiliary;

    public String key() {
        if (auxiliary != null) {
            return subject.getId() + ":" + auxiliary.getId();
        }
        return subject.getId() + "";
    }

    public boolean isAC() {
        return auxiliary != null && category != null;
    }
}
