package cn.gson.financial.kernel.model.enums;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.entity.AccountingCategory.AssistingAccountingColumn;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.collection.CollUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 默认辅助核算</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum AccountingCategoryType {
    CUSTOMER("客户", Customer.class, CustomerService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("category", "客户类别"),
            new AssistingAccountingColumn("creditCode", "统一社会信用代码"),
            new AssistingAccountingColumn("address", "经营地址"),
            new AssistingAccountingColumn("contact", "联系人"),
            new AssistingAccountingColumn("telPhone", "手机")
    )),
    VENDOR("供应商", Vendor.class, VendorService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("category", "供应商类别"),
            new AssistingAccountingColumn("creditCode", "统一社会信用代码"),
            new AssistingAccountingColumn("address", "经营地址"),
            new AssistingAccountingColumn("contact", "联系人"),
            new AssistingAccountingColumn("telPhone", "手机")
    )),
    EMPLOYEE("职员", Employee.class, EmployeeService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("gender", "性别"),
            new AssistingAccountingColumn("departmentName", "部门"),
            new AssistingAccountingColumn("post", "职务"),
            new AssistingAccountingColumn("job", "岗位"),
            new AssistingAccountingColumn("telPhone", "手机"),
            new AssistingAccountingColumn("birthDate", "出生日期"),
            new AssistingAccountingColumn("onboardingDate", "入职日期"),
            new AssistingAccountingColumn("dimissionDate", "离职日期")
    )),
    DEPARTMENT("部门", Department.class, DepartmentService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("leader", "负责人"),
            new AssistingAccountingColumn("telPhone", "手机"),
            new AssistingAccountingColumn("startDate", "成立日期"),
            new AssistingAccountingColumn("endDate", "撤销日期")
    )),
    ORGANIZATION("组织机构", Organization.class, OrganizationService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("address", "经营地址")
    )),
    PROJECT("项目", Project.class, ProjectService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("departmentName", "负责部门"),
            new AssistingAccountingColumn("leader", "负责人"),
            new AssistingAccountingColumn("telPhone", "手机"),
            new AssistingAccountingColumn("startDate", "开始日期"),
            new AssistingAccountingColumn("acceptanceDate", "验收日期")
    )),
    STOCKS("存货", Stocks.class, StocksService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("category", "存货类别"),
            new AssistingAccountingColumn("specification", "规格型号"),
            new AssistingAccountingColumn("unit", "计量单位"),
            new AssistingAccountingColumn("startDate", "启用日期"),
            new AssistingAccountingColumn("endDate", "停用日期")
    )),
    CASH_FLOW("现金流", CashFlow.class, CashFlowService.class, CollUtil.newArrayList(
            new AssistingAccountingColumn("category", "现金流类别")
    )),
    CUSTOMIZATION("自定义", AccountingCategoryDetails.class, AccountingCategoryDetailsService.class, new ArrayList<>());

    public final String display;

    public final List<AssistingAccountingColumn> columns;

    public final Class<? extends AcDetails> entity;

    public final Class<? extends AuxiliaryDataService> dataService;

    AccountingCategoryType(String display, Class<? extends AcDetails> entity, Class<? extends AuxiliaryDataService> dataService, List<AssistingAccountingColumn> columns) {
        this.display = display;
        this.entity = entity;
        this.dataService = dataService;
        this.columns = columns;
    }

    public static AccountingCategoryType conv(String display) {
        return Arrays.stream(AccountingCategoryType.values()).filter(s -> s.display.equals(display)).findFirst().orElse(null);
    }
}
