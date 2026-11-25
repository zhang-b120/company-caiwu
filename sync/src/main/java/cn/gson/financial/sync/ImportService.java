package cn.gson.financial.sync;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.sync.dto.SubjectImportMappingDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.sync</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月13日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface ImportService {

    /**
     * 分析并导入数据
     * @param fileKm 科目文件
     * @param fileQc 期初文件
     * @param filePz 凭证文件
     * @param companyName 企业名称
     * @param encodings 科目编码规则
     * @param subjectMappings 导入科目和系统预设科目映射匹配关系
     * @param vatType 类型
     * @param accountStandard 准则类型
     * @param userId 导入用户id
     * @param astId 账套模板id
     * @param soft 导入来源
     * @return
     * @throws IOException
     */
    @Transactional
    AccountSets analysisData(MultipartFile fileKm,
                             MultipartFile fileQc,
                             MultipartFile filePz,
                             String companyName,
                             List<Integer> encodings,
                             List<SubjectImportMappingDto> subjectMappings,
                             VatType vatType,
                             AccountStandard accountStandard,
                             Long userId,
                             Long astId,
                             SyncType soft) throws IOException;
}
