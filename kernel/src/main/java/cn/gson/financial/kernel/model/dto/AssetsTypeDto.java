package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AssetsType;
import lombok.Getter;
import lombok.Setter;

/**
 * 
 * <p><b>Copyright © 2010-2020开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022/11/3 10:58</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
public class AssetsTypeDto extends AssetsType {

    private String depreciationName;

    private String costName;
}
