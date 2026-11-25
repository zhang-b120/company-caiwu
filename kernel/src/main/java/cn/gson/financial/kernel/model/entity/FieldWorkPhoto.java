package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FN-07 外勤拍照归档
 * <p>
 * 给 FM-12 外勤手机端提供"拍照 → 上传 → 关联客户档案 + 任务"的归档存储。
 * 物理表名 fxy_financial_field_work_photo（PhysicalNamingStrategy 自动加前缀）。
 * <p>
 * 单 PK + JpaRepository（不属于账套数据多 PK 体系，仅一个简单 PK，ID 由 cosid 雪花生成）。
 * 物理文件存放在 customize-config.base-path 下 field-work-photos/{accountSetsId}/{yyyy-MM}/ 子目录。
 */
@Getter
@Setter
@Entity
@Table(name = "field_work_photo")
@Comment("外勤拍照归档（FN-07）")
public class FieldWorkPhoto implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("关联客户 ID（可选）")
    @Column(name = "customer_id")
    private Long customerId;

    @Comment("关联任务 ID（可选，可指向 declare_task / invoice_task / oc_task 等）")
    @Column(name = "task_id")
    private Long taskId;

    @Comment("原始文件名")
    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Comment("服务端保存的文件名")
    @Column(name = "storage_name", length = 255, nullable = false)
    private String storageName;

    @Comment("相对 storage root 的路径")
    @Column(name = "storage_path", length = 512, nullable = false)
    private String storagePath;

    @Column(name = "file_suffix", length = 16)
    private String fileSuffix;

    @Column(name = "mime_type", length = 64)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "gps_lat", precision = 10, scale = 7)
    private BigDecimal gpsLat;

    @Column(name = "gps_lng", precision = 10, scale = 7)
    private BigDecimal gpsLng;

    @Comment("拍照时间（前端传入）")
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "description", length = 1024)
    private String description;

    @Comment("上传人 user_id")
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
