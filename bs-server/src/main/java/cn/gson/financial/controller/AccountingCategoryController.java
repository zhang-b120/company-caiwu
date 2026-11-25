package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.entity.AccountingCategory.AssistingAccountingColumn;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.AccountingCategoryService;
import cn.gson.financial.kernel.service.AuxiliaryDataService;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/accounting-category")
@RequiredArgsConstructor
public class AccountingCategoryController {

    private final AccountingCategoryService accountingCategoryService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(accountingCategoryService.listByAccountSetsId(accountSets.getId()));
    }

    @GetMapping("/{categoryId}")
    public JsonResult categoryList(@SessionAccountSet AccountSets accountSets, @PathVariable Long categoryId) {
        return JsonResult.successful(accountingCategoryService.getOne(new PrimaryKey(categoryId, accountSets.getId())));
    }

    @PostMapping("/byid")
    public JsonResult listByIds(@SessionAccountSet AccountSets accountSets, @RequestBody Set<Long> categoryIdSet) {
        return JsonResult.successful(accountingCategoryService.listByIdsWidthData(accountSets.getId(), categoryIdSet));
    }

    @PostMapping
    @SaCheckPermission("setting-assistingaccounting-category-canedit")
    public JsonResult save(@RequestBody AccountingCategory accountingCategory, @SessionAccountSet AccountSets accountSets) {
        accountingCategory.setAccountSetsId(accountSets.getId());
        accountingCategoryService.save(accountingCategory);
        return JsonResult.successful();
    }

    @PutMapping
    @SaCheckPermission("setting-assistingaccounting-category-canedit")
    public JsonResult update(@RequestBody AccountingCategory accountingCategory, @SessionAccountSet AccountSets accountSets) {
        accountingCategory.setAccountSetsId(accountSets.getId());
        accountingCategoryService.update(accountingCategory);
        return JsonResult.successful();
    }

    @DeleteMapping("{id:\\d+}")
    @SaCheckPermission("setting-assistingaccounting-category-candelete")
    public JsonResult delete(@PathVariable Long id, @SessionAccountSet AccountSets accountSets) {
        accountingCategoryService.delete(new PrimaryKey(id, accountSets.getId()));
        return JsonResult.successful();
    }

    @GetMapping("template")
    @SaCheckPermission("setting-assistingaccount-canimport")
    public void downTemplate(@RequestParam Long categoryId, @RequestParam AccountingCategoryType categoryType, HttpServletResponse response, @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
        AccountingCategory category = this.accountingCategoryService.getOne(new PrimaryKey(categoryId, accountSetsId));
        List<AssistingAccountingColumn> categoryColumns = category.getColumns();
        if (!AccountingCategoryType.CUSTOMIZATION.equals(categoryType)) {
            categoryColumns = categoryType.columns;
        }

        List<String> columns = StrUtil.split((category.getName() + "编码," + category.getName() + "名称," + categoryColumns.stream().map(AssistingAccountingColumn::getTitle).collect(Collectors.joining(",")) + ",备注"), ",");
        ExcelWriter writer = ExcelUtil.getWriter();
        writer.writeHeadRow(columns);

        String fileName = category.getName() + "导入模板.xls";
        response.setContentType("application/vnd.ms-excel;");
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }

    @GetMapping("download")
    @SaCheckPermission("setting-assistingaccount-canexport")
    public void downloadData(@RequestParam Long categoryId, AccountingCategoryType categoryType, HttpServletResponse response, @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
        AccountingCategory category = this.accountingCategoryService.getOne(new PrimaryKey(categoryId, accountSetsId));
        AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);

        ExcelWriter writer = ExcelUtil.getWriter();
        writer.addHeaderAlias("code", category.getName() + "编码");
        writer.addHeaderAlias("name", category.getName() + "名称");
        if (AccountingCategoryType.CUSTOMIZATION.equals(categoryType)) {
            for (int i = 0; i < category.getColumns().size(); i++) {
                writer.addHeaderAlias("cusColumn" + i, category.getColumns().get(i).getTitle());
            }
        } else {
            categoryType.columns.forEach(c -> {
                writer.addHeaderAlias(c.getCode(), c.getTitle());
            });
        }
        writer.addHeaderAlias("remark", "备注");
        writer.setOnlyAlias(true);
        List<? extends AcDetails> details = dataService.loadData(accountSetsId, categoryId);
        writer.write(details);
        String fileName = category.getName() + "导出数据" + categoryId + ".xls";
        response.setContentType("application/vnd.ms-excel;");
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }

    @PostMapping("import")
    @SaCheckPermission("setting-assistingaccount-canimport")
    public JsonResult importData(@RequestParam Long categoryId, AccountingCategoryType categoryType, @RequestParam("file") MultipartFile multipartFile, @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
        AccountingCategory category = this.accountingCategoryService.getOne(new PrimaryKey(categoryId, accountSetsId));
        if (category == null) {
            return JsonResult.failure("亲，导入类别选择错误~");
        }

        ExcelReader reader = ExcelUtil.getReader(multipartFile.getInputStream());
        reader.addHeaderAlias(category.getName() + "编码", "code");
        reader.addHeaderAlias(category.getName() + "名称", "name");
        if (AccountingCategoryType.CUSTOMIZATION.equals(categoryType)) {
            for (int i = 0; i < category.getColumns().size(); i++) {
                reader.addHeaderAlias(category.getColumns().get(i).getTitle(), "cusColumn" + i);
            }
        } else {
            categoryType.columns.forEach(c -> {
                reader.addHeaderAlias(c.getTitle(), c.getCode());
            });
        }
        reader.addHeaderAlias("备注", "remark");
        List<? extends AcDetails> rows = reader.readAll(categoryType.entity);

        Map<String, AcDetails> detailsMap = new HashMap<>();

        for (AcDetails detail : rows) {
            detail.setCategoryId(categoryId);
            detail.setCategoryType(categoryType);
            detail.setAccountSetsId(accountSetsId);
            detail.setEnable(true);

            if (StrUtil.isEmpty(detail.getCode()) || StrUtil.isEmpty(detail.getName())) {
                return JsonResult.failure(category.getName() + "编码和名称不能为空~");
            }
            detail.setMnemonics(PinYinUtil.getFirstLettersLo(detail.getName()));

            if (detailsMap.containsKey(detail.getCode())) {
                return JsonResult.failure("亲，编码[" + detail.getCode() + "]重复,请检查编码~");
            }
            detailsMap.put(detail.getCode(), detail);
        }
        AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);
        return JsonResult.successful(dataService.importData(accountSetsId, categoryId, categoryType, detailsMap));
    }
}
