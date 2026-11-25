package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.entity.TaxFilingAnnual;
import cn.gson.financial.kernel.model.entity.TaxFilingInfo;
import cn.gson.financial.kernel.model.entity.TaxFilingStatus;
import cn.gson.financial.kernel.model.repo.TaxFilingAnnualRepository;
import cn.gson.financial.kernel.model.repo.TaxFilingInfoRepository;
import cn.gson.financial.kernel.model.repo.TaxFilingStatusRepository;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报税情况服务
 *
 * <p>列表多表 JOIN，服务端分页。
 * 多值/模糊/空值查询：
 * <ul>
 *   <li>5 个状态字段（工资薪金/经营所得/国税/企业年报/工商年报）：枚举值，IN 精确多选 + IFNULL 兼容空值</li>
 *   <li>9 个自由文本字段（3 税款 + 年报备注 + 备注/备注1-4）：REGEXP 多值+模糊，空 token 转为 "^$" 匹配空值</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class TaxFilingStatusService extends AbsService {

    private final TaxFilingStatusRepository taxFilingStatusRepository;
    private final TaxFilingAnnualRepository taxFilingAnnualRepository;
    private final TaxFilingInfoRepository taxFilingInfoRepository;

    /** 分页查询列表（仅当前登录人作为账套创建者的账套） */
    public PageResults<Map<String, Object>> listPage(Long currentUserId,
                                                     Map<String, Object> filters,
                                                     Page page) {
        Assert.notNull(currentUserId, "当前用户 ID 不能为空");
        String taxPeriod = filters == null ? null : (String) filters.get("taxPeriod");
        String period = StrUtil.isBlank(taxPeriod)
                ? LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                : taxPeriod;
        Integer taxYear = Integer.valueOf(period.substring(0, 4));

        Map<String, Object> params = new HashMap<>();
        if (filters != null) params.putAll(filters);
        params.put("currentUserId", currentUserId);
        params.put("taxPeriod", period);
        params.put("taxYear", taxYear);
        // 空字符串转 null，使 Sqltoy 的 #[...] 自动跳过
        params.replaceAll((k, v) -> (v instanceof String && ((String) v).isBlank()) ? null : v);

        // 14 个文本字段：多值模糊 + 空值 → 一个 MySQL REGEXP 字符串
        params.put("salaryWagesRegex",                     parseToRegex((String) params.get("salaryWages")));
        params.put("salaryWagesTaxRegex",                  parseToRegex((String) params.get("salaryWagesTax")));
        params.put("businessIncomeRegex",                  parseToRegex((String) params.get("businessIncome")));
        params.put("businessIncomeTaxRegex",               parseToRegex((String) params.get("businessIncomeTax")));
        params.put("stateTaxRegex",                        parseToRegex((String) params.get("stateTax")));
        params.put("stateTaxAmountRegex",                  parseToRegex((String) params.get("stateTaxAmount")));
        params.put("enterpriseAnnualReportRegex",          parseToRegex((String) params.get("enterpriseAnnualReport")));
        params.put("industryCommercialAnnualReportRegex",  parseToRegex((String) params.get("industryCommercialAnnualReport")));
        params.put("annualRemarkRegex",                    parseToRegex((String) params.get("annualRemark")));
        params.put("remarkRegex",                          parseToRegex((String) params.get("remark")));
        params.put("remark1Regex",                         parseToRegex((String) params.get("remark1")));
        params.put("remark2Regex",                         parseToRegex((String) params.get("remark2")));
        params.put("remark3Regex",                         parseToRegex((String) params.get("remark3")));
        params.put("remark4Regex",                         parseToRegex((String) params.get("remark4")));

        @SuppressWarnings({"unchecked", "rawtypes"})
        org.sagacity.sqltoy.model.Page<Map<String, Object>> sqltoyPage =
                new org.sagacity.sqltoy.model.Page(page.getPageSize(), page.getPage());
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.sagacity.sqltoy.model.Page<Map<String, Object>> result =
                (org.sagacity.sqltoy.model.Page) lazyDao.findPageBySql(sqltoyPage,
                        "tax_filing_status_list", params, Map.class);
        return new PageResults<>(result.getRows(), page, result.getRecordCount());
    }

    /**
     * 把用户输入的逗号分隔字符串拆分为去重后的列表；空 token 保留为 ""（用于匹配空值）。
     * 整体为空时返回 null，让 Sqltoy 的 #[...] 自动跳过。
     */
    private List<String> parseToList(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        List<String> list = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        return list.isEmpty() ? null : list;
    }

    /**
     * 把用户输入拆分后构造为单一 MySQL REGEXP 字符串：
     * <ul>
     *   <li>每个非空 token 经过转义后作为子表达式（部分匹配，相当于 LIKE %t%）</li>
     *   <li>空 token 转为 "^$"，与 IFNULL(field,'') 配合可匹配 NULL 或空字符串</li>
     *   <li>所有子表达式用 | 连接</li>
     * </ul>
     */
    private String parseToRegex(String raw) {
        List<String> tokens = parseToList(raw);
        if (tokens == null) return null;
        return tokens.stream()
                .map(t -> t.isEmpty() ? "^$" : escapeMySqlRegex(t))
                .collect(Collectors.joining("|"));
    }

    /** 转义 MySQL REGEXP 元字符，确保用户输入按字面量匹配。 */
    private String escapeMySqlRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\.^$|*+?()[]{}".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** 国税申报税目明细 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> taxDetail(Long accountSetId, String taxPeriod) {
        Assert.notNull(accountSetId, "账套ID不能为空");
        Assert.notBlank(taxPeriod, "报税账期不能为空");
        Map<String, Object> params = new HashMap<>();
        params.put("accountSetId", accountSetId);
        params.put("taxPeriod", taxPeriod);
        return (List) lazyDao.findBySql("tax_filing_status_tax_detail", params, Map.class);
    }

    /** 个税申报子任务明细 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> iitDetail(Long accountSetId, String taxPeriod) {
        Assert.notNull(accountSetId, "账套ID不能为空");
        Assert.notBlank(taxPeriod, "报税账期不能为空");
        Map<String, Object> params = new HashMap<>();
        params.put("accountSetId", accountSetId);
        params.put("taxPeriod", taxPeriod);
        return (List) lazyDao.findBySql("tax_filing_status_iit_detail", params, Map.class);
    }

    /** 保存账期维度的报税情况（按 账套+账期 upsert） */
    @Transactional
    public TaxFilingStatus saveStatus(TaxFilingStatus entity) {
        Assert.notNull(entity.getAccountSetId(), "账套ID不能为空");
        Assert.notBlank(entity.getTaxPeriod(), "报税账期不能为空");
        Assert.isTrue(entity.getTaxPeriod().matches("^\\d{4}-\\d{2}$"), "报税账期格式必须为 YYYY-MM");
        if (entity.getId() == null) {
            taxFilingStatusRepository.findByAccountSetIdAndTaxPeriod(entity.getAccountSetId(), entity.getTaxPeriod())
                    .ifPresent(exist -> entity.setId(exist.getId()));
        }
        return taxFilingStatusRepository.save(entity);
    }

    /** 保存账套维度的报税信息（按 账套 upsert） */
    @Transactional
    public TaxFilingInfo saveInfo(TaxFilingInfo info) {
        Assert.notNull(info.getAccountSetId(), "账套ID不能为空");
        TaxFilingInfo target = taxFilingInfoRepository.findByAccountSetId(info.getAccountSetId())
                .orElseGet(TaxFilingInfo::new);
        target.setAccountSetId(info.getAccountSetId());
        if (info.getSalaryWagesEnabled() != null) target.setSalaryWagesEnabled(info.getSalaryWagesEnabled());
        if (info.getBusinessIncomeEnabled() != null) target.setBusinessIncomeEnabled(info.getBusinessIncomeEnabled());
        if (info.getTaxHandlingOldAccount() != null) target.setTaxHandlingOldAccount(info.getTaxHandlingOldAccount());
        if (info.getNature() != null) target.setNature(info.getNature());
        if (info.getRemark() != null) target.setRemark(info.getRemark());
        if (info.getRemark1() != null) target.setRemark1(info.getRemark1());
        if (info.getRemark2() != null) target.setRemark2(info.getRemark2());
        if (info.getRemark3() != null) target.setRemark3(info.getRemark3());
        if (info.getRemark4() != null) target.setRemark4(info.getRemark4());
        if (target.getSalaryWagesEnabled() == null) target.setSalaryWagesEnabled(false);
        if (target.getBusinessIncomeEnabled() == null) target.setBusinessIncomeEnabled(false);
        return taxFilingInfoRepository.save(target);
    }

    /** 保存年报（按 账套+年份 upsert） */
    @Transactional
    public TaxFilingAnnual saveAnnual(TaxFilingAnnual annual) {
        Assert.notNull(annual.getAccountSetId(), "账套ID不能为空");
        Assert.notNull(annual.getTaxYear(), "报税年份不能为空");
        if (annual.getId() == null) {
            taxFilingAnnualRepository.findByAccountSetIdAndTaxYear(annual.getAccountSetId(), annual.getTaxYear())
                    .ifPresent(exist -> annual.setId(exist.getId()));
        }
        return taxFilingAnnualRepository.save(annual);
    }

    /** 一次保存 三张表：报税情况(账期) + 报税信息(账套) + 年报(账套+年份) */
    @Transactional
    public Map<String, Object> saveAll(TaxFilingStatus status, TaxFilingInfo info, TaxFilingAnnual annual) {
        TaxFilingStatus savedStatus = saveStatus(status);

        TaxFilingInfo savedInfo = null;
        if (info != null && info.getAccountSetId() != null) {
            savedInfo = saveInfo(info);
        }

        TaxFilingAnnual savedAnnual = null;
        boolean annualHasContent = annual != null && (
                StrUtil.isNotBlank(annual.getEnterpriseAnnualReport())
                || StrUtil.isNotBlank(annual.getIndustryCommercialAnnualReport())
                || StrUtil.isNotBlank(annual.getAnnualRemark())
                || annual.getId() != null);
        if (annualHasContent) {
            annual.setAccountSetId(status.getAccountSetId());
            if (annual.getTaxYear() == null) {
                annual.setTaxYear(Integer.valueOf(status.getTaxPeriod().substring(0, 4)));
            }
            savedAnnual = saveAnnual(annual);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", savedStatus);
        result.put("info", savedInfo);
        result.put("annual", savedAnnual);
        return result;
    }

    /** 更新报税信息上的"工资薪金/经营所得"办理开关 */
    @Transactional
    public void updateInfoFlag(Long accountSetId, Boolean salaryWagesEnabled, Boolean businessIncomeEnabled) {
        Assert.notNull(accountSetId, "账套ID不能为空");
        TaxFilingInfo info = new TaxFilingInfo();
        info.setAccountSetId(accountSetId);
        info.setSalaryWagesEnabled(salaryWagesEnabled);
        info.setBusinessIncomeEnabled(businessIncomeEnabled);
        saveInfo(info);
    }

    @Transactional
    public void delete(Long id) {
        taxFilingStatusRepository.deleteById(id);
    }
}
