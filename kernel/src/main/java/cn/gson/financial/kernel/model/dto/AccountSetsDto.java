package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.VatType;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.AccountSets} entity
 */
@Data
public class AccountSetsDto implements Serializable {
    private Long id;
    private String companyName;
    private LocalDate enableDate;
    private String creditCode;
    private AccountStandard accountStandard;
    private VatType vatType;
    private String address;
    private Boolean cashierModule;
    /** @deprecated 旧版内部分类编号，已废弃，保留仅为兼容历史数据；新数据请用 industryCode / industryName */
    @Deprecated
    private Integer industry;
    /** 行业代码（国标 GB/T 4754） */
    private String industryCode;
    /** 行业名称 */
    private String industryName;
    /** 是否个体工商户 */
    private Boolean individualBusiness;
    /** 企业经营范围 */
    private String businessScope;
    private Boolean fixedAssetModule;
    private Boolean voucherReviewed;
    private Date createDate;
    private Long creatorId;
    private String creatorName;
    private String roleName;
    private String creatorMobile;
    private Long voucherCount;
    private LocalDate currentAccountDate;
    private List<Integer> encoding;
    private List<SubjectCategory> subjectCategory;
    private Boolean journal;
    private Boolean assets;
    private LocalDate effectiveDate;
    private Long astId;
    private Boolean imported;
    private String syncType;
    private String electricityTaxCode;
    private String mobile;
    private String province;
    private String electricityTaxPhone;
    private Boolean zeroDeclarations;
    private Boolean deactivated;
    private String individualTaxCode;
    private String individualTax;
    /**
     * 备注（与账期无关）
     */
    private String remark;
    /**
     * 员工总数
     */
    private Long employeeTotalCount;
    /**
     * 该账期是否有工资（用于批量复制筛选）
     */
    private Boolean hasSalary;
    /**
     * 当前月份是否有工资（系统当前月份）
     */
    private Boolean hasCurrentMonthSalary;
    /**
     * 是否已完成期初录入（该账套存在期初余额数据）
     */
    private Boolean initialBalanceCompleted;
    /**
     * 期初录入状态：0 未执行 1 进行中 2 失败 3 成功（来自 account_set_period_status）
     */
    private Integer initialBalanceStatus;
    /**
     * 做账状态：0 未执行 1 进行中 2 失败 3 成功
     */
    private Integer accountingStatus;
    /**
     * 取票状态：0 未执行 1 进行中 2 失败 3 成功
     */
    private Integer invoiceStatus;
    /**
     * 报税状态：0 未执行 1 进行中 2 失败 3 成功
     */
    private Integer taxStatus;
    /**
     * 个税状态：0 未执行 1 进行中 2 失败 3 成功
     */
    private Integer individualTaxStatus;
}
