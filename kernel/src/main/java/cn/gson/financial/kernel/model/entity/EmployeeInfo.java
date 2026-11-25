package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import cn.hutool.core.bean.BeanUtil;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 员工信息实体类
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "employee_info")
public class EmployeeInfo {

    // 基础信息
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    private Long id;
    /* ========== 基础信息 ========== */
    @ColName("工号")
    private String employeeId;

    @ColName("姓名")
    private String name;

    @ColName("证件类型")
    private String idType;

    @ColName("证件号码")
    private String idNumber;

    @ColName("性别")
    private String gender;

    @ColName("出生日期")
    private Date birthDate;

    @ColName("所属账套id")
    private Long accountSetsId;

    @ColName("公司名称")
    private String companyName;

    /* ========== 状态信息 ========== */
    @ColName("人员状态")
    private String employeeStatus;

    @ColName("报送状态")
    private String reportStatus;

    @ColName("身份验证状态")
    private String authStatus;

    /* ========== 联系信息 ========== */
    @ColName("手机号码")
    private String phone;

    @ColName("电子邮箱")
    private String email;

    /* ========== 任职信息 ========== */
    @ColName("任职受雇从业日期")
    private Date employmentDate;

    @ColName("离职日期")
    private Date resignationDate;

    @ColName("是否扣除减除费用")
    private Boolean isDeduction;

    @ColName("职务")
    private String position;

    @ColName("学历")
    private String education;

    @ColName("任职受雇从业类型")
    private String employmentType;

    @ColName("入职年度就业情形")
    private String employmentSituation;

    /* ========== 银行信息 ========== */
    @ColName("开户银行")
    private String bankName;

    @ColName("银行账号")
    private String bankAccount;

    @ColName("开户行省份")
    private String bankProvince;

    @ColName("银行卡状态")
    private String bankCardStatus;

    /* ========== 居住地址 ========== */
    @ColName("经常居住地（省）")
    private String residenceProvince;

    @ColName("经常居住地（市）")
    private String residenceCity;

    @ColName("经常居住地（区县）")
    private String residenceDistrict;


    @ColName("经常居住地（详细地址）")
    private String residenceDetailAddress;

    /* ========== 户籍地址 ========== */
    @ColName("户籍所在地（省）")
    private String householdProvince;

    @ColName("户籍所在地（市）")
    private String householdCity;

    @ColName("户籍所在地（区县）")
    private String householdDistrict;


    @ColName("户籍所在地（详细地址）")
    private String householdDetailAddress;

    /* ========== 联系地址 ========== */
    @ColName("联系地址（省）")
    private String contactProvince;

    @ColName("联系地址（市）")
    private String contactCity;

    @ColName("联系地址（区县）")
    private String contactDistrict;


    @ColName("联系地址（详细地址）")
    private String contactDetailAddress;

    /* ========== 特殊属性 ========== */
    @ColName("是否残疾")
    private Boolean isDisabled;

    @ColName("是否烈属")
    private Boolean isMartyrFamily;

    @ColName("是否孤老")
    private Boolean isOrphanOrElderly;

    /* ========== 税务信息 ========== */
    @ColName("国籍(地区)")
    private String nationality;

    @ColName("纳税人识别号")
    private String taxpayerId;

    @ColName("个人投资额")
    private BigDecimal personalInvestment;

    @ColName("个人投资比例(%)")
    private BigDecimal investmentRatio;

    /* ========== 其他信息 ========== */
    @ColName("其他情况说明")
    private String otherRemarks;

    @ColName("备注")
    private String remarks;

    @ColName("残疾证件类型")
    private String disabilityCertType;

    @ColName("残疾证号")
    private String disabilityCertNumber;

    @ColName("烈属证号")
    private String martyrFamilyCertNumber;

    /* ========== 系统字段 ========== */
    @ColName("更新时间")
    private Date updateTime;
    // 添加辅助方法：根据列名设置字段值
    public void setFieldByColName(String colName, String value) throws Exception {
        if (value == null || value.isEmpty()) return;

        for (Field field : this.getClass().getDeclaredFields()) {
            ColName annotation = field.getAnnotation(ColName.class);
            if (annotation != null && annotation.value().equals(colName)) {
                setFieldValue(field, value);
                return;
            }
        }
        // 未找到匹配字段时不报错（静默跳过）
    }

    private void setFieldValue(Field field, String value) throws Exception {
        field.setAccessible(true);
        Class<?> type = field.getType();

        if (type == String.class) {
            field.set(this, value);
        } else if (type == Date.class) {
            field.set(this, parseDate(value));
        } else if (type == Boolean.class || type == boolean.class) {
            field.set(this, parseBoolean(value));
        } else if (type == BigDecimal.class) {
            field.set(this, new BigDecimal(value));
        } else if (type == Double.class || type == double.class) {
            field.set(this, Double.parseDouble(value));
        } else {
            throw new IllegalArgumentException("Unsupported field type: " + type);
        }
    }

    private Date parseDate(String value) throws ParseException {
        List<String> formats = Arrays.asList("yyyy-MM-dd", "yyyy/MM/dd", "yyyy年MM月dd日");
        for (String format : formats) {
            try {
                return new SimpleDateFormat(format).parse(value);
            } catch (ParseException ignored) {}
        }
        throw new ParseException("Unparseable date: " + value, 0);
    }

    private boolean parseBoolean(String value) {
        return "是".equals(value) || "true".equalsIgnoreCase(value)
                || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}

