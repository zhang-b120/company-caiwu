package cn.gson.financial.kernel.model.yingDao.dto;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-09-02
 * @Description
 */
@Data
public class ExportEmployeeSalaryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = -3477999576647073745L;

    @ExcelProperty("工号")
    private String code;

    @ExcelProperty("*姓名")
    private String name;

    @ExcelProperty("*证件类型")
    private String idCardType;

    @ExcelProperty("*证件号码")
    private String idCard;

    @ExcelProperty("本期收入")
    private String income;

    @ExcelProperty("本期免税收入")
    private String taxExemptIncome;

    @ExcelProperty("基本养老保险费")
    private String endowmentInsurance;

    @ExcelProperty("基本医疗保险费")
    private String medicalInsurance;

    @ExcelProperty("失业保险费")
    private String unemploymentInsurance;

    @ExcelProperty("住房公积金")
    private String housingProvidentFund;

    @ExcelProperty("累计子女教育")
    private String childrenEducation;

    @ExcelProperty("累计继续教育")
    private String continuingEducation;

    @ExcelProperty("累计住房贷款利息")
    private String mortgageInterest;

    @ExcelProperty("累计住房租金")
    private String rent;

    @ExcelProperty("累计赡养老人")
    private String supportElderly;

    @ExcelProperty("累计3岁以下婴幼儿照护")
    private String childCare;

    @ExcelProperty("累计个人养老金")
    private String privatePension;

    @ExcelProperty("企业(职业)年金")
    private String annuity;

    @ExcelProperty("商业健康保险")
    private String healthInsurance;

    @ExcelProperty("税延养老保险")
    private String taxDeferredPensionInsurance;
  //  公务交通费用	通讯费用	律师办案费用	西藏附加减除费用
    @ExcelProperty("公务交通费用")
    private String businessTrafficExpenses;
    @ExcelProperty("通讯费用")
    private String communicationExpenses;
    @ExcelProperty("律师办案费用")
    private String lawyerExpenses;
    @ExcelProperty("西藏附加减除费用")
    private String tibetAdditionalDeduction;
    @ExcelProperty("其他")
    private String other;

    @ExcelProperty("准予扣除的捐赠额")
    private String donationAmount;

    @ExcelProperty("减免税额")
    private String taxSavings;

    @ExcelProperty("协定减免")
    private String treatyReduction;

    @ExcelProperty("备注")
    private String remark;
}
