package cn.gson.financial.kernel.model.vo;

import lombok.Data;

import java.util.List;

/**
 *  cn.gson.financial.kernel.model.vo
 *  <p>Creation: 2025年01月06日</p>
 * @version 1.0
 * @author 开发者
 */
@Data
public class CashierAccountCheckVo {
    private Long id;
    private String code;
    private Long accountSetsId;
    private String name;
    private String currencyName;
    private Long currencyId;
    private Long subjectId;
    private String subjectCode;
    private Double sr;
    private Double zc;
    private Double initBalance;
    private Double balance;
    private String project;
    private Long parentId;
    private List<CashierAccountCheckVo> children;
}
