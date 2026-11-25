package cn.gson.financial.kernel.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.time.LocalDate;

@DynamicInsert
@DynamicUpdate
@Entity
@Data
public class TaxReportResult implements Serializable {

    @Comment(value = "账套id")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long accountSetId;
    /**
     * 主键id
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 账期
     */

    private LocalDate taxTime;
    /**
     * 报税条目总数
     */
    private int allCount;
    /**
     * 已申报数量
     */
    private int successCount;
    /**
     * 报税截图路径
     */
    private String imgPath;

    private String flag;
}
