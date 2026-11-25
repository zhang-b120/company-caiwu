package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.*;

/**
 * @Author zhb
 * @Date 2025-09-16
 * @Description
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("银行库")
@Table(name = "bank_lib")
public class BankLib {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("银行编码")
    @Column(name = "bank_code", length = 50)
    private String bankCode;

    @Comment("银行名称")
    @Column(name = "bank_name", length = 50)
    private String bankName;

    @Comment("是否自动识别 0否 1是")
    @Column(name = "is_auto_recognize", length = 0)
    private Integer isAutoRecognize;

    @Comment("是否启用 0否 1是")
    @Column(name = "enabled", length = 0)
    private Integer enabled;

    @Comment("是否删除 0否 1是")
    @Column(name = "is_delete")
    @ColumnDefault("0")
    private Integer isDelete;
}