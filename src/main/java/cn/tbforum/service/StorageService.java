package cn.tbforum.service;
/**
 * 文件存储服务抽象：统一本地磁盘与对象存储（MinIO/S3）的存储接口。
 * 当前为本地实现，未来可替换为 MinIO 实现而无需改动 Controller。
 */
public interface StorageService {
 /**
  * 存储文件并返回可访问 URL
  * @param data 文件字节数据
  * @param format 文件格式（png/jpg/gif/webp）
  * @param prefix 文件名前缀（如 avatar_1_）
  * @return 可访问的 URL 路径
  */
 String store(byte[] data,String format,String prefix);
}
