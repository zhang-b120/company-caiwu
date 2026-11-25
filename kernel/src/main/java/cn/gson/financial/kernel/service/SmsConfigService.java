package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.QSmsConfig;
import cn.gson.financial.kernel.model.entity.SmsConfig;
import cn.gson.financial.kernel.model.repo.SmsConfigRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年05月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SmsConfigService extends AbsService {

    private final QSmsConfig qSmsConfig = QSmsConfig.smsConfig;

    private final SmsConfigRepository smsConfigRepository;

    @Transactional
    public void save(SmsConfig smsConfig) {
        SmsConfig config = bqf.selectFrom(qSmsConfig).fetchFirst();

        if (config != null) {
            BeanUtil.copyProperties(smsConfig, config, CopyOptions.create().ignoreNullValue());
            smsConfigRepository.save(config);
        } else {
            smsConfigRepository.save(smsConfig);
        }
    }

    /**
     * 加载短信配置
     *
     * @return SmsConfig
     */
    public SmsConfig load() {
        return bqf.selectFrom(qSmsConfig).fetchFirst();
    }
}
