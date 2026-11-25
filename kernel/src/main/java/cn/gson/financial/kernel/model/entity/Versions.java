package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.time.LocalDate;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-01 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-01</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("版本信息")
public class Versions {
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("管理员ID")
    private Long adminId;

    @CreationTimestamp
    @Comment("创建时间")
    private LocalDate createDate;

    @Comment("版本发布时间")
    private LocalDate publishDate;

    @Comment("版本号")
    private String versionName;

    @Comment("更新内容")
    @Column(columnDefinition = "text")
    private String content;
}
