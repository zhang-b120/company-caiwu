package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 与表 fxy_financial_financial_process 对应的实体
 */
@DynamicInsert
@DynamicUpdate
@Entity
@Data
@Table(name = "financial_process")
public class FinancialProcess {

    @Id
    @Comment("主键")
    private Integer id;

    @Comment("账套ID")
    @Column(name = "account_sets_id")
    private Long accountSetsId;

    @Comment("会计期间，格式示例：2025-09 或 2025-09-01")
    @Column(name = "accounting_period")
    private String accountingPeriod;

    @Comment("是否已取票：是/否")
    @Column(name = "is_ticket_obtained")
    private String isTicketObtained;

    @Comment("是否税目完成：是/否")
    @Column(name = "is_tax_item_completed")
    private String isTaxItemCompleted;

    @Comment("是否已做账：是/否")
    @Column(name = "is_accounting_done")
    private String isAccountingDone;

    @Comment("是否完成申报：是/否")
    @Column(name = "is_tax_filing_completed")
    private String isTaxFilingCompleted;

    @Comment("是否已做工资：是/否")
    @Column(name = "is_payroll_processed")
    private String isPayrollProcessed;

    @Comment("是否人员信息同步：是/否")
    @Column(name = "is_personnel_info_synced")
    private String isPersonnelInfoSynced;

    @Comment("是否个税完成：是/否")
    @Column(name = "is_individual_tax_completed")
    private String isIndividualTaxCompleted;
}


