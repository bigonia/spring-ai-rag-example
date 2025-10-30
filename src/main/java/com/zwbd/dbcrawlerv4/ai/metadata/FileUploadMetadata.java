package com.zwbd.dbcrawlerv4.ai.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/10/21 10:33
 * @Desc:
 */
@Data
public class FileUploadMetadata extends BaseMetadata {

    @JsonIgnore
    private String filePath;

//    @NotBlank(message = "原始文件名 (original_filename) 不能为空")
    private String originalFilename;

//    @NotNull(message = "文件大小 (file_size_bytes) 不能为空")
    private Long fileSizeBytes;

}
