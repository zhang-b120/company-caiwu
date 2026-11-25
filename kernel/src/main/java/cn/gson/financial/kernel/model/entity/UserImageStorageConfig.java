package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户本地图片存储配置表
 *
 * @author 开发者
 * @date 2025-01-XX
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "user_image_storage_config")
@Comment("用户本地图片存储配置表")
public class UserImageStorageConfig {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    /**
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    @Comment("用户ID")
    private Long userId;

    /**
     * 图片存储根路径
     */
    @Column(name = "storage_root", nullable = false, length = 500)
    @Comment("图片存储根路径")
    private String storageRoot;

    /**
     * 存储类型: 1-本地, 2-OSS, 3-CDN
     */
    @Column(name = "storage_type", nullable = false, columnDefinition = "TINYINT DEFAULT 1")
    @Comment("存储类型: 1-本地, 2-OSS, 3-CDN")
    private Integer storageType;

    /**
     * 路径用途说明
     */
    @Column(name = "purpose", length = 200)
    @Comment("路径用途说明")
    private String purpose;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    @UpdateTimestamp
    @Comment("更新时间")
    private LocalDateTime updatedAt;
}

