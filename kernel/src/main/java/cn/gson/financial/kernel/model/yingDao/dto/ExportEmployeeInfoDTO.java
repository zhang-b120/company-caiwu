package cn.gson.financial.kernel.model.yingDao.dto;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-09-02
 * @Description
 */
@Data
public class ExportEmployeeInfoDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 7401935103217479643L;

    @ExcelIgnore
    private Long id;

    @ExcelProperty("工号")
    private String code;

    @ExcelProperty("*姓名")
    private String name;

    @ExcelProperty("*证件类型")
    private String idCardType;

    @ExcelProperty("*证件号码")
    private String idCard;

    @ExcelProperty("*国籍(地区)")
    private String country;

    @ExcelProperty("*性别")
    private String sex;

    @ExcelProperty("*出生日期")
    private LocalDate birthDate;

    @ExcelProperty("是否高级专家")
    private String isSeniorExpert;


    @ExcelProperty("*任职受雇从业类型")
    private String onboardingType;

    @ExcelProperty("其他情况说明")
    private String otherRemark;

    @ExcelProperty("入职年度就业情形")
    private String employmentSituation;

    @ExcelProperty("手机号码")
    private String telPhone;

    @ExcelProperty("任职受雇从业日期")
    private String onboardingDate;

    @ExcelProperty("离职日期")
    private String dimissionDate;

    @ExcelProperty("是否离职后补发工资")
    private String isPostEmploymentPay ;

    @ExcelProperty("实际补发工资的月份")
    private String taxBelongingMonth;

    @ExcelProperty("是否残疾")
    private String isDisability;

    @ExcelProperty("是否烈属")
    private String isMartyr;

    @ExcelProperty("是否孤老")
    private String isLonelyOld;

    @ExcelProperty("残疾证件类型")
    private String disabilityCertificateType;

    @ExcelProperty("残疾证号")
    private String disabilityCertificateNumber;

    @ExcelProperty("烈属证号")
    private String martyrCertificateNumber;

    @ExcelProperty("是否扣除减除费用")
    private String isDeductFees;

    @ExcelProperty("个人投资额")
    private String personalInvestmentAmount;

    @ExcelProperty("个人投资比例(%)")
    private String personalInvestmentRatio;

    @ExcelProperty("备注")
    private String remark;

    @ExcelProperty("中文名")
    private String chineseName;

    @ExcelProperty("涉税事由")
    private String taxRelatedMatters;

    @ExcelProperty("出生国家(地区)")
    private String birthCounty;

    @ExcelProperty("首次入境时间")
    private String firstEntryTime;

    @ExcelProperty("预计离境时间")
    private String expectedDepartureTime;

    @ExcelProperty("其他证件类型")
    private String otherCertificateType;

    @ExcelProperty("其他证件号码")
    private String otherCertificateNumber;

    @ExcelProperty("户籍所在地（省）")
    private String censusProvince;

    @ExcelProperty("户籍所在地（市）")
    private String censusCity;

    @ExcelProperty("户籍所在地（区县）")
    private String censusCounty;


    @ExcelProperty("户籍所在地（详细地址）")
    private String censusAddress;

    @ExcelProperty("经常居住地（省）")
    private String liveProvince;

    @ExcelProperty("经常居住地（市）")
    private String liveCity;

    @ExcelProperty("经常居住地（区县）")
    private String liveCounty;


    @ExcelProperty("经常居住地（详细地址）")
    private String liveAddress;

    @ExcelProperty("联系地址（省）")
    private String contactProvince;

    @ExcelProperty("联系地址（市）")
    private String contactCity;

    @ExcelProperty("联系地址（区县）")
    private String contactCounty;


    @ExcelProperty("联系地址（详细地址）")
    private String contactAddress;

    @ExcelProperty("电子邮箱")
    private String email;

    @ExcelProperty("学历")
    private String qualification;

    @ExcelProperty("开户银行")
    private String bankName;

    @ExcelProperty("银行账号")
    private String bankAccount;

    @ExcelProperty("开户行省份")
    private String bankProvince;

    @ExcelProperty("职务")
    private String post;
}
