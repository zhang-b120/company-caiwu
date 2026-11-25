package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.aspect.CacheRemove;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.QVoucherWord;
import cn.gson.financial.kernel.model.entity.VoucherWord;
import cn.gson.financial.kernel.model.repo.VoucherWordRepository;
import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Service
@RequiredArgsConstructor
public class VoucherWordService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final VoucherWordRepository voucherWordRepository;

    private final QVoucherWord qVoucherWord = QVoucherWord.voucherWord;


    @Transactional
    @CacheRemove(value = "voucherWord", key = "#entity.accountSetsId")
    public VoucherWord save(VoucherWord entity) {
        if (entity.getPrimaryKey() == null) {
            //判断是否重复
            if (voucherWordRepository.count(qVoucherWord.word.eq(entity.getWord().trim())
                    .and(qVoucherWord.accountSetsId.eq(entity.getAccountSetsId()))) > 0) {
                throw new ServiceException("亲，保存失败啦！凭证字【%s】已经存在！", entity.getWord());
            }
        } else {
            //判断是否重复
            if (voucherWordRepository.count(qVoucherWord.word.eq(entity.getWord().trim())
                    .and(qVoucherWord.accountSetsId.eq(entity.getAccountSetsId()))
                    .and(qVoucherWord.id.ne(entity.getId()))) > 0) {
                throw new ServiceException("亲，保存失败啦！凭证字【%s】已经存在！", entity.getWord());
            }
        }

        VoucherWord voucherWord = voucherWordRepository.saveAndFlush(entity);
        if (entity.getIsDefault()) {
            jqf.update(qVoucherWord)
                    .set(qVoucherWord.isDefault, false)
                    .where(qVoucherWord.accountSetsId.eq(voucherWord.getAccountSetsId())
                            .and(qVoucherWord.id.ne(voucherWord.getId())))
                    .execute();
        }

        return voucherWord;
    }


    @Transactional
    @CacheRemove(value = "voucherWord", key = "#accountSetsId")
    public void remove(Long voucherWordId, Long accountSetsId) {
        VoucherWord word = jqf.selectFrom(qVoucherWord).where(qVoucherWord.accountSetsId.eq(accountSetsId).and(qVoucherWord.id.eq(voucherWordId))).fetchFirst();
        if (word.getIsDefault()) {
            throw new ServiceException("默认凭证字不能被删除！");
        }
        voucherWordRepository.delete(word);
    }

    /**
     * 如果新增为默认，则把其他的都设置为非默认
     *
     * @param entity
     */
    @Transactional
    @CacheRemove(value = "voucherWord", key = "#entity.accountSetsId")
    public void updateDefault(VoucherWord entity) {
        if (entity.getIsDefault()) {
            jqf.update(qVoucherWord)
                    .set(qVoucherWord.isDefault, false)
                    .where(qVoucherWord.accountSetsId.eq(entity.getAccountSetsId()))
                    .execute();
            jqf.update(qVoucherWord)
                    .set(qVoucherWord.isDefault, true)
                    .where(qVoucherWord.id.eq(entity.getId())
                            .and(qVoucherWord.accountSetsId.eq(entity.getAccountSetsId())))
                    .execute();
        }
    }

    @Cacheable(value = "voucherWord", key = "#accountSetsId")
    public List<VoucherWord> list(Long accountSetsId) {
        return bqf.selectFrom(qVoucherWord)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId))
                .orderBy(qVoucherWord.isDefault.desc())
                .fetch();
    }

    @Transactional
    @Override
    @CacheRemove(value = "voucherWord", key = "#accountSets.id")
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        //默认凭证字
        List<VoucherWord> defaultVw = bqf.selectFrom(qVoucherWord)
                .where(qVoucherWord.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();
        voucherWordRepository.saveAll(defaultVw.stream().map(vw -> {
            bqf.detach(vw);
            VoucherWord bean = BeanUtil.toBean(vw, VoucherWord.class);
            bean.setAccountSetsId(accountSets.getId());
            return bean;
        }).collect(Collectors.toList()));
    }

    @Transactional
    @Override
    @CacheRemove(value = "voucherWord", key = "#accountSetsId")
    public void clear(Long accountSetsId) {
        jqf.delete(qVoucherWord)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qVoucherWord)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return VoucherWord.class;
    }

    @Override
    @CacheRemove(value = "voucherWord", key = "#accountSetsId")
    public void recover(Long accountSetsId, String json) {
        List<VoucherWord> entityList = JSON.parseArray(json, VoucherWord.class);
        for (VoucherWord entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            voucherWordRepository.save(entity);
        }
    }
}

