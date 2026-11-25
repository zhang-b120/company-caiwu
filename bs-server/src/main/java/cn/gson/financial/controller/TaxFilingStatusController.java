package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.TaxFilingAnnual;
import cn.gson.financial.kernel.model.entity.TaxFilingInfo;
import cn.gson.financial.kernel.model.entity.TaxFilingStatus;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.TaxFilingStatusService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 报税情况控制器
 *
 * <p>三张表：tax_filing_status(账期)、tax_filing_info(账套)、tax_filing_annual(账套+年份)</p>
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/tax-filing-status")
public class TaxFilingStatusController {

    private final TaxFilingStatusService taxFilingStatusService;

    /**
     * 分页列表查询。除"国税申报/个税申报/缴费"3 列由任务工作台状态决定外，其余表头均支持作为查询条件。
     */
    @GetMapping
    public JsonResult list(ListQuery q, @SessionUser UserVo userVo) {
        Page p = new Page().setPage(q.getPage()).setPageSize(q.getPageSize());
        Map<String, Object> filters = new HashMap<>();
        filters.put("taxPeriod", q.getTaxPeriod());
        filters.put("companyName", q.getCompanyName());
        filters.put("creditCode", q.getCreditCode());
        filters.put("individualTaxCode", q.getIndividualTaxCode());
        filters.put("companyOrgType", q.getCompanyOrgType());
        filters.put("vatType", q.getVatType());
        filters.put("province", q.getProvince());
        filters.put("creatorKeyword", q.getCreatorKeyword());
        filters.put("taxHandlingOldAccount", q.getTaxHandlingOldAccount());
        filters.put("nature", q.getNature());
        filters.put("salaryWagesEnabled", q.getSalaryWagesEnabled());
        filters.put("businessIncomeEnabled", q.getBusinessIncomeEnabled());
        filters.put("salaryWages", q.getSalaryWages());
        filters.put("salaryWagesTax", q.getSalaryWagesTax());
        filters.put("businessIncome", q.getBusinessIncome());
        filters.put("businessIncomeTax", q.getBusinessIncomeTax());
        filters.put("stateTax", q.getStateTax());
        filters.put("stateTaxAmount", q.getStateTaxAmount());
        filters.put("enterpriseAnnualReport", q.getEnterpriseAnnualReport());
        filters.put("industryCommercialAnnualReport", q.getIndustryCommercialAnnualReport());
        filters.put("annualRemark", q.getAnnualRemark());
        filters.put("remark",  q.getRemark());
        filters.put("remark1", q.getRemark1());
        filters.put("remark2", q.getRemark2());
        filters.put("remark3", q.getRemark3());
        filters.put("remark4", q.getRemark4());
        // 创建时间范围：DATE → DATETIME（开始日 00:00:00 / 结束日 23:59:59）
        if (q.getAccountSetCreateDateStart() != null) {
            filters.put("accountSetCreateDateStart",
                    q.getAccountSetCreateDateStart().atStartOfDay());
        }
        if (q.getAccountSetCreateDateEnd() != null) {
            filters.put("accountSetCreateDateEnd",
                    LocalDateTime.of(q.getAccountSetCreateDateEnd(), LocalTime.MAX));
        }
        return JsonResult.successful(taxFilingStatusService.listPage(userVo.getId(), filters, p));
    }

    /** 国税申报税目明细 */
    @GetMapping("/tax-detail")
    public JsonResult taxDetail(@RequestParam Long accountSetId,
                                @RequestParam String taxPeriod) {
        return JsonResult.successful(taxFilingStatusService.taxDetail(accountSetId, taxPeriod));
    }

    /** 个税申报子任务明细 */
    @GetMapping("/iit-detail")
    public JsonResult iitDetail(@RequestParam Long accountSetId,
                                @RequestParam String taxPeriod) {
        return JsonResult.successful(taxFilingStatusService.iitDetail(accountSetId, taxPeriod));
    }

    /** 一次保存 报税情况(账期) + 报税信息(账套) + 年报(账套+年份) */
    @PostMapping
    public JsonResult save(@RequestBody SaveAllRequest body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        if (body.getAccountSetId() == null) throw new IllegalArgumentException("账套ID不能为空");
        if (body.getTaxPeriod() == null || body.getTaxPeriod().isBlank())
            throw new IllegalArgumentException("报税账期不能为空");
        return JsonResult.successful(
                taxFilingStatusService.saveAll(body.toStatus(), body.toInfo(), body.toAnnual()));
    }

    /** 更新报税信息上的"工资薪金/经营所得"是否办理开关（账套维度） */
    @PostMapping("/account-set-flag")
    public JsonResult updateInfoFlag(@RequestBody InfoFlagRequest body) {
        taxFilingStatusService.updateInfoFlag(
                body.getAccountSetId(),
                body.getSalaryWagesEnabled(),
                body.getBusinessIncomeEnabled());
        return JsonResult.successful();
    }

    @DeleteMapping("{id:\\d+}")
    public JsonResult delete(@PathVariable Long id) {
        taxFilingStatusService.delete(id);
        return JsonResult.successful();
    }

    /** 列表查询参数（Spring MVC 会按字段名从 query string 绑定） */
    @Data
    public static class ListQuery {
        private Integer page = 1;
        private Integer pageSize = 20;
        // 必填
        private String taxPeriod;
        // 账套维度
        private String companyName;
        private String creditCode;
        private String individualTaxCode;
        /** 企业类型/组织形式（账套表 company_org_type，模糊匹配） */
        private String companyOrgType;
        /** 增值税类型（账套表 vat_type：SMALL_SCALE 小规模 / COMMONLY 一般纳税人，精确匹配） */
        private String vatType;
        private String province;
        private String creatorKeyword;
        private LocalDate accountSetCreateDateStart;
        private LocalDate accountSetCreateDateEnd;
        // tfi
        private String taxHandlingOldAccount;
        private String nature;
        private Boolean salaryWagesEnabled;
        private Boolean businessIncomeEnabled;
        // 备注 5 列分别查询：多值用 , 分隔；空 token 表示查询空值
        private String remark;
        private String remark1;
        private String remark2;
        private String remark3;
        private String remark4;
        // tfs
        private String salaryWages;
        private String salaryWagesTax;
        private String businessIncome;
        private String businessIncomeTax;
        private String stateTax;
        private String stateTaxAmount;
        // tfa
        private String enterpriseAnnualReport;
        private String industryCommercialAnnualReport;
        private String annualRemark;
    }

    /** 合并请求体：覆盖 3 张表的所有字段 */
    @Data
    public static class SaveAllRequest {
        // 公共
        private Long accountSetId;
        private String taxPeriod;

        // === 账期维度：tax_filing_status ===
        private Long id;
        private String salaryWages;
        private String salaryWagesTax;
        private String businessIncome;
        private String businessIncomeTax;
        private String stateTax;
        private String stateTaxAmount;

        // === 账套维度：tax_filing_info ===
        private Long infoId;
        private Boolean salaryWagesEnabled;
        private Boolean businessIncomeEnabled;
        private String taxHandlingOldAccount;
        private String nature;
        private String remark;
        private String remark1;
        private String remark2;
        private String remark3;
        private String remark4;

        // === 年报：tax_filing_annual ===
        private Long annualId;
        private String enterpriseAnnualReport;
        private String industryCommercialAnnualReport;
        private String annualRemark;

        public TaxFilingStatus toStatus() {
            TaxFilingStatus s = new TaxFilingStatus();
            s.setId(id);
            s.setAccountSetId(accountSetId);
            s.setTaxPeriod(taxPeriod);
            s.setSalaryWages(salaryWages);
            s.setSalaryWagesTax(salaryWagesTax);
            s.setBusinessIncome(businessIncome);
            s.setBusinessIncomeTax(businessIncomeTax);
            s.setStateTax(stateTax);
            s.setStateTaxAmount(stateTaxAmount);
            return s;
        }

        public TaxFilingInfo toInfo() {
            TaxFilingInfo i = new TaxFilingInfo();
            i.setId(infoId);
            i.setAccountSetId(accountSetId);
            i.setSalaryWagesEnabled(salaryWagesEnabled);
            i.setBusinessIncomeEnabled(businessIncomeEnabled);
            i.setTaxHandlingOldAccount(taxHandlingOldAccount);
            i.setNature(nature);
            i.setRemark(remark);
            i.setRemark1(remark1);
            i.setRemark2(remark2);
            i.setRemark3(remark3);
            i.setRemark4(remark4);
            return i;
        }

        public TaxFilingAnnual toAnnual() {
            TaxFilingAnnual a = new TaxFilingAnnual();
            a.setId(annualId);
            a.setAccountSetId(accountSetId);
            a.setEnterpriseAnnualReport(enterpriseAnnualReport);
            a.setIndustryCommercialAnnualReport(industryCommercialAnnualReport);
            a.setAnnualRemark(annualRemark);
            return a;
        }
    }

    @Data
    public static class InfoFlagRequest {
        private Long accountSetId;
        private Boolean salaryWagesEnabled;
        private Boolean businessIncomeEnabled;
    }
}
