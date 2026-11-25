package cn.gson.financial.kernel.model.hibernate;

import lombok.Data;

import java.io.Serializable;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.salebook.bean</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class FileInfo implements Serializable {
    private String name;
    private Long size;
    private String type;
    private String url;
    private String uid;
    private String thumbUrl;
    private String status;
}
