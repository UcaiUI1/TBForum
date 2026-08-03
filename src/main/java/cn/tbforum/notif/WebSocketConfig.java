package cn.tbforum.notif;
import cn.tbforum.security.JwtService; import org.springframework.context.annotation.*; import org.springframework.http.server.ServerHttpRequest; import org.springframework.http.server.ServerHttpResponse; import org.springframework.web.socket.WebSocketHandler; import org.springframework.web.socket.config.annotation.*; import org.springframework.web.socket.server.HandshakeInterceptor; import java.util.*;
@Configuration @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
 private final NotificationService handler; private final JwtService jwt;
 public WebSocketConfig(NotificationService h,JwtService j){handler=h;jwt=j;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry r){
  r.addHandler(handler,"/ws/notifications").setAllowedOriginPatterns("http://localhost:*","http://127.0.0.1:*","http://192.168.*:*","http://10.*:*","http://172.16.*:*").addInterceptors(new HandshakeInterceptor(){
   @Override public boolean beforeHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Map<String,Object> attrs){
    List<String> protos=req.getHeaders().get("Sec-WebSocket-Protocol");
    if(protos!=null)for(String p:protos)for(String v:p.split(",")){String t=v.trim();if(t.startsWith("bearer.")&&t.length()>7)try{attrs.put("ws-user",jwt.subject(t.substring(7)));break;}catch(Exception e){}}
    return true;
   }
   @Override public void afterHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Exception ex){}
  });
 }
}
