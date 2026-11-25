package cn.gson.financial.kernel.model.crm.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-07-14
 * @Description
 */
@Data
public class AddAccountSetMigrationDataDTO implements Serializable {
    private static final long serialVersionUID = -6921653652717501415L;

    /**
     * 账套ID
     */
    private Long accountSetId;

    /**
     * 实体类名
     */
    private String entityName;

    /**
     * 迁移数据
     */
    private String migrationData;
}