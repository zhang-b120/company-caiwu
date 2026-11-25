package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.ExcelMappingTemplate;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExcelMappingTemplateRepository extends JpaRepository<ExcelMappingTemplate, PrimaryKey>{
} 