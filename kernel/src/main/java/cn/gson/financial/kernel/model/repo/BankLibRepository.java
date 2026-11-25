package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BankLib;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankLibRepository extends JpaRepository<BankLib, PrimaryKey>{

    java.util.Optional<BankLib> findFirstByBankNameAndIsDelete(String bankName, Integer isDelete);

    java.util.Optional<BankLib> findFirstByBankCodeAndIsDelete(String bankCode, Integer isDelete);
} 