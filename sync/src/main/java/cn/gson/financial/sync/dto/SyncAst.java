package cn.gson.financial.sync.dto;

import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 同步账套信息</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年08月02日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Builder
@Getter
public class SyncAst {

    /**
     * 账套ID
     */
    private String id;
    /**
     * 账套名称
     */
    private String name;
    /**
     * 会计准则
     */
    private AccountStandard accountingStandard;
    /**
     * 增值税种类
     */
    private VatType subAccountingStandard;

    private LocalDate startDate;

    private LocalDate currentDate;

    private String encoding;

    private List<Integer> encodings;
}
