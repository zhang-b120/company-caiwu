package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 银行流水导入学习规律表
 * 记录用户纠正的对方户名 → 收支类别 + 对方科目 映射关系
 * 供 AI 下次导入时参考，使识别越来越精准
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"accountSetsId", "counterparty"}))
@Comment("银行流水-AI学习规律表")
public class BankImportPattern extends AbsEntity {

    @Comment("对方户名（精确匹配）")
    @Column(length = 200)
    private String counterparty;

    @Comment("收支类别ID")
    private Long ioTypeId;

    @Comment("收支类别名称（冗余，供AI直接读取）")
    @Column(length = 50)
    private String ioTypeName;

    @Comment("收支方向：收/支")
    @Column(length = 4)
    private String ioTypeDirection;

    @Comment("对方科目ID")
    private Long subjectId;

    @Comment("对方科目编码（冗余，供AI直接读取）")
    @Column(length = 32)
    private String subjectCode;

    @Comment("对方科目名称（冗余，供AI直接读取）")
    @Column(length = 100)
    private String subjectName;

    @Comment("匹配使用次数")
    private Integer matchCount = 0;

    @Comment("最近使用时间")
    private LocalDateTime lastUsedAt;
}
