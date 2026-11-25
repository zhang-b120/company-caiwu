package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;

import java.io.Serializable;

/**
 * RPT-FN-01：CRM 调 financial 取财务报表 + 科目余额（POST /OutSystem2CRM/crmGetReport）入参。
 * <p>
 * 由 finmaster {@code FinancialUtils.crmGetReport(...)} 调用，
 * 供 86 号工单 R1 综合体检 / R6 高新评估的财务维度取数。
 * <p>
 * 契约形状见《86_AI企业体检_开发派工总报告.md》§D4。
 */
@Data
public class GetReportReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 账套 ID（必填，CRM 服务端按 openId→companyId→taxNumber→accountSetsId 推导后传入） */
    private Long accountSetsId;

    /** 期间 YYYY-MM（必填，控制器会归一到月末作为 voucherDate） */
    private String period;
}
