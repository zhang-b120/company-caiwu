package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@ToString
public class AssetsImportVo {

    @ColName("检查状态")
    private String check;

    @ColName("资产编号")
    @NotEmpty(message = "资产编号为空")
    private String code;

    @NotEmpty(message = "资产名称为空")
    @ColName("资产名称")
    private String name;

    @NotEmpty(message = "资产类别为空")
    @ColName("资产类别")
    private String assetsType;

    @ColName("开始使用日期")
    private LocalDate startDate;

    @ColName("增加方式")
    private String addTypeVal;

    @ColName("规格型号")
    private String specification;

    @ColName("供应商")
    private String supplier;

    //使用部门
    @ColName("部门名称\n" +
             "（系统自动识别，无需填写）")
    private String dept;

    @ColName("部门编码")
    private String deptCode;

    //折旧方法
    @ColName("折旧方法")
    private String depreciationMethod;

    //资产科目
    @NotEmpty(message = "资产科目编码为空")
    @ColName("资产科目编码")
    private String assetsSubjectCode;

    //累计折旧科目
    @ColName("累计折旧科目编码")
    private String totalDepreciationSubjectCode;

    //资产清理科目
    @ColName("资产清理科目编码")
    private String assetsCleanSubjectCode;

    //折旧费用科目
    @ColName("折旧费用科目编码")
    private String depreciationExpenseSubjectCode;

    //资产原值
    @ColName("资产原值")
    private Double originalValue;

    //使用期限
    @ColName("已折旧月份")
    private Integer useMonth;

    //预计使用月份
    @ColName("预计使用月份")
    private Integer expectUseMonth;

    @ColName("残值率（%）")
    private Double residualRate;

    //累计折旧
    @ColName("累计折旧")
    private Double totalDepreciation;

    //本年累计折旧
    @ColName("本年累计折旧")
    private Double yearTotalDepreciation;

    //每月折旧额
    @ColName("每月折旧额\n" +
             "（系统自动计算，无需填写）")
    private Double currentMonthDepreciation;

    //{1:'正常',2:'停用',3:'出租'}
    @ColName("使用状况")
    private String statusVal;

    @ColName("备注")
    private String remark;

    @ColName("录入当期是否折旧")
    private Boolean isPeriod;

    private String error;

    public void setAssetsType(@NotEmpty(message = "资产类别为空") String assetsType) {
        this.assetsType = assetsType.trim().replaceFirst("'", "");
    }

    public void setResidualRate(Double residualRate) {
        if (residualRate != null && residualRate < 1) {
            this.residualRate = residualRate * 100;
        } else {
            this.residualRate = residualRate;
        }
    }
}
