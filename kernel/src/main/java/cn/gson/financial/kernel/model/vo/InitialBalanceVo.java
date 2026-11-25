package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.Subject;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.pojo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class InitialBalanceVo extends Subject {

    private Long subjectId;

    private Integer balanceId;

    private Double beginBalance;

    private Double debitAccumulation;

    private Double creditAccumulation;

    private Double initialBalance;

    private Double num;
    /**
     * 期初累计借方
     */
    private Double cumulativeDebit;

    /**
     * 期初累计贷方
     */
    private Double cumulativeCredit;

    private Double cumulativeDebitNum;

    private Double cumulativeCreditNum;

    private boolean leaf;

    private boolean ad;

    private Subject parentSubject;

    private List<InitialBalanceVo> children;
}
