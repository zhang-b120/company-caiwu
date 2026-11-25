package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Table(name = "tax_payer")
@Comment("办税人，账套表所添加的办税人")
public class TaxPayer implements java.io.Serializable {

    /**
     * 账套id
     */
    @Id
    @Comment("账套id")
    @Column(name = "account_sets_id", nullable = false, length = 100)
    private Long accountSetsId;

    /**
     * 手机号1
     */
    @Comment("手机号1")
    @Column(name = "mobile1", length = 12)
    private String mobile1;

    /**
     * 手机号2
     */
    @Comment("手机号2")
    @Column(name = "mobile2", length = 11)
    private String mobile2;

    /**
     * 手机号3
     */
    @Comment("手机号3")
    @Column(name = "mobile3", length = 12)
    private String mobile3;
}
