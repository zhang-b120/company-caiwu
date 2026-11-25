package cn.gson.financial.kernel.model.crm.req;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一键报税 - 公司请求实体
 */
@Data
public class OneKeyTaxCompanyReq implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long accountSetsId;

    private String companyName;

    private String accountStandard;

    private String vatType;

    private String creditCode;

    private String province;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate currentAccountDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long localCurrencyId;

    private String electricityTaxCode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate enableDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long creatorId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private Boolean checked;

    private Boolean isTaxItem;

    private List<OneKeyTaxItemReq> children;
}

