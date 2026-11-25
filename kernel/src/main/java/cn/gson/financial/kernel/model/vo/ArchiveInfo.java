package cn.gson.financial.kernel.model.vo;

import lombok.Builder;

import java.io.File;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Builder
public class ArchiveInfo {

    private String name;

    private File file;
}
