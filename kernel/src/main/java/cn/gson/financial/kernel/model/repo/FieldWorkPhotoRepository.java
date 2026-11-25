package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FieldWorkPhoto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * FN-07 外勤拍照归档 Repository
 */
public interface FieldWorkPhotoRepository
        extends JpaRepository<FieldWorkPhoto, Long>, JpaSpecificationExecutor<FieldWorkPhoto> {

    Page<FieldWorkPhoto> findAllByAccountSetsIdOrderByCreatedAtDesc(Long accountSetsId, Pageable pageable);
}
