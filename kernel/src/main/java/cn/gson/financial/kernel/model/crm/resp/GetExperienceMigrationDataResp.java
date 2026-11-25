package cn.gson.financial.kernel.model.crm.resp;

import lombok.Data;

import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-07-14
 * @Description
 */
@Data
public class GetExperienceMigrationDataResp implements Serializable {
    private static final long serialVersionUID = -6091750546250412692L;

    /**
     * 账套ID
     */
    private Long experienceAccountSetId;

    /**
     * 实体类名
     */
    private String entityName;

    /**
     * 迁移数据
     */
    private String migrationData;

    public GetExperienceMigrationDataResp(){}

    public GetExperienceMigrationDataResp(Long experienceAccountSetId, String entityName, String migrationData){
        this.experienceAccountSetId = experienceAccountSetId;
        this.entityName = entityName;
        this.migrationData = migrationData;
    }
}