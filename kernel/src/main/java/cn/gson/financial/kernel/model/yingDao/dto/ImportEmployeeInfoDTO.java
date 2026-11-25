package cn.gson.financial.kernel.model.yingDao.dto;

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
public class ImportEmployeeInfoDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 7401935103217479643L;

    @ExcelProperty("工号")
    private String code;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("证件号码")
    private String idCard;

    @ExcelProperty("性别")
    private String sex;

    @ExcelProperty("出生日期")
    private LocalDate birthDate;

    @ExcelProperty("人员状态")
    private String employeeStatus;

    @ExcelProperty("手机号码")
    private String telPhone;

    @ExcelProperty("任职受雇从业日期")
    private String onboardingDate;

    @ExcelProperty("离职日期")
    private String dimissionDate;

}
