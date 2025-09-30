package com.zwbd.dbcrawlerv4.ai.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/9/18 9:04
 * @Desc:
 */
@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);
    private final Path fileStorageLocation;

    public FileStorageService() {
        // 定义一个临时目录用于存放上传的文件
        // 在生产环境中，这应该是一个可配置的路径
        this.fileStorageLocation = Paths.get("upload-dir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new RuntimeException("Could not create the directory where the uploaded files will be stored.", ex);
        }
    }

    /**
     * 存储上传的文件。
     *
     * @param file 上传的文件对象
     * @return 存储后的文件路径
     */
    public Path store(MultipartFile file) {
        // 清理文件名
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());

        try {
            if (originalFileName.contains("..")) {
                throw new RuntimeException("Sorry! Filename contains invalid path sequence " + originalFileName);
            }

            // 为了避免文件名冲突，我们可以在文件名中加入随机字符串
            String fileExtension = "";
            int i = originalFileName.lastIndexOf('.');
            if (i > 0) {
                fileExtension = originalFileName.substring(i);
            }
            String newFileName = UUID.randomUUID().toString() + fileExtension;


            Path targetLocation = this.fileStorageLocation.resolve(newFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            logger.info("Stored file {} as {}", originalFileName, newFileName);
            return targetLocation;

        } catch (IOException ex) {
            logger.error("Could not store file {}. Please try again!", originalFileName, ex);
            throw new RuntimeException("Could not store file " + originalFileName + ". Please try again!", ex);
        }
    }
}
