package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.UserImageStorageConfig;
import cn.gson.financial.kernel.model.repo.UserImageStorageConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 用户图片存储配置服务
 *
 * @author 开发者
 * @date 2025-01-XX
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserImageStorageConfigService extends AbsService {

    private final UserImageStorageConfigRepository repository;

    /**
     * 根据用户ID查询所有配置
     *
     * @param userId 用户ID
     * @return 配置列表
     */
    public List<UserImageStorageConfig> listByUserId(Long userId) {
        return repository.findAll().stream()
                .filter(config -> config.getUserId().equals(userId))
                .toList();
    }

    /**
     * 根据用户ID和存储类型查询配置
     *
     * @param userId      用户ID
     * @param storageType 存储类型
     * @return 配置信息
     */
    public Optional<UserImageStorageConfig> findByUserIdAndStorageType(Long userId, Integer storageType) {
        return repository.findByUserIdAndStorageType(userId, storageType);
    }

    /**
     * 根据ID查询配置
     *
     * @param id 配置ID
     * @return 配置信息
     */
    public Optional<UserImageStorageConfig> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * 保存或更新配置
     *
     * @param config 配置信息
     * @return 保存后的配置
     */
    @Transactional
    public UserImageStorageConfig save(UserImageStorageConfig config) {
        // 如果有ID，则按主键更新
        if (config.getId() != null) {
            Optional<UserImageStorageConfig> existing = repository.findById(config.getId());
            if (existing.isPresent()) {
                UserImageStorageConfig existingConfig = existing.get();
                existingConfig.setStorageRoot(config.getStorageRoot());
                existingConfig.setStorageType(config.getStorageType());
                existingConfig.setPurpose(config.getPurpose());
                return repository.save(existingConfig);
            }
        }

        // 【模式A】：每个用户 + 每种存储类型只保留一条记录
        // 没有ID时，先按 userId + storageType 查一条，如果存在则提示已存在，避免误覆盖
        Optional<UserImageStorageConfig> existing = repository.findByUserIdAndStorageType(
                config.getUserId(), config.getStorageType());

        if (existing.isPresent()) {
            throw new ServiceException("该存储类型已配置，请在列表中修改原有配置~");
        }

        // 不存在则新增
        return repository.save(config);
    }

    /**
     * 删除配置
     *
     * @param id 配置ID
     */
    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * 根据用户ID删除所有配置
     *
     * @param userId 用户ID
     */
    @Transactional
    public void deleteByUserId(Long userId) {
        List<UserImageStorageConfig> configs = listByUserId(userId);
        repository.deleteAll(configs);
    }
}

