package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.User} entity
 */
@Getter
@Setter
public class UserDto implements Serializable {
    private Long id;
    private String mobile;
    private String unionId;
    private String openId;
    private String nickname;
    private String avatarUrl;
    private String realName;
    private Date createDate;
    private String email;
    private Integer totalAccounts;
    private LocalDate expirationDate;
    private Integer accountSetsNum;
    private Integer accountingNum;
    private Integer userNum;
    private LocalDateTime lastUpdate;
}
