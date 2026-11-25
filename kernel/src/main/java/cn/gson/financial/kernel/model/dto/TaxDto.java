package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class TaxDto implements Serializable {
    @Comment(value = "税表id")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taxId;
    @Comment(value = "账套id")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long accountSetsId;
    @Comment(value = "账套名称")
    private String companyName;
    @Comment(value = "税表类型")
    private String taxType;
    @Comment(value = "税表状态")
    private String taxState;
    @Column(name = "user_id")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    @Comment(value = "税表截止时间")
    private LocalDate taxEndTime;
    @Comment(value="账期")
    private String accountPeriod;
    private LocalDate enableDate;
    private String creditCode;
    private String accountStandard;
    private String vatType;
    private String address;
    private Boolean cashierModule;
    private Integer industry;
    private Boolean fixedAssetModule;
    private Boolean voucherReviewed;
    //创建账套的日期
    private Date createDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long creatorId;
    private String creatorName;
    private String roleName;
    private String creatorMobile;
    private Long voucherCount;
    private LocalDate currentAccountDate;
    private List<Integer> encoding;
    private List<SubjectCategory> subjectCategory;
    private Boolean journal;
    private Boolean assets;
    private String flag;
    private LocalDate effectiveDate;
    private Long astId;
    private Boolean imported;
    private String syncType;
    private String electricityTaxCode;
    private String mobile;
    private String province;
    //报税查询特殊发票的时间段
    private LocalDate startDate;
    private LocalDate endDate;
    private Long localCurrencyId;
    //创建报税任务的时间
    private LocalDateTime createTime;
    /**
     * 首页报税条目截图
     */
    private String imageUrl;
    /**
     * 具体的税截图
     */
    private String taxImageUrl;
    /**
     * 具体的税截图
     */
    private List<String> taxImageUrlList;
    /**
     * 税款合计
     */
    private String totalTaxes;
    /**
     * 税款截图
     */
    private String paymentUrl;
    private String taxPaymentHtml;
    /*
     * 问题描述
     */
    private String problemDescription;
    /**
     * 自检截图
     */
    private String selfInspectionImgUrl;
    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
