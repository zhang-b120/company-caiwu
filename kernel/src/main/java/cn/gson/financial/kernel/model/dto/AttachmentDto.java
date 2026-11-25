package cn.gson.financial.kernel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.Attachment}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AttachmentDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String title;
    private String storageTitle;
    private String path;
    private Long size;
    private LocalDateTime bakTime;
    private Long userId;
    private Long sourceId;
    private String sourceTitle;
    private String userName;
    private String fileType;
    private String type;
}
