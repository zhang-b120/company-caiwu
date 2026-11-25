package cn.gson.financial.kernel.config;

import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.opl.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2021年06月24日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Component
@ConfigurationProperties("app")
public class AppConfig {

    /**
     * 上传文件的的服务器存储路径
     */
    private String uploadRoot;

    /**
     * pdf中文支持的字体路径，生成环境必须配置服务器的绝对路径
     */
    private String fontPath;

    private ALiYun aliyun;

    public File getUploadRoot() {
        File root;
        if (StrUtil.isEmpty(this.uploadRoot)) {
            root = new File(SystemUtil.getUserInfo().getHomeDir(), "attachment");
        } else {
            root = new File(uploadRoot);
        }
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    public File getUploadRoot(String child) {
        File root = new File(this.getUploadRoot(), child);
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    @Data
    public static class ALiYun {
        /**
         * 账号D
         */
        private String accessKeyId;
        /**
         * 账号秘钥
         */
        private String accessKeySecret;
    }
}
