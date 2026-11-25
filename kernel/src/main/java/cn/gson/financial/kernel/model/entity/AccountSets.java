package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import cn.hutool.core.lang.Assert;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 账套</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年10月16日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("账套")
public class AccountSets implements Serializable {
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    /**
     * 单位名称
     */
    @Comment("单位名称")
    private String companyName;
    /**
     * 电税密码
     */
    @Comment("电税密码")
    private String electricityTaxCode;

    /**
     * 个税密码
     */
    @Comment("个税密码")
    private String individualTaxCode;

    @Comment("省份")

    private String province;

    @Comment("是否重分类")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean resetStatus;

    @Comment("是否停用")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean deactivated;

    @Comment("是否是零申报")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean zeroDeclarations;

    @Comment("客户星级（0-5，0表示未评级）")
    @ColumnDefault("0")
    private Integer starRating;
    //登录手机号
    @Comment("电税手机号")
    private String electricityTaxPhone;

    //能否报个税,如果为空则表示能申报，如果不为空则不能申报
    @Comment("是否能报个税")
    private String individualTax;

    /**
     * 账套启用年月
     */
    @Comment("账套启用年月")
    private LocalDate enableDate;

    /**
     * 统一社会信用代码
     */
    @Comment("统一社会信用代码")
    private String creditCode;

    /**
     * 0.企业会计准则、1.企业会计准则、2.民间非营利组织会计制度
     */
    @Comment("会计准则")
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private AccountStandard accountStandard;

    /**
     * 单位所在地
     */
    @Comment("单位所在地")
    private String address;

    /**
     * 是否启用出纳模块
     */
    @Comment("是否启用出纳模块")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean cashierModule;

    /**
     * 行业（旧版内部分类编号 0-12，已废弃，保留仅为兼容历史数据；
     * 新数据请使用 {@link #industryCode} / {@link #industryName}）
     */
    @Deprecated
    @Comment("行业（旧版内部分类编号，已废弃，请使用industryCode/industryName）")
    private Integer industry;

    /**
     * 行业代码（国标 GB/T 4754《国民经济行业分类》代码）
     */
    @Comment("行业代码（国标GB/T 4754）")
    @Column(name = "industry_code", length = 32)
    private String industryCode;

    /**
     * 行业名称（与 industryCode 对应的中文名称）
     */
    @Comment("行业名称")
    @Column(name = "industry_name", length = 128)
    private String industryName;

    /**
     * 是否个体工商户
     */
    @Comment("是否个体工商户")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean individualBusiness = false;

    /**
     * 企业经营范围
     */
    @Comment("企业经营范围")
    @Column(name = "business_scope", columnDefinition = "TEXT")
    private String businessScope;

    /**
     * 企业类型 / 组织形式（数据源：天眼查原文）
     * 例：有限责任公司(自然人投资或控股)、其他股份有限公司(上市)、个体工商户。
     * 可能为空（公司未同步过天眼查时不传 / 传 null），允许为空。
     */
    @Comment("企业类型/组织形式（天眼查原文）")
    @Column(name = "company_org_type", length = 128)
    private String companyOrgType;

    /**
     * 是否启用固定资产模块
     */
    @Comment("是否启用固定资产模块")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean fixedAssetModule;

    /**
     * 增值税种类
     * 0.小规模纳税人、1.一般纳税人
     */
    @Comment("增值税种类")
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private VatType vatType;

    @Transient
    private String vatTypes;

    /**
     * FN-INVOICE-COLLECT-MODE-CONFIG：发票采集/归属期模式（账套级，默认季度）。仅对小规模生效。
     */
    @Comment("发票采集模式：QUARTERLY 季度（默认）/ MONTHLY 月度，仅小规模生效")
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_collect_mode", length = 16)
    @org.hibernate.annotations.ColumnDefault("'QUARTERLY'")
    private cn.gson.financial.kernel.model.enums.InvoiceCollectMode invoiceCollectMode = cn.gson.financial.kernel.model.enums.InvoiceCollectMode.QUARTERLY;

    /**
     * FN-INVOICE-COLLECT-MODE-CONFIG：本账套发票是否「按季归集」= 小规模 且 采集模式=季度。
     * 一般纳税人恒 false（按月，与配置无关，杜绝 config/vatType 双判据打架）；小规模 MONTHLY → false（按月）。
     */
    public boolean isQuarterlyInvoiceCollect() {
        return vatType == VatType.SMALL_SCALE
                && invoiceCollectMode != cn.gson.financial.kernel.model.enums.InvoiceCollectMode.MONTHLY;
    }

    /**
     * 凭证是否需要审核
     */
    @Comment("凭证是否需要审核")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean voucherReviewed;

    @Comment("创建时间")
    @CreationTimestamp
    private Date createDate;

    @Comment("有效日期")
    private LocalDate effectiveDate;

    /**
     * 创建人
     */
    @Comment("创建人")
    private Long creatorId;

    /**
     * 账套模板ID
     */
    @Comment("模板ID")
    private Long astId;

    /**
     * 当前记账年月
     */
    @Comment("当前记账年月")
    private LocalDate currentAccountDate;

    /**
     * 科目编码方式
     */
    @Comment("科目编码方式")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Integer> encoding;

    @Comment("科目类别")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<SubjectCategory> subjectCategory;

    /**
     * 绑定的 OpenClaw 实例编号（如 OC-01），账套级别绑定，整套流程共用
     */
    @Comment("绑定的OpenClaw实例编号，如OC-01，为空则自动分配")
    @Column(name = "oc_instance_id", length = 32)
    private String ocInstanceId;

    /**
     * 税务登录渠道（账套级别）。
     * <ul>
     *   <li>{@code officer}      - 办税员账号+密码（默认）</li>
     *   <li>{@code proxy}        - 代理通道</li>
     *   <li>{@code legal_person} - 法人信息</li>
     *   <li>{@code ca}           - CA 证书</li>
     * </ul>
     */
    @Comment("税务登录渠道：officer/proxy/legal_person/ca")
    @Column(name = "login_type", length = 20)
    @ColumnDefault("'officer'")
    private String loginType = "officer";

    /**
     * 备注（不随账期变化，用于首页备注列）
     */
    @Comment("备注")
    @Column(length = 500)
    private String remark;

    /**
     * AI 分析用企业简介（描述主营业务、进销项特征等，帮助 AI 更准确地匹配发票业务类型）
     */
    @Comment("AI分析用企业简介：主营业务、进销项特征等，供AI做账时参考")
    @Column(name = "ai_profile", columnDefinition = "TEXT")
    private String aiProfile;

    /**
     * 任务步骤执行模式配置（JSON）
     * 格式：{"verify":"auto","init":"auto","data":"auto","bookkeep":"auto","vat":"auto","iit":"auto","payment":"auto"}
     * 值为 "auto" 时自动执行，"manual" 时暂停等待人工确认后再执行
     */
    @Comment("任务步骤执行模式：auto=自动执行，manual=人工确认后执行")
    @Column(name = "step_config", columnDefinition = "TEXT")
    private String stepConfig;

    /**
     * 首页显示顺序（数值越小越靠前）
     */
    @Comment("首页显示顺序")
    @Column(name = "display_order")
    private Integer displayOrder;

    /**
     * 资金模块
     */
    @Comment("资金模块")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean journal;
    /**
     * 固定资产模块
     */
    @Comment("固定资产模块")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean assets;
    /**
     * 是否是导入账套
     */
    @Comment("是否是导入账套")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean imported;
    /**
     * 导入来源
     */
    @Comment("导入来源")
    @Column(length = 64)
    private String syncType;

    @PrePersist
    public void prePersist() {
        Assert.isTrue(enableDate != null && enableDate.isAfter(LocalDate.of(2009, 12, 31)), "账套启用年月必须大2009年");
    }

    @PreUpdate
    public void preUpdate() {
        if (enableDate != null) {
            Assert.isTrue(enableDate.isAfter(LocalDate.of(2009, 12, 31)), "账套启用年月必须大2009年");
        }
    }

}
