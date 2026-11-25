package cn.gson.financial.kernel.model.entity;

import cn.hutool.core.bean.BeanUtil;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-09-03
 * @Description
 */
@DynamicInsert
@DynamicUpdate
@Entity
@Data
@Table(name = "sync_employee_information")
public class SyncEmployeeInformation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;


    /**
     * 账套ID
     */
    @Column(name = "account_Sets_Id")
    @Comment("账套ID")
    private Long accountSetsId;

    /**
     * 结果 0 未执行 1 失败 2成功
     */
    @Column(name = "result")
    @Comment("结果")
    private Integer result;

    /**
     *失败原因
     */
    @Column(name = "failed_reason")
    @Comment("失败原因")
    private String failedReason;

    /**
     * 截图名
     */
    @Column(name = "img_name")
    @Comment("截图名")
    private String imgName;

    /**
     * 截图路径
     * @return
     */
    @Column(name = "img_url")
    @Comment("截图名")
    private String imgUrl;

    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
