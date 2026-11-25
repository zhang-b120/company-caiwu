package cn.gson.financial.kernel.model.vo;

import lombok.Data;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class AccountSummaryItemVo {

    private Long id;
    private String code;
    private String type;
    private String name;
    private Double sr;
    private Double zc;
    private Integer srbs;
    private Integer zcbs;
    private Double balance;
    private Double initialBalance;
}
