package cn.tbforum.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException; import java.nio.file.Files; import java.nio.file.Path; import java.nio.file.Paths;
/**
 * 本地磁盘存储实现：文件写入 uploads/ 目录，返回 /uploads/filename 路径。
 * 通过 app.storage.type=local 激活（默认），未来可扩展 app.storage.type=minio 切换。
 */
@Service
@ConditionalOnProperty(name="app.storage.type",havingValue="local",matchIfMissing=true)
public class LocalStorageService implements StorageService {
 private final Path root;
 public LocalStorageService(@Value("${app.storage.local.dir:uploads}") String dir){
  this.root=Paths.get(dir);
  try{Files.createDirectories(root);}catch(IOException e){throw new IllegalStateException("无法创建上传目录: "+root,e);}
 }
 @Override public String store(byte[] data,String format,String prefix){
  String filename=prefix+System.currentTimeMillis()+"."+format;
  try{Files.write(root.resolve(filename),data);return "/uploads/"+filename;}catch(IOException e){throw UserService.bad("文件存储失败");}
 }
}
