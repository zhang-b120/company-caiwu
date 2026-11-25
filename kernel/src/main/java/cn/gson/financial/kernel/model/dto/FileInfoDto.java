package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-07-16
 * @Description
 */
@Data
public class FileInfoDto implements Serializable {
    private static final long serialVersionUID = -1815443789324836421L;

    private Long id;

    private String originalFileName;

    private String fileName;

    private String filePath;

    private LocalDateTime createTime;

    private String businessCode;

    private String businessName;

    private Integer isDeal;
}