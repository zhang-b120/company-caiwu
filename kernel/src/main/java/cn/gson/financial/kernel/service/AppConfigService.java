package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.AppConfig;
import cn.gson.financial.kernel.model.entity.QAppConfig;
import cn.gson.financial.kernel.model.repo.AppConfigRepository;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AppConfigService extends AbsService {

    private final AppConfigRepository appConfigRepository;
    private final QAppConfig qAppConfig = QAppConfig.appConfig;

    private final RedisUtil redisUtil;

    @Transactional
    public void save(AppConfig appConfig) {
        AppConfig config = bqf.selectFrom(qAppConfig)
                .fetchFirst();
        if (config != null) {
            BeanUtil.copyProperties(appConfig, config, CopyOptions.create().ignoreNullValue());
            appConfigRepository.save(config);
        } else {
            appConfigRepository.save(appConfig);
        }
        redisUtil.delByKey("appConfig");
    }

    public AppConfig load() {
        String appConfig = redisUtil.get("appConfig", true);
        if (StrUtil.isNotEmpty(appConfig)) {
            return JSON.parseObject(appConfig, AppConfig.class);
        }
        AppConfig config = bqf.selectFrom(qAppConfig)
                .fetchFirst();
        if (config == null) {
            config = new AppConfig();
            config.setName("青艺科技");
            config.setCompany("开发公司");
            config.setRegister(true);
            config.setExperienceDays(30);
            appConfigRepository.save(config);
        }
        redisUtil.set("appConfig", JSON.toJSONString(config), true);
        return config;
    }
}
