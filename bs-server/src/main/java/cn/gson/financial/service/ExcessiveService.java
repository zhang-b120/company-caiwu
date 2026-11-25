package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSetsTemplate;
import cn.gson.financial.kernel.service.AbsService;
import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : 过渡性服务，TODO 后续要删除</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Deprecated
@Service
public class ExcessiveService extends AbsService implements ApplicationListener<ApplicationStartedEvent> {

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QAccountSetsTemplate qAccountSetsTemplate = QAccountSetsTemplate.accountSetsTemplate;

    @Transactional
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        Map<String, AccountSetsTemplate> astList = new HashMap<>();
        bqf.selectFrom(qAccountSetsTemplate).orderBy(qAccountSetsTemplate.id.asc()).fetch().forEach(s -> {
            String key = s.getAccountStandard().name() + ":" + s.getVatType().name();
            if (!astList.containsKey(key)) {
                astList.put(key, s);
            }
        });

        List<AccountSets> setsList = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.astId.isNull())
                .fetch();
        if (CollUtil.isNotEmpty(setsList)) {
            for (AccountSets accountSets : setsList) {
                AccountSetsTemplate template = astList.get(accountSets.getAccountStandard().name() + ":" + accountSets.getVatType().name());
                if (template != null) {
                    jqf.update(qAccountSets)
                            .set(qAccountSets.astId, template.getId())
                            .where(qAccountSets.id.eq(accountSets.getId()))
                            .execute();
                }
            }
        }
    }
}
