package cn.gson.financial.kernel.model.entity;

import cn.hutool.core.bean.BeanUtil;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.util.Map;

/**
 * 申报自检定时任务实体类
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "timed_task_declare_self_inspection")
@Comment("申报自检定时任务")
public class TimedTaskDeclareSelfInspection {

    /**
     * 主键id
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("id")
    private Long id;

    /**
     * 统一社会信用代码
     */
    @Column(name = "credit_code", length = 100)
    @Comment("统一社会信用代码")
    private String creditCode;

    /**
     * 自检结果截图
     */
    @Column(name = "img_url", length = 100)
    @Comment("自检结果截图")
    private String imgUrl;

    /**
     * 问题描述
     */
    @Column(name = "problem_description", columnDefinition = "text")
    @Comment("问题描述")
    private String problemDescription;

    /**
     * 自检账期
     */
    @Column(name = "period", length = 100)
    @Comment("自检账期")
    private LocalDate period;

    /**
     * 账套id
     */
    @Column(name = "account_sets_id", length = 100)
    @Comment("账套id")
    private Long accountSetsId;

    /**
     * 转化为map
     */
    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
