package cn.tbforum.controller;
import cn.tbforum.config.AppProperties; import cn.tbforum.domain.User; import cn.tbforum.service.StorageService; import cn.tbforum.service.UserService;
import org.springframework.validation.annotation.Validated; import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*; import java.awt.image.BufferedImage; import java.util.Map;

@RestController @Validated @RequestMapping("/api") public class UploadController {
 private final UserService userService; private final StorageService storage; private final AppProperties props;
 public UploadController(UserService u,StorageService s,AppProperties p){userService=u;storage=s;props=p;}
 @PostMapping("/upload") public Map<String,String> upload(@RequestParam("file") MultipartFile file) throws IOException {
  User u=userService.current();
  if(file.isEmpty())throw UserService.bad("文件不能为空");
  if(file.getSize()>props.getUpload().getMaxFileSize())throw UserService.bad("文件不能超过2MB");
  byte[] data=file.getBytes();
  String fmt=detectImageFormat(data);
  if(fmt==null)throw UserService.bad("仅支持 png/jpg/gif/webp 格式");
  String prefix="avatar_"+u.id+"_";
  byte[] toStore;
  if("webp".equals(fmt)){toStore=data;}
  else{
   BufferedImage img;
   try{img=javax.imageio.ImageIO.read(new ByteArrayInputStream(data));}catch(IOException e){throw UserService.bad("文件损坏或非有效图片");}
   if(img==null)throw UserService.bad("文件损坏或非有效图片");
   ByteArrayOutputStream bos=new ByteArrayOutputStream();
   try{if(!javax.imageio.ImageIO.write(img,fmt,bos))throw UserService.bad("图片重编码失败");}catch(IOException e){throw UserService.bad("图片重编码失败");}
   toStore=bos.toByteArray();
  }
  String url=storage.store(toStore,fmt,prefix);
  return Map.of("url",url);
 }
 /** 通过文件头魔数判定真实图片格式，避免信任客户端 Content-Type。 */
 private static String detectImageFormat(byte[] b){if(b==null||b.length<12)return null;if((b[0]&0xFF)==0x89&&b[1]==0x50&&b[2]==0x4E&&b[3]==0x47)return "png";if((b[0]&0xFF)==0xFF&&(b[1]&0xFF)==0xD8&&(b[2]&0xFF)==0xFF)return "jpg";if(b[0]==0x47&&b[1]==0x49&&b[2]==0x46&&b[3]==0x38)return "gif";if(b[0]==0x52&&b[1]==0x49&&b[2]==0x46&&b[3]==0x46&&b[8]==0x57&&b[9]==0x45&&b[10]==0x42&&b[11]==0x50)return "webp";return null;}
}
