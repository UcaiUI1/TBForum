package cn.tbforum.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * 集中管理应用配置参数，消除散落代码中的魔法数字。
 * 通过 application.yml 的 app.* 前缀绑定。
 */
@Configuration
@ConfigurationProperties(prefix="app")
public class AppProperties {
 private Upload upload=new Upload();
 private Tag tag=new Tag();
 private Moderator moderator=new Moderator();
 private Cache cache=new Cache();
 private View view=new View();

 public Upload getUpload(){return upload;}
 public Tag getTag(){return tag;}
 public Moderator getModerator(){return moderator;}
 public Cache getCache(){return cache;}
 public View getView(){return view;}

 public void setUpload(Upload u){this.upload=u;}
 public void setTag(Tag t){this.tag=t;}
 public void setModerator(Moderator m){this.moderator=m;}
 public void setCache(Cache c){this.cache=c;}
 public void setView(View v){this.view=v;}

 /** 上传限制 */
 public static class Upload{
  private long maxFileSize=2*1024*1024; // 2MB
  public long getMaxFileSize(){return maxFileSize;}
  public void setMaxFileSize(long v){this.maxFileSize=v;}
 }
 /** 标签限制 */
 public static class Tag{
  private int maxPerPost=5;
  private int minLength=2;
  private int maxLength=30;
  public int getMaxPerPost(){return maxPerPost;}
  public int getMinLength(){return minLength;}
  public int getMaxLength(){return maxLength;}
  public void setMaxPerPost(int v){this.maxPerPost=v;}
  public void setMinLength(int v){this.minLength=v;}
  public void setMaxLength(int v){this.maxLength=v;}
 }
 /** 版主限制 */
 public static class Moderator{
  private int maxPerForum=2;
  public int getMaxPerForum(){return maxPerForum;}
  public void setMaxPerForum(int v){this.maxPerForum=v;}
 }
 /** 缓存 TTL */
 public static class Cache{
  private Duration feedTtl=Duration.ofMinutes(2);
  private Duration forumListTtl=Duration.ofMinutes(10);
  public Duration getFeedTtl(){return feedTtl;}
  public Duration getForumListTtl(){return forumListTtl;}
  public void setFeedTtl(Duration v){this.feedTtl=v;}
  public void setForumListTtl(Duration v){this.forumListTtl=v;}
 }
 /** 浏览数防刷 */
 public static class View{
  private Duration dedupWindow=Duration.ofMinutes(5);
  public Duration getDedupWindow(){return dedupWindow;}
  public void setDedupWindow(Duration v){this.dedupWindow=v;}
 }
}
