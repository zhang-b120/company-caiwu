package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 账套模板</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("账套模板")
public class AccountSetsTemplate {
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("科目模板名称")
    private String title;

    @Comment("编码规则")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Integer> encoding;

    @Comment("科目类别")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<SubjectCategory> subjectCategory;

    @Comment("会计准则")
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private AccountStandard accountStandard;

    @Comment("增值税类型")
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private VatType vatType;

    @Comment("是否启用")
    @ColumnDefault("1")
    private Boolean enabled;

    private LocalDateTime lastUpdateTime;

}
