package cn.tbforum.notif;
import cn.tbforum.domain.*; import cn.tbforum.repo.NotificationRepository; import cn.tbforum.security.JwtService; import cn.tbforum.repo.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service; import org.springframework.transaction.support.TransactionSynchronization; import org.springframework.transaction.support.TransactionSynchronizationManager; import org.springframework.web.socket.*; import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import java.util.*; import java.util.concurrent.*;

@Service
public class NotificationService extends TextWebSocketHandler {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NotificationService.class);
 private final NotificationRepository notifications; private final JwtService jwt; private final UserRepository users;
 private final StringRedisTemplate redis; private final boolean redisAvailable;
 private final Map<String,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
 private final MeterRegistry meterRegistry;
 private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=new com.fasterxml.jackson.databind.ObjectMapper();
 /** Redis Pub/Sub 频道：多实例间广播 WS 消息 */
 private static final String WS_CHANNEL="tbforum:ws:notify";
 public NotificationService(NotificationRepository n,JwtService j,UserRepository u,@Autowired(required=false) StringRedisTemplate r,@Autowired(required=false) RedisMessageListenerContainer container,@Autowired(required=false) MeterRegistry registry){
  notifications=n;jwt=j;users=u;redis=r;redisAvailable=r!=null;meterRegistry=registry;
  // 注册 WS 活跃连接数 gauge（本实例维度）
  if(meterRegistry!=null){
   meterRegistry.gauge("tbforum.ws.connections.active", java.util.Collections.emptyList(), sessions, s->s.values().stream().mapToInt(Set::size).sum());
  }
  // 注册订阅：收到 Redis 消息后转发到本地 session（多实例广播）
  if(redisAvailable&&container!=null){
   container.addMessageListener(new MessageListener(){
    @Override public void onMessage(Message message,byte[] pattern){
     try{var node=MAPPER.readTree(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8));sendWs(node.get("user").asText(),node.get("msg").asText());}catch(Exception e){log.warn("ws subscribe parse failed",e);}
    }
   },new PatternTopic(WS_CHANNEL));
  }
 }

 @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
 String user=authUser(session); if(user==null){session.close(CloseStatus.POLICY_VIOLATION);return;} sessions.computeIfAbsent(user,k->ConcurrentHashMap.newKeySet()).add(session);
 }
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
 String user=authUser(session); if(user!=null){var s=sessions.get(user);if(s!=null)s.remove(session);}
 }
 private String authUser(WebSocketSession session){
 // 从握手阶段 HandshakeInterceptor 存入的 attributes 读取已验证用户名
 Object user=session.getAttributes().get("ws-user");
 return user!=null?user.toString():null;
 }

 public void notify(User recipient,User actor,Notification.Kind kind,String text,Long postId,Long commentId){
 if(recipient==null||actor==null||recipient.id.equals(actor.id))return;
 Notification n=new Notification(); n.user=recipient; n.actor=actor; n.kind=kind; n.text=text; n.postId=postId; n.commentId=commentId;
 notifications.save(n);
 String msg;
 try{var obj=MAPPER.createObjectNode();obj.put("id",n.id);obj.put("kind",n.kind.name());obj.put("text",n.text);obj.put("actor",actor.username);if(postId!=null)obj.put("postId",postId);else obj.putNull("postId");if(commentId!=null)obj.put("commentId",commentId);else obj.putNull("commentId");obj.put("createdAt",n.createdAt.toString());msg=MAPPER.writeValueAsString(obj);}catch(Exception e){return;}
 // 事务提交后再发 WS，避免回滚后消息已发
 if(TransactionSynchronizationManager.isSynchronizationActive()){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){dispatch(recipient.username,msg);}});}else{dispatch(recipient.username,msg);}
 }
 /** 分发 WS 消息：本地发送 + Redis Pub/Sub 广播到其他实例 */
 private void dispatch(String username,String msg){
  sendWs(username,msg);
  if(redisAvailable){try{var obj=MAPPER.createObjectNode();obj.put("user",username);obj.put("msg",msg);redis.convertAndSend(WS_CHANNEL,MAPPER.writeValueAsString(obj));}catch(Exception e){log.warn("ws publish failed user={}",username,e);}}
 }
 private void sendWs(String username,String msg){var s=sessions.get(username);if(s!=null)for(var sess:s)if(sess.isOpen())try{sess.sendMessage(new TextMessage(msg));}catch(Exception e){log.warn("ws send failed user={}",username,e);}}

 public void notifyMentions(String content,User actor,Long postId,Long commentId){
 var m=java.util.regex.Pattern.compile("@([\\w\\u4e00-\\u9fa5]{2,40})").matcher(content);
 Set<String> seen=new HashSet<>();
 while(m.find()){String name=m.group(1);if(seen.add(name))users.findByUsername(name).ifPresent(u->notify(u,actor,Notification.Kind.MENTION,"在帖子中提到了你",postId,commentId));}
 }
}
