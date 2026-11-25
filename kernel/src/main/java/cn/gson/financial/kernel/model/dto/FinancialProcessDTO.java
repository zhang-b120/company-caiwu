package cn.gson.financial.kernel.model.dto;

import lombok.Data;

@Data
public class FinancialProcessDTO {

    private Long accountSetsId;

    private String accountingPeriod;

    private String isTicketObtained;

    private String isTaxItemCompleted;

    private String isAccountingDone;

    private String isTaxFilingCompleted;

    private String isPayrollProcessed;

    private String isPersonnelInfoSynced;

    private String isIndividualTaxCompleted;

    private String companyName;

    private String page;

    private String pageSize;

    private String vatType;

    private String failureReason;
}
