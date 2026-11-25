package cn.gson.financial.kernel.model.ua.resp;

import lombok.Data;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@Data
public class GetUserInfoResp {

    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户手机号
     */
    private String mobile;

    /**
     * 账套启用日期
     */
    private LocalDate enableDate;

}
