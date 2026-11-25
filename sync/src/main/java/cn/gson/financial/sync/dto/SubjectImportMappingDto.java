package cn.gson.financial.sync.dto;

import lombok.Data;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年12月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Data
public class SubjectImportMappingDto {
    private String code;
    private Boolean isNew = false;
    private String matchedCode;
}
