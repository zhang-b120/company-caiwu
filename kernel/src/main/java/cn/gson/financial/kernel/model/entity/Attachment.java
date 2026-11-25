package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
@Comment("账套上传文件信息")
public class Attachment extends AbsEntity {

    @Comment("文件原始标题")
    private String title;

    @Comment("文件存储标题")
    private String storageTitle;

    @Comment("文件文件路径")
    private String path;

    @Comment("文件大小")
    private Long size;

    @Comment("来源ID")
    private Long sourceId;

    @Comment("文件上传时间")
    private LocalDateTime bakTime;

    @Comment("文件上传人")
    private Long userId;

    @Comment("文件类型")
    private String fileType;

    @Comment("上传类型")
    @Enumerated(EnumType.STRING)
    private AttachmentType type;
}
