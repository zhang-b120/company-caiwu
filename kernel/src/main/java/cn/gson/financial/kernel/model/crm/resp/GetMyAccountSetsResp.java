package cn.gson.financial.kernel.model.crm.resp;

import cn.gson.financial.kernel.model.enums.SubjectCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-07-05
 * @Description
 */
@Data
public class GetMyAccountSetsResp implements Serializable {

    private static final long serialVersionUID = -4077411983038019566L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long accountSetsId;

    private String companyName;

    private LocalDate enableDate;

    private String creditCode;

    private String accountStandard;

    private String vatType;

    private String electricityTaxCode;

    private String electricityTaxPhone;

    private Date createDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long creatorId;

    private String province;

    private Integer status;

    private LocalDate checkDate;
    //报税查询特殊发票的时间段
    private Long localCurrencyId;

    private LocalDate currentAccountDate;

    private List<GetTaxTabResp> taxTabRespList;

    private Integer allCount;
    private Integer successCount;
    private String imgPath;
    //税款截图
    private String taxAmountImg;
}