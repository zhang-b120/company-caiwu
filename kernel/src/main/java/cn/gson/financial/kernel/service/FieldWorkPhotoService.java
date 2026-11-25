package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.FieldWorkPhoto;
import cn.gson.financial.kernel.model.repo.FieldWorkPhotoRepository;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FN-07 外勤拍照归档 Service
 * <p>
 * 物理文件存放在 customize-config.base-path 下 field-work-photos/{accountSetsId}/{yyyy-MM}/ 子目录。
 * 文件名：{雪花id}.{原 suffix}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldWorkPhotoService {

    /** 子目录名（相对 base-path） */
    public static final String SUBDIR = "field-work-photos";

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 允许的图片 content-type 白名单 */
    public static final Set<String> ALLOWED_MIME_TYPES = new HashSet<>(List.of(
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/heif",
            "image/webp"
    ));

    private final FieldWorkPhotoRepository fieldWorkPhotoRepository;

    @Value("${customize-config.base-path:D:\\financialExcel}")
    private String basePath;

    /** 存储 root（绝对路径，base-path/field-work-photos） */
    public File storageRoot() {
        return new File(basePath, SUBDIR);
    }

    /** 校验 content-type 是否为白名单内的图片类型 */
    public static boolean isAllowedImage(String contentType) {
        if (StrUtil.isBlank(contentType)) return false;
        return ALLOWED_MIME_TYPES.contains(contentType.trim().toLowerCase());
    }

    /**
     * 保存上传文件 + 数据库元数据。
     * <p>
     * 在 service 内分配 ID 并把物理文件落到 {basePath}/field-work-photos/{accountSetsId}/{yyyy-MM}/{id}.{suffix}。
     */
    @Transactional(rollbackFor = Exception.class)
    public FieldWorkPhoto upload(MultipartFile file,
                                 Long accountSetsId,
                                 Long customerId,
                                 Long taskId,
                                 String gps,
                                 LocalDateTime capturedAt,
                                 String description,
                                 Long uploadedBy) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        if (accountSetsId == null) {
            throw new IllegalArgumentException("accountSetsId 不能为空");
        }
        if (!isAllowedImage(file.getContentType())) {
            throw new IllegalArgumentException("仅支持图片格式 (image/jpeg, image/png, image/heic, image/webp)，实际：" + file.getContentType());
        }

        // 1. 先 save 一次拿 ID（保证 storage_name 用 ID 命名）
        FieldWorkPhoto entity = new FieldWorkPhoto();
        entity.setAccountSetsId(accountSetsId);
        entity.setCustomerId(customerId);
        entity.setTaskId(taskId);
        entity.setFileName(file.getOriginalFilename());
        entity.setMimeType(file.getContentType());
        entity.setFileSize(file.getSize());
        entity.setCapturedAt(capturedAt);
        entity.setDescription(description);
        entity.setUploadedBy(uploadedBy);
        // GPS 解析
        BigDecimal[] latLng = parseGps(gps);
        if (latLng != null) {
            entity.setGpsLat(latLng[0]);
            entity.setGpsLng(latLng[1]);
        }
        String suffix = FileNameUtil.getSuffix(file.getOriginalFilename());
        if (StrUtil.isBlank(suffix)) {
            suffix = mimeToSuffix(file.getContentType());
        }
        entity.setFileSuffix(suffix);

        // 占位 storagePath / storageName，先 save 拿 ID
        entity.setStorageName("__pending__");
        entity.setStoragePath("__pending__");
        entity = fieldWorkPhotoRepository.save(entity);

        // 2. 用 ID 决定物理文件名
        String storageName = entity.getId() + (StrUtil.isBlank(suffix) ? "" : "." + suffix);
        String monthDir = (capturedAt != null ? capturedAt : LocalDateTime.now()).format(MONTH_FMT);
        String relativePath = SUBDIR + "/" + accountSetsId + "/" + monthDir + "/" + storageName;

        File targetDir = new File(new File(basePath, SUBDIR), accountSetsId + "/" + monthDir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建存储目录：" + targetDir.getAbsolutePath());
        }
        File target = new File(targetDir, storageName);

        // 3. 落盘
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        // 4. 更新数据库 path
        entity.setStorageName(storageName);
        entity.setStoragePath(relativePath);
        return fieldWorkPhotoRepository.save(entity);
    }

    public Optional<FieldWorkPhoto> findById(Long id) {
        return fieldWorkPhotoRepository.findById(id);
    }

    /**
     * 列表查询（按 accountSetsId 必填 + customerId / taskId 可选过滤）。
     */
    public Page<FieldWorkPhoto> list(Long accountSetsId,
                                     Long customerId,
                                     Long taskId,
                                     int page,
                                     int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 200) size = 200;

        Specification<FieldWorkPhoto> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (accountSetsId != null) {
                predicates.add(cb.equal(root.get("accountSetsId"), accountSetsId));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (taskId != null) {
                predicates.add(cb.equal(root.get("taskId"), taskId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return fieldWorkPhotoRepository.findAll(spec, pr);
    }

    /**
     * 把相对路径解析为绝对 File，并做安全校验：必须落在 storageRoot 下。
     */
    public File resolveAbsoluteFile(FieldWorkPhoto photo) throws IOException {
        if (photo == null || StrUtil.isBlank(photo.getStoragePath())) {
            return null;
        }
        File root = new File(basePath).getCanonicalFile();
        // storagePath 形如 "field-work-photos/{accountSetsId}/{yyyy-MM}/{name}"
        File f = new File(root, photo.getStoragePath()).getCanonicalFile();
        String rootCanonical = root.getCanonicalPath();
        String fileCanonical = f.getCanonicalPath();
        if (!fileCanonical.startsWith(rootCanonical)) {
            throw new SecurityException("文件路径越界：" + photo.getStoragePath());
        }
        return f;
    }

    /**
     * 解析 "lat,lng" 字符串为 BigDecimal[2]。失败/格式不对返回 null（GPS 字段非必填，宽松处理）。
     */
    public static BigDecimal[] parseGps(String gps) {
        if (StrUtil.isBlank(gps)) return null;
        String[] parts = gps.trim().split(",");
        if (parts.length != 2) return null;
        try {
            BigDecimal lat = new BigDecimal(parts[0].trim());
            BigDecimal lng = new BigDecimal(parts[1].trim());
            // 极简范围校验
            if (lat.doubleValue() < -90 || lat.doubleValue() > 90) return null;
            if (lng.doubleValue() < -180 || lng.doubleValue() > 180) return null;
            return new BigDecimal[]{lat, lng};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String mimeToSuffix(String mime) {
        if (StrUtil.isBlank(mime)) return null;
        return switch (mime.trim().toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/heic" -> "heic";
            case "image/heif" -> "heif";
            case "image/webp" -> "webp";
            default -> null;
        };
    }
}
