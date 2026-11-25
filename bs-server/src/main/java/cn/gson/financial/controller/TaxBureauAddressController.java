package cn.gson.financial.controller;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.QTaxBureauAddress;
import cn.gson.financial.kernel.model.entity.TaxBureauAddress;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.TaxBureauAddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/taxBureauAddressController")
public class TaxBureauAddressController{
    @Autowired
    public TaxBureauAddressService taxBureauAddressService;
    @GetMapping("/getTaxBureauAddress")
    public JsonResult getTaxBureauAddress() {
        List<TaxBureauAddress> taxBureauAddresses =taxBureauAddressService.getTaxBureauAddress();
        return JsonResult.successful(taxBureauAddresses);
    }
    @GetMapping("/getTaxBureauAddressByCity")
    public JsonResult getTaxBureauAddressByCity(String city) {
        TaxBureauAddress taxBureauAddress = taxBureauAddressService.getTaxBureauAddressByCity(city);
        return JsonResult.successful(taxBureauAddress);
    }
    @GetMapping("/getTaxBureauAddressByPinYin")
    public JsonResult getTaxBureauAddressByPinYin(String pinYin) {
        TaxBureauAddress taxBureauAddress = taxBureauAddressService.getTaxBureauAddressByPinYin(pinYin);
        return JsonResult.successful(taxBureauAddress);
    }
}
