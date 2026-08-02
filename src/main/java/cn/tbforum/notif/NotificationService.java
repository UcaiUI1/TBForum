package cn.tbforum.notif;
import cn.tbforum.domain.*; import cn.tbforum.repo.Repositories.Notifications; import cn.tbforum.security.JwtService; import cn.tbforum.repo.Repositories.Users;
import org.springframework.stereotype.Service; import org.springframework.web.socket.*; import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import java.util.*; import java.util.concurrent.*;

@Service
public class NotificationService extends TextWebSocketHandler {
 private final Notifications notifications; private final JwtService jwt; private final Users users;
 private final Map<String,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
 public NotificationService(Notifications n,JwtService j,Users u){notifications=n;jwt=j;users=u;}

 @Override public void afterConnectionEstablished(WebSocketSession session){
 String user=authUser(session); if(user!=null) sessions.computeIfAbsent(user,k->ConcurrentHashMap.newKeySet()).add(session);
 }
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){
 String user=authUser(session); if(user!=null){var s=sessions.get(user);if(s!=null)s.remove(session);}
 }
 private String authUser(WebSocketSession session){
 String q=session.getUri().getQuery(); if(q==null)return null;
 for(String p:q.split("&")){if(p.startsWith("token=")){try{return jwt.subject(p.substring(6));}catch(Exception e){return null;}}}
 return null;
 }

 public void notify(User recipient,User actor,Notification.Kind kind,String text,Long postId,Long commentId){
 if(recipient==null||actor==null||recipient.id.equals(actor.id))return;
 Notification n=new Notification(); n.user=recipient; n.actor=actor; n.kind=kind; n.text=text; n.postId=postId; n.commentId=commentId;
 notifications.save(n);
 String msg=String.format("{\"id\":%d,\"kind\":\"%s\",\"text\":\"%s\",\"actor\":\"%s\",\"postId\":%s,\"commentId\":%s,\"createdAt\":\"%s\"}",n.id,n.kind,n.text,actor.username,postId!=null?postId:"null",commentId!=null?commentId:"null",n.createdAt);
 var s=sessions.get(recipient.username); if(s!=null)for(var sess:s)if(sess.isOpen())try{sess.sendMessage(new TextMessage(msg));}catch(Exception ignored){}
 }

 public void notifyMentions(String content,User actor,Long postId,Long commentId){
 var m=java.util.regex.Pattern.compile("@(\\w{3,40})").matcher(content);
 Set<String> seen=new HashSet<>();
 while(m.find()){String name=m.group(1);if(seen.add(name))users.findByUsername(name).ifPresent(u->notify(u,actor,Notification.Kind.MENTION,"在帖子中提到了你",postId,commentId));}
 }
}
