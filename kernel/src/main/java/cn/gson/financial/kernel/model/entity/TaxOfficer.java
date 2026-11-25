package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 办税员
 * 一个账套可配置多个办税员，核验税务时使用指定（默认）办税员的凭证登录电子税务局
 */
@Getter
@Setter
@Entity
@Table(name = "tax_officer")
@Comment("办税员")
public class TaxOfficer implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("所属账套ID")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("办税员姓名")
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Comment("登录手机号 / 办税员账号")
    @Column(name = "phone", length = 20)
    private String phone;

    @Comment("电税密码")
    @Column(name = "password", length = 100)
    private String password;

    /**
     * 登录渠道类型：
     * <ul>
     *   <li>{@code officer}      - 办税员账号+密码（phone + password）</li>
     *   <li>{@code proxy}        - 代理通道登录（phone + password 存代理平台账号密码）</li>
     *   <li>{@code legal_person} - 法人信息登录（name + idCard + password）</li>
     *   <li>{@code ca}           - CA 证书（保留兼容）</li>
     *   <li>{@code password}     - 同 officer，旧值兼容</li>
     * </ul>
     */
    @Comment("登录渠道：officer/proxy/legal_person/ca/password(兼容旧值)")
    @Column(name = "login_type", length = 20, nullable = false)
    @ColumnDefault("'officer'")
    private String loginType = "officer";

    /**
     * 登录方式：自动 / 手动。FN-TAX-LOGIN-CREDENTIALS-WRITE-API。
     * <ul>
     *   <li>{@code auto}   - 自动登录（RPA 用凭证自动登电子税务局，现状默认；子类型见 loginType=officer/proxy）</li>
     *   <li>{@code manual} - 手动登录 = <b>BT 内 RPA + 用户实时交互（扫码 / 短信验证码），非脱离系统纯人工</b>；
     *       子方式见 {@link #manualMethod}（qrcode/sms）</li>
     * </ul>
     */
    @Comment("登录方式：auto(自动:办税员/代理)/manual(手动:BT内RPA+用户实时交互 扫码/短信)")
    @Column(name = "login_mode", length = 10, nullable = false)
    @ColumnDefault("'auto'")
    private String loginMode = "auto";

    /**
     * 手动登录子方式（仅 {@code login_mode=manual} 时有意义）。FN-TAX-LOGIN-MANUAL-METHOD-FIELD。
     * <ul>
     *   <li>{@code sms}    - 短信验证码登录（需 phone + password）</li>
     *   <li>{@code qrcode} - 扫码登录（不存凭证，省份取自账套画像）</li>
     * </ul>
     */
    @Comment("手动登录子方式：qrcode(扫码)/sms(短信)，仅 login_mode=manual 时有意义")
    @Column(name = "manual_method", length = 10)
    private String manualMethod;

    @Comment("法人身份证号（loginType=legal_person 时使用）")
    @Column(name = "id_card", length = 30)
    private String idCard;

    @Comment("代理机构税号（loginType=proxy 时使用）")
    @Column(name = "proxy_tax_no", length = 30)
    private String proxyTaxNo;

    /**
     * 是否为默认办税员（每个账套只有一个默认，下发时优先使用）
     * 字段名避免使用 is 前缀，防止 Lombok/Spring Data 生成 getter 时歧义
     */
    @Comment("是否默认使用（每个账套只有一个默认）")
    @Column(name = "is_default", nullable = false)
    @ColumnDefault("b'0'")
    private Boolean defaultOfficer = false;

    @Comment("是否系统内置办税员（不可删除）")
    @Column(name = "is_system", nullable = false)
    @ColumnDefault("b'0'")
    private Boolean systemOfficer = false;

    /**
     * FN-OFFICER-DEDUP ①：启用状态 ACTIVE/INACTIVE —— 保住「停用办税员」语义（此前 tax_officer 缺）。
     * INACTIVE 的办税员凭证不参与登录/任务下发（消费方按 status 过滤）。
     */
    @Comment("启用状态：ACTIVE 启用 / INACTIVE 停用")
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'ACTIVE'")
    private String status = "ACTIVE";

    @Comment("备注")
    @Column(name = "remark", length = 200)
    private String remark;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
