package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.VoucherType;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-08 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-08</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
public class SalaryVoucherDto implements Serializable {
    private Long voucherId;
    private Long accountSetsId;
    private Integer year;
    private Integer month;
    private BigDecimal salaryAmount;
    private BigDecimal socialAmount;
    private BigDecimal practicalAmount;
    private LocalDateTime crateTime;
    private LocalDateTime billTime;
    private String Status;
    private VoucherType voucherType;

    private String TemplateName;
    private String subjectName;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal amount;
    private Long templateId;
    private Long subjectId;
    private String direction;
    private String word;

}
