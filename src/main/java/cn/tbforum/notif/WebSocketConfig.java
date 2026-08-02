package cn.tbforum.notif;
import org.springframework.context.annotation.*; import org.springframework.web.socket.config.annotation.*;
@Configuration @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
 private final NotificationService handler;
 public WebSocketConfig(NotificationService h){handler=h;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry r){
 r.addHandler(handler,"/ws/notifications").setAllowedOrigins("*");
 }
}
