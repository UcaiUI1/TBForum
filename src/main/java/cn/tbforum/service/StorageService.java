package cn.tbforum.service;

import java.util.Optional;

/**
 * 文件存储服务抽象：统一本地磁盘与对象存储（MinIO/S3）的存储接口。
 * 当前为本地实现，未来可替换为 MinIO 实现而无需改动 Controller。
 */
public interface StorageService {
    /**
     * 存储文件并返回可访问 URL
     * @param data 文件字节数据
     * @param format 已验证的安全文件格式（仅小写字母和数字）
     * @param prefix 安全文件名前缀（仅字母、数字、下划线和连字符，如 avatar_1_）
     * @return 可访问的 URL 路径
     */
    String store(byte[] data, String format, String prefix);

    /**
     * 读取由该存储服务管理的 URL。实现必须拒绝目录穿越、符号链接和非受管路径。
     *
     * @param url store 返回的受管 URL
     * @return 文件存在且路径安全时返回内容，否则返回空
     */
    Optional<StoredFile> read(String url);

    /**
     * 删除由该存储服务管理的 URL。不存在的文件按删除成功处理。
     *
     * @param url store 返回的受管 URL
     * @return 删除完成或文件本就不存在时返回 true；路径非法或 I/O 失败时返回 false
     */
    boolean delete(String url);

    record StoredFile(byte[] data) {
        public StoredFile {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        public long sizeBytes() {
            return data.length;
        }
    }
}
