package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.SalaryDto;
import cn.gson.financial.kernel.model.dto.SalaryExportDetailDto;
import cn.gson.financial.kernel.model.dto.SalaryFieldDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.vo.SalaryDetailVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.SalaryService;
import cn.gson.financial.kernel.service.SalaryVoucherTemplateService;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.poi.excel.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 *
 * <p><b>Copyright © 2010-2021 开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 工资管理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/salary")
public class SalaryController extends AbsService {

    private final SalaryService salaryService;

    private final SalaryVoucherTemplateService salaryVoucherTemplateService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QSalaryVoucherTemplate qSalaryVoucherTemplate = QSalaryVoucherTemplate.salaryVoucherTemplate;

    private final QSubject qSubject = QSubject.subject;

    @GetMapping
    @SaCheckPermission("salary-salarymanage-canview")
    public JsonResult list(@SessionAccountSetId Long accountSetsId, Page page, LocalDateRange dateRange) {
        PageResults<SalaryDto> list = salaryService.list(accountSetsId, page, dateRange);
        List<SalaryFieldDto> salaryFields = new ArrayList<>();
        for (SalaryDto result : list.getResults()) {
            for (SalaryFieldDto s : result.getSalaryFields()) {
                if (!salaryFields.contains(s) && s.getEnabled()) {
                    salaryFields.add(s);
                }
            }
            //前台不单独返回字段了
            result.setSalaryFields(null);
        }


        return JsonResult.successful()
                .data("list", list)
                .data("fields", salaryFields.stream().filter(SalaryFieldDto::getEnabled).toList());
    }

    @PostMapping
    @SaCheckPermission("salary-salarymanage-canedit")
    public JsonResult save(@SessionAccountSetId Long accountSetsId, @RequestParam LocalDate period, @RequestBody SalaryDetailVo detailVo) {
        return JsonResult.successful(salaryService.save(accountSetsId, period, detailVo,Boolean.FALSE));
    }

    /**
     * 获取最后一次工资表日期
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("last/date")
    @SaCheckPermission("salary-salarymanage-canedit")
    public JsonResult lastDate(@SessionAccountSetId Long accountSetsId) {
        return JsonResult.successful(salaryService.lastDate(accountSetsId));
    }

    @GetMapping("detail")
    @SaCheckPermission("salary-salarymanage-canview")
    public JsonResult detail(@SessionAccountSetId Long accountSetsId, Long salaryId) {
        return JsonResult.successful(salaryService.detail(accountSetsId, salaryId));
    }

    /**
     * 状态变更（复核）
     *
     * @param accountSetsId
     * @param state
     * @param salaryId
     * @return
     */
    @GetMapping("review/{salaryId}")
    @SaCheckPermission("salary-salarymanage-canreview")
    public JsonResult review(@SessionAccountSetId Long accountSetsId, Boolean state, @PathVariable Long salaryId ,@SessionUser UserVo userVo) {
        salaryService.review(accountSetsId, state, salaryId,Boolean.FALSE);
        return JsonResult.successful();
    }

    /**
     * 删除工资表
     *
     * @param accountSetsId
     * @param salaryId
     * @return
     */
    @DeleteMapping("{salaryId}")
    @SaCheckPermission("salary-salarymanage-candelete")
    public JsonResult delete(@SessionAccountSetId Long accountSetsId, @PathVariable Long salaryId) {
        salaryService.delete(accountSetsId, salaryId,Boolean.FALSE);
        return JsonResult.successful();
    }

    /**
     * 打印
     *
     * @param accountSets
     * @param dateRange
     * @return
     */
    @GetMapping("print")
    @SaCheckPermission("salary-salarymanage-canprint")
    public void print(@SessionAccountSet AccountSets accountSets, LocalDateRange dateRange, HttpServletResponse response) throws Exception {
        List<SalaryExportDetailDto> detailDtos = salaryService.exportDetail(accountSets.getId(), dateRange);

        List<String> sheetNames = detailDtos.stream().map(s -> s.getPeriod().format(DateTimeFormatter.ofPattern("yyyy年MM月"))).toList();
        Dict params = Dict.create();
        params.set("sheetNames", sheetNames);
        params.set("details", detailDtos);
        params.set("companyName", accountSets.getCompanyName());

        JxlsUtils.exportExcel("template/salary-export.xlsx", response.getOutputStream(), params);
    }

    /**
     * 导出
     *
     * @param accountSets
     * @param dateRange
     * @return
     */
    @GetMapping("download")
    @SaCheckPermission("salary-salarymanage-candownload")
    public void download(@SessionAccountSet AccountSets accountSets, LocalDateRange dateRange, HttpServletResponse response) throws Exception {
        List<SalaryExportDetailDto> detailDtos = salaryService.exportDetail(accountSets.getId(), dateRange);
        List<String> sheetNames = detailDtos.stream().map(s -> s.getPeriod().format(DateTimeFormatter.ofPattern("yyyy年MM月"))).toList();
        Dict params = Dict.create();
        params.set("sheetNames", sheetNames);
        params.set("details", detailDtos);
        params.set("companyName", accountSets.getCompanyName());
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        String filename = accountSets.getCompanyName() + dateRange.title("yyyy年MM月") + "薪资表.xlsx";
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        JxlsUtils.exportExcel("template/salary-export.xlsx", response.getOutputStream(), params);
    }

    /**
     * 查询账套列表（用于批量复制工资，支持筛选有工资/无工资的账套）
     *
     * @param userVo 当前用户
     * @param period 账期（格式：yyyy-MM）
     * @param hasSalary 是否有工资（true:有工资, false:无工资, null:全部）
     * @return 账套列表及统计信息
     */
    @GetMapping("batch-copy/account-sets")
    @SaCheckPermission("salary-salarymanage-canedit")
    public JsonResult getAccountSetsForBatchCopy(@SessionUser UserVo userVo,
                                                  @RequestParam(required = false) String period,
                                                  @RequestParam(required = false) Boolean hasSalary) {
        return JsonResult.successful(salaryService.getAccountSetsForBatchCopy(userVo.getId(), period, hasSalary));
    }

    /**
     * 批量复制上月工资
     *
     * @param userVo 当前用户
     * @param accountSetsIds 账套ID列表
     * @param period 目标账期（格式：yyyy-MM）
     * @return 批量复制结果
     */
    @PostMapping("batch-copy")
    @SaCheckPermission("salary-salarymanage-canedit")
    public JsonResult batchCopyLastMonthSalary(@SessionUser UserVo userVo,
                                               @RequestBody List<Long> accountSetsIds,
                                               @RequestParam String period) {
        return JsonResult.successful(salaryService.batchCopyLastMonthSalary(userVo.getId(), accountSetsIds, period));
    }

    /**
     * 批量修改工资模板
     * 遍历公司中的总账套，将模板账套中的"计提发放工资"模板复制到各个账套
     *
     * @return
     */
    @SaIgnore
    @GetMapping("/template/textChange")
    public JsonResult textChange() {
        //遍历公司中的总账套，将其中的计提发放工资这个模板摘出来
        List<AccountSets> accountSetsList = jqf.selectFrom(qAccountSets).fetch();
        //查询账套模板中的计提发放工资
        //要分小规模 432597845032308737还是一般纳税人 432597845602734082

        //从工资凭证模板中查询小规模或者一般纳税人的计提发放工资的配置
        List<SalaryVoucherTemplate> salaryTemplateListxiao = jqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(432597845032308737L)
                        .and(qSalaryVoucherTemplate.title.eq("计提发放工资")))
                .fetch();
        List<SalaryVoucherTemplate> salaryTemplateListyiban = jqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(432597845602734082L)
                        .and(qSalaryVoucherTemplate.title.eq("计提发放工资")))
                .fetch();

        //遍历公司中的总账套,将查询出来的模板配置复制到公司中的总账套，给每个账套都新增这个模板
        for (AccountSets accountSets : accountSetsList) {
            // 跳过模板账套本身
            if (accountSets.getId().equals(432597845032308737L) || accountSets.getId().equals(432597845602734082L)) {
                continue;
            }

            List<SalaryVoucherTemplate> templatesToCopy = List.of();
            if ("小规模纳税人".equals(accountSets.getVatType().display)) {
                //小规模
                templatesToCopy = salaryTemplateListxiao;
            } else if ("一般纳税人".equals(accountSets.getVatType().display)) {
                //一般纳税人
                templatesToCopy = salaryTemplateListyiban;
            }
            // 如果模板列表为空，跳过
            if (CollUtil.isEmpty(templatesToCopy)) {
                continue;
            }

            //为每个模板创建副本并保存到当前账套
            for (SalaryVoucherTemplate template : templatesToCopy) {
                // 检查该账套是否已存在相同名称的模板
                SalaryVoucherTemplate existing = bqf.selectFrom(qSalaryVoucherTemplate)
                        .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSets.getId())
                                .and(qSalaryVoucherTemplate.title.eq(template.getTitle())))
                        .fetchFirst();

                if (existing != null) {
                    // 模板已存在，只更新items字段，不改其他字段
                    // 从源模板复制items，然后转换科目ID到当前账套
                    bqf.detach(template);

                    // 直接使用源模板的items进行深拷贝，确保所有字段都被复制
                    List<SalaryVoucherTemplate.VoucherItems> copiedItems = new ArrayList<>();
                    if (CollUtil.isNotEmpty(template.getItems())) {
                        copiedItems = template.getItems().stream()
                                .map(item -> {
                                    // 使用BeanUtil.copyProperties确保所有字段都被复制，包括direction
                                    SalaryVoucherTemplate.VoucherItems newItem = new SalaryVoucherTemplate.VoucherItems();
                                    BeanUtil.copyProperties(item, newItem);
                                    return newItem;
                                })
                                .collect(Collectors.toList());
                    }

                    // 创建临时模板用于转换
                    SalaryVoucherTemplate tempTemplate = new SalaryVoucherTemplate();
                    tempTemplate.setItems(copiedItems);

                    // 确保使用源模板的原始items（包含源账套的科目ID）
                    // 然后转换到当前循环中的目标账套
                    Long sourceAccountSetsId = template.getAccountSetsId(); // 源账套ID（模板账套）
                    Long targetAccountSetsId = accountSets.getId(); // 目标账套ID（当前循环的账套）

                    // 转换科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目全名fullName匹配）
                    convertSubjectIds(tempTemplate, sourceAccountSetsId, targetAccountSetsId);

                    // 只更新items字段，使用转换后的items（科目ID已经是目标账套的了）
                    existing.setItems(tempTemplate.getItems());
                    salaryVoucherTemplateService.save(existing);
                    continue;
                }

                bqf.detach(template);
                SalaryVoucherTemplate newTemplate = BeanUtil.toBean(template, SalaryVoucherTemplate.class);
                newTemplate.setId(null); // 清除ID，让系统生成新ID
                newTemplate.setAccountSetsId(accountSets.getId());
                newTemplate.setDef(false); // 设置为非默认模板

                // 转换科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目完整路径匹配）
                Long sourceAccountSetsId = template.getAccountSetsId();
                Long targetAccountSetsId = accountSets.getId();
                convertSubjectIds(newTemplate, sourceAccountSetsId, targetAccountSetsId);

                salaryVoucherTemplateService.save(newTemplate);
            }
        }
        return JsonResult.successful();
    }

    /**
     * 转换模板中的科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目全名fullName匹配）
     *
     * @param template 模板对象
     * @param sourceAccountSetsId 源账套ID
     * @param targetAccountSetsId 目标账套ID
     */
    private void convertSubjectIds(SalaryVoucherTemplate template, Long sourceAccountSetsId, Long targetAccountSetsId) {
        if (CollUtil.isEmpty(template.getItems())) {
            return;
        }

        // 收集所有需要转换的科目ID（items中的subjectId是String类型）
        Set<String> sourceSubjectIdStrings = template.getItems().stream()
                .filter(item -> item.getSubjectId() != null && !item.getSubjectId().isEmpty())
                .map(SalaryVoucherTemplate.VoucherItems::getSubjectId)
                .collect(Collectors.toSet());

        if (CollUtil.isEmpty(sourceSubjectIdStrings)) {
            return;
        }

        // 将String类型的subjectId转换为Long类型进行查询
        Set<Long> sourceSubjectIds = sourceSubjectIdStrings.stream()
                .filter(id -> {
                    try {
                        Long.parseLong(id);
                        return true;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .map(Long::parseLong)
                .collect(Collectors.toSet());

        if (CollUtil.isEmpty(sourceSubjectIds)) {
            return;
        }

        // 查询源账套的科目，建立ID到全名（fullName）的映射
        Map<Long, String> sourceSubjectIdToFullNameMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(sourceAccountSetsId)
                        .and(qSubject.id.in(sourceSubjectIds)))
                .fetch()
                .stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getFullName));

        // 获取所有全名
        Set<String> fullNames = new HashSet<>(sourceSubjectIdToFullNameMap.values());

        if (CollUtil.isEmpty(fullNames)) {
            return;
        }

        // 查询目标账套的科目，建立全名（fullName）到科目ID的映射
        Map<String, Long> targetFullNameToSubjectIdMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(targetAccountSetsId)
                        .and(qSubject.fullName.in(fullNames)))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        Subject::getFullName,
                        Subject::getId,
                        (existing, replacement) -> existing // 如果有重名，保留第一个
                ));

        // 转换items中的subjectId和subjectTitle
        template.getItems().forEach(item -> {
            if (item.getSubjectId() != null && !item.getSubjectId().isEmpty()) {
                try {
                    Long sourceSubjectId = Long.parseLong(item.getSubjectId());
                    String fullName = sourceSubjectIdToFullNameMap.get(sourceSubjectId);
                    if (fullName != null) {
                        Long targetSubjectId = targetFullNameToSubjectIdMap.get(fullName);
                        if (targetSubjectId != null) {
                            // 找到匹配的科目，更新subjectId和subjectTitle
                            item.setSubjectId(targetSubjectId.toString());
                            // 同时更新subjectTitle字段，用于前端显示（使用fullName）
                            item.setSubjectTitle(fullName);
                        } else {
                            // 目标账套中找不到匹配的科目，清空subjectId，避免使用错误的ID
                            // setItems方法会自动过滤掉subjectId为空的item
                            item.setSubjectId(null);
                            item.setSubjectTitle(null);
                        }
                    } else {
                        // 源账套中找不到对应的科目，清空subjectId
                        item.setSubjectId(null);
                        item.setSubjectTitle(null);
                    }
                } catch (NumberFormatException e) {
                    // 如果subjectId不是数字，清空它
                    item.setSubjectId(null);
                    item.setSubjectTitle(null);
                }
            }
        });
    }


}

