package cn.gson.financial.kernel.model.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账套批量导入 Excel 映射 DTO
 */
@Data
public class AccountSetsImportDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty("单位名称")
    private String companyName;

    @ExcelProperty("统一社会信用代码")
    private String creditCode;

    @ExcelProperty("账套启用年月")
    private String enableDate;

    @ExcelProperty("增值税种类")
    private String vatType;

    @ExcelProperty("电税密码")
    private String electricityTaxCode;

    @ExcelProperty("个税密码")
    private String individualTaxCode;

    @ExcelProperty("税务所属地")
    private String province;

    @ExcelProperty("税局手机号")
    private String electricityTaxPhone;

    @ExcelProperty("是否零申报")
    private String zeroDeclarations;
}
