package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.ExportFile;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportFileRepository extends JpaRepository<ExportFile, PrimaryKey>{
} 