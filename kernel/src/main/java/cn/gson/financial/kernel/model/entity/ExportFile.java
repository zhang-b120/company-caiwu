package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-09-03
 * @Description
 */
@DynamicInsert
@DynamicUpdate
@Entity
@Data
@Table(name = "export_file")
public class ExportFile {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Column(name = "accountSetsId")
    @Comment("账套ID")
    private Long accountSetsId;

    @Column(name = "period")
    @Comment("账期")
    private LocalDate period;

    @Comment("文件路径")
    @Column(name = "file_url")
    private String fileUrl;

    @Comment("文件名称")
    @Column(name = "file_name")
    private String fileName;

    @Comment("文件类型")
    @Column(name = "file_suffix")
    private String fileSuffix;

    @Comment("文件全路径")
    @Column(name = "file_path")
    private String filePath;

    @Comment("类型")
    @Column(name = "type")
    private String type;

    @Comment("类型")
    @Column(name = "source")
    private String source;
}