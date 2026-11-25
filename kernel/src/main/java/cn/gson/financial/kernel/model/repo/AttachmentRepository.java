package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Attachment;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, PrimaryKey> {
}
