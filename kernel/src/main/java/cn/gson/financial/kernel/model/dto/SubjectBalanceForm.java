package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AcDetails;
import lombok.Data;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SubjectBalanceForm {
    private Long subjectId;
    private Long currencyId;
    private List<AcDetails> acDetails;
}
