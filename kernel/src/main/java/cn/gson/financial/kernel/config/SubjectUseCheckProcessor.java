package cn.gson.financial.kernel.config;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface SubjectUseCheckProcessor {

    /**
     * 是否使用了相应科目
     *
     * @param accountSetsId
     * @param subjectId
     * @return
     */
    boolean isSubjectUsed(Long accountSetsId, Long subjectId);
}
