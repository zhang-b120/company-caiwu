package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.util.List;

@Data
public class GetAgentChannelInfoReq {

    /**
     * 用户ID列表（过滤账套归属用户）
     */
    private List<Long> userIds;

    /**
     * 服务人员姓名列表（多个姓名之间为或关系）
     */
    private List<String> serviceStaffList;

    /**
     * 功能权限，例如：税费申报
     */
    private String functionPermissions;

    /**
     * 账期，格式 yyyy-MM-dd，例如 2026-02-01
     */
    private String checkDate;
}
