package cn.gson.financial.kernel.model.crm.resp;

import cn.gson.financial.kernel.model.enums.AccountStandard;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-06-30
 * @Description
 */
@Data
public class GetAccountSetInfoResp implements Serializable {
    private static final long serialVersionUID = 6878856298240315863L;

    /**
     * 账套ID
     */
    private Long id;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 公司税号
     */
    private String creditCode;

    /**
     * 公司类型
     */
    private String vatTypeName;

    /**
     * 省份
     */
    private String province;

    /**
     * 是否零申报
     */
    private Boolean zeroDeclarations;

    /**
     * 会计准则
     */
    private String accountStandard;

    /**
     * 当前账期
     */
    private LocalDate currentAccountDate;

    /**
     * 结账状态 0-未结转未结账 1-已结转未结账 2-已结转已结账
     */
    private Integer checkOutStatus;

    /**
     * 进项发票张数
     */
    private Long inInvoiceTotalCount = 0L;

    /**
     * 进项发票金额
     */
    private BigDecimal inInvoiceTotalAmount = new BigDecimal("0");

    /**
     * 销项发票张数
     */
    private Long outInvoiceTotalCount = 0L;

    /**
     * 销项发票金额
     */
    private BigDecimal outInvoiceTotalAmount = new BigDecimal("0");

    /**
     * 凭证数量
     */
    private Integer voucherTotalCount = 0;

    /**
     * 报税条目总数量
     */
    private Integer taxDeclaration = 0;

    /**
     * 申报完成报税条目数量
     */
    private Integer taxDeclarationSuccess = 0;

    /**
     * 员工人数
     */
    private Long employeeTotalCount = 0L;

    /**
     * 员工工资笔数
     */
    private Integer salaryTotalCount = 0;

    /**
     * 员工工资
     */
    private BigDecimal salaryTotalAmount = new BigDecimal("0");

    /**
     * 支出总额
     */
    private BigDecimal payTotalAmount = new BigDecimal("0");

    /**
     * 收入总额
     */
    private BigDecimal receiveTotalAmount = new BigDecimal("0");

    /**
     * 应缴税费
     */
    private BigDecimal taxesPayAccount = new BigDecimal("0");
}