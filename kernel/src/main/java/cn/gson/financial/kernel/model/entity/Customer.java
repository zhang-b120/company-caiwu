package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算-客户</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("辅助项-客户")
public class Customer extends AcDetails {

    @Comment("客户类别")
    private String category;

    @Comment("统一社会信用代码")
    private String creditCode;

    @Comment("经营地址")
    private String address;

    @Comment("联系人")
    private String contact;

    @Comment("手机")
    private String telPhone;

    /**
     * 企微外部联系人 ID（external_userid），用于把企业微信好友与本系统客户记录绑定。
     * 由 Flyway V20260511_001 迁移加列，并在 wechat_external_userid 上建有
     * 普通索引 idx_customer_wechat_uid（非唯一，多账套场景）。
     */
    @Comment("企微外部联系人 ID")
    @Column(length = 64)
    private String wechatExternalUserid;

    // ─── FN-INVOICE-02 开票扩展字段 ──────────────────────────────

    @Comment("开户行（开票必填）")
    @Column(length = 200)
    private String bank;

    @Comment("银行账号（开票必填）")
    @Column(length = 100)
    private String bankAccount;

    @Comment("购买方纳税人类型 GENERAL/SMALL_SCALE/INDIVIDUAL")
    @Column(length = 20)
    private String taxpayerType;

    @Comment("默认发票类型 SPECIAL/NORMAL/DIGITAL_NORMAL")
    @Column(length = 20)
    private String defaultInvoiceType;

    @Comment("所属行业")
    @Column(length = 100)
    private String industry;

    @Comment("邮箱（发送 PDF 用）")
    @Column(length = 100)
    private String email;
}
