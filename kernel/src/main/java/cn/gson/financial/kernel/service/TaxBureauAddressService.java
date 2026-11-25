package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.QTaxBureauAddress;
import cn.gson.financial.kernel.model.entity.TaxBureauAddress;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaxBureauAddressService extends AbsService {
    private final QTaxBureauAddress qTaxBureauAddress = QTaxBureauAddress.taxBureauAddress;

    /**
     * 获取所有税局地址
     * @return
     */
    public List<TaxBureauAddress> getTaxBureauAddress() {
        return jqf.selectFrom(qTaxBureauAddress).fetch();
    }

    /**
     *  根据城市获取税局地址
     * @param city
     * @return
     */
    public TaxBureauAddress getTaxBureauAddressByCity(String city) {
        String prefix = city.substring(0, Math.min(2, city.length()));
        return jqf.selectFrom(qTaxBureauAddress)
                .where(qTaxBureauAddress.city.startsWith(prefix))
                .fetchFirst();
    }

    /**
     *  根据拼音获取税局地址
     * @param pinYin
     * @return
     */
    public TaxBureauAddress getTaxBureauAddressByPinYin(String pinYin) {
        return jqf.selectFrom(qTaxBureauAddress).where(qTaxBureauAddress.pinYin.eq(pinYin)).fetchOne();
    }

}
