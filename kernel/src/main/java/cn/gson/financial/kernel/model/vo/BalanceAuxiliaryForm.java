package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AuxiliaryDetail;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class BalanceAuxiliaryForm {

    private Long subjectId;
    private Long currencyId;
    private List<List<AuxiliaryDetail.Detail>> details = new ArrayList<>();
}
