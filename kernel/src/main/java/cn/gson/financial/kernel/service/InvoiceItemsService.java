package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.InvoiceItems;
import cn.gson.financial.kernel.model.entity.QInvoiceItems;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.InvoiceItemsRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (InvoiceItems)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-19 21:07:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceItemsService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;

    private final InvoiceItemsRepository invoiceItemsRepository;

    @Transactional
    public InvoiceItems save(InvoiceItems invoiceItems) {
        if (invoiceItems.getId() != null) {
            InvoiceItems original = invoiceItemsRepository.getReferenceById(invoiceItems.getPrimaryKey());
            BeanUtil.copyProperties(invoiceItems, original, CopyOptions.create().ignoreNullValue());
            invoiceItems = invoiceItemsRepository.save(original);
        } else {
            invoiceItems = invoiceItemsRepository.save(invoiceItems);
        }
        return invoiceItems;
    }

    public List<InvoiceItems> list() {
        return bqf.selectFrom(qInvoiceItems)
                .orderBy(qInvoiceItems.id.desc())
                .fetch();
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        invoiceItemsRepository.deleteById(primaryKey);
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qInvoiceItems)
                .where(qInvoiceItems.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qInvoiceItems)
                .where(qInvoiceItems.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return InvoiceItems.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<InvoiceItems> entityList = JSON.parseArray(json, InvoiceItems.class);
        for (InvoiceItems entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            invoiceItemsRepository.save(entity);
        }
    }
}

