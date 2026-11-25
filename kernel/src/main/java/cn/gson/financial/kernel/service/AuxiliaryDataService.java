package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助项目查询</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface AuxiliaryDataService {

    void clear(Long categoryId, Long accountSetsId);

    AcDetails save(AcDetails entity);

    default List<? extends AcDetails> loadData(Long accountSetsId) {
        return null;
    }

    default List<? extends AcDetails> loadData(Long accountSetsId, Long categoryId) {
        return null;
    }

    List<? extends AcDetails> loadData(Long accountSetsId, Long categoryId, String filter);

    AcDetails loadByCode(Long accountSetsId, Long categoryId, String code);

    String loadMaxCode(Long accountSetsId, Long categoryId);

    AcDetails loadByName(Long accountSetsId, Long categoryId, String name);

    Optional<AcDetails> loadById(Long accountSetsId, Long categoryId, Long id);

    default PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Page page) {
        return null;
    }

    default PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Page page, String filter) {
        return null;
    }

    PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Long categoryId, Page page);

    PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Long categoryId, Page page, String filter);

    default Map<Long, ? extends AcDetails> loadDataByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        return null;
    }

    Map<Long, ? extends AcDetails> loadDataByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList);

    default List<? extends AcDetails> loadListByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        return null;
    }

    List<? extends AcDetails> loadListByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList);

    Map<String, Integer> importData(Long accountSetsId, Long categoryId, AccountingCategoryType categoryType, Map<String, AcDetails> detailsMap);
}
