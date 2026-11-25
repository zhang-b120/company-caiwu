package cn.gson.financial.loginStatusVerification;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@Data
@Service
public class LoginStatusVerification {

    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;
    private QAccountSets qAccountSets = QAccountSets.accountSets;

    public List<Map<String,String>> getPrivateAccountSets(Map<String, String> UserInfo) {
        List<Map<String,String>> accountSetsList = new ArrayList<>();
        bqf.selectFrom(qAccountSets).select(qAccountSets.province,qAccountSets.electricityTaxPhone,qAccountSets.count())
                   .groupBy(qAccountSets.province,qAccountSets.electricityTaxPhone)
                       .fetch().forEach(i->{
                       HashMap<String,String> accountSet = new HashMap<>();
                       accountSet.put("accountsCount",i.get(qAccountSets.count()).toString());
                       accountSet.put("province",i.get(qAccountSets.province));
                       accountSet.put("electricityTaxPhone",i.get(qAccountSets.electricityTaxPhone));
                       accountSet.put("loginStatus","offLine");
                       accountSetsList.add(accountSet);
                   });
        return accountSetsList;
    }


}
