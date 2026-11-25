package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.Backup;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A DTO for the {@link Backup} entity
 */
@Getter
@Setter
public class BackUpDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String title;
    @JsonIgnore
    private String path;
    private Long size;
    private LocalDateTime bakTime;
    private Long userId;
    private String userName;
    private Integer bakType;
}
