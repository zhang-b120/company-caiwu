package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-07-16
 * @Description
 */
@DynamicInsert
@DynamicUpdate
@Entity
@Setter
@Getter
@Comment("文件信息")
public class FileInfo extends AbsEntity {
    private static final long serialVersionUID = 4425433533672525591L;

    @Comment("文件路径")
    @Column(name = "file_path")
    private String filePath;

    @Comment("源文件名称")
    @Column(name = "original_file_name")
    private String originalFileName;

    @Comment("文件名称")
    @Column(name = "file_name")
    private String fileName;

    @Comment("文件类型")
    @Column(name = "file_suffix")
    private String fileSuffix;

    @Comment("账期")
    @Column(name = "account_set_period")
    private LocalDate accountSetPeriod;

    @Comment("类型")
    @Column(name = "type")
    private String type;

    @Comment("创建时间")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Comment("是否删除")
    @Column(name = "is_delete")
    private Integer isDelete;

    @Comment("业务编码")
    @Column(name = "business_code", length = 50)
    private String businessCode;

    @Comment("业务名称")
    @Column(name = "business_Name", length = 50)
    private String businessName;

    @Comment("是否已处理 0否 1是")
    @Column(name = "is_deal")
    @ColumnDefault("0")
    private Integer isDeal;
}