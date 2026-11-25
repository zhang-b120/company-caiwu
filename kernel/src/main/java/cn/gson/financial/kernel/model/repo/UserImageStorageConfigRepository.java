package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.UserImageStorageConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserImageStorageConfigRepository extends JpaRepository<UserImageStorageConfig, Long> {
    /**
     * 根据用户ID和存储类型查询配置
     * @param userId 用户ID
     * @param storageType 存储类型: 1-本地, 2-OSS, 3-CDN
     * @return 配置信息
     */
    Optional<UserImageStorageConfig> findByUserIdAndStorageType(Long userId, Integer storageType);
}

