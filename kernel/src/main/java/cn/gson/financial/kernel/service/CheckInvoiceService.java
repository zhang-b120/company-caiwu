package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.Invoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckInvoiceService extends AbsService{
    public Long updateByInvoiceNumber(Invoice record) {
         lazyDao.executeSql("updateByInvoiceNumber",record);
        return null;
    }
}

