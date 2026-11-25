package cn.gson.financial.kernel.model.entity;

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
 * <li>Description : 辅助核算-供应商</li>
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
@Comment("辅助项-供应商")
public class Vendor extends AcDetails {
    @Comment("供应商类别")
    private String category;

    @Comment("统一社会信用代码")
    private String creditCode;

    @Comment("经营地址")
    private String address;

    @Comment("联系人")
    private String contact;

    @Comment("手机")
    private String telPhone;
}
