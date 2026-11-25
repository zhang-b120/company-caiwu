package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算-部门</li>
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
@Comment("辅助项-部门")
public class Department extends AcDetails {

    @Comment("负责人")
    private String leader;

    @Comment("手机")
    private String telPhone;

    @Comment("成立日期")
    private LocalDate startDate;

    @Comment("撤销日期")
    private LocalDate endDate;
}
