package cn.gson.financial.kernel.tool;

import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年09月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AttachUtil {

    private final AppConfig appConfig;

    public String getAttachPath(String path) {
        return "/attachment/" + path;
    }

    public String saveFile(Long accountSetsId, InputStream file, AttachmentType type, String name) throws IOException {
        String path = type.name() + "/" + name;
        File attrDir = new File(appConfig.getUploadRoot("ast-" + accountSetsId), type.name());
        if (!attrDir.exists()) {
            Assert.isTrue(attrDir.mkdirs(), "附件目录创建失败~");
        }
        File attFile = new File(attrDir, name);
        FileCopyUtils.copy(file, new FileOutputStream(attFile));
        return path;
    }

    public FileRecord getFile(Long accountSetsId, String path) {
        path = path.replace("/attachment/", accountSetsId != null ? "ast-" + accountSetsId + "/" : "");
        File file = new File(appConfig.getUploadRoot(), path);
        //兼容历史请求
        if (!file.exists()) {
            file = new File(appConfig.getUploadRoot(), path.replace("/attachment/", ""));
        }
        return new FileRecord(file, FileUtil.readBytes(file));
    }

    public record FileRecord(File file, byte[] bytes) {
    }
}
