package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
public class SalarySocialDto implements Serializable {
    private Long id;

    private Long accountSetsId;

    private String name;

    private String personRatio;

    private String companyRatio;

    private String status;
}
