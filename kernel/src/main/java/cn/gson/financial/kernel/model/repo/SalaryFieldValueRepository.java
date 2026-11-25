package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.SalaryFieldValue;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-06 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.repo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-06</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
public interface SalaryFieldValueRepository extends JpaRepository<SalaryFieldValue, PrimaryKey> {
    List<SalaryFieldValue> findBySalaryId(Long salaryId);
}
