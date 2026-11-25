package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import lombok.Data;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class BatchSocialVo {

    private List<Long> records;

    private List<SalarySocialEmployeeDto> socials;
    /**
     * 是否购买社保
     */
    private Boolean purchase;
}
