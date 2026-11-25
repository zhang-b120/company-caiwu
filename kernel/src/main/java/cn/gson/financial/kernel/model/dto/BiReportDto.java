package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.BiReport}
 */
@Data
public class BiReportDto {
    private Long id;
    private Long accountSetsId;
    private String name;
    private Integer pos;
    private String tag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String remark;
}
