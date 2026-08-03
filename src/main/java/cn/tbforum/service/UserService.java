package cn.tbforum.service;
import cn.tbforum.domain.*; import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.UserRepository; import cn.tbforum.repo.FollowRepository; import cn.tbforum.repo.PostRepository;
import cn.tbforum.security.JwtService; import cn.tbforum.notif.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*; import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class UserService {
 private final UserRepository users; private final FollowRepository follows; private final PostRepository posts; private final PasswordEncoder encoder; private final JwtService jwt; private final NotificationService notif; private final HttpServletRequest request;
 public UserService(UserRepository u,FollowRepository f,PostRepository p,PasswordEncoder e,JwtService j,NotificationService n,HttpServletRequest r){users=u;follows=f;posts=p;encoder=e;jwt=j;notif=n;request=r;}
 public User current(){String name=SecurityContextHolder.getContext().getAuthentication()==null?null:SecurityContextHolder.getContext().getAuthentication().getName();if(name==null||"anonymousUser".equals(name))throw unauthorized("请先登录");return users.findByUsername(name).orElseThrow(()->unauthorized("登录已失效"));}
 public User currentOrNull(){String name=SecurityContextHolder.getContext().getAuthentication()==null?null:SecurityContextHolder.getContext().getAuthentication().getName();if(name==null||"anonymousUser".equals(name))return null;return users.findByUsername(name).orElse(null);}
 /** 获取客户端真实 IP（处理反向代理头） */
 public String clientIp(){String xff=request.getHeader("X-Forwarded-For");if(xff!=null&&!xff.isBlank()){int comma=xff.indexOf(',');return comma>=0?xff.substring(0,comma).trim():xff.trim();}String real=request.getHeader("X-Real-IP");if(real!=null&&!real.isBlank())return real;return request.getRemoteAddr();}
 public ResponseEntity<Token> register(Credentials input){if(users.findByUsername(input.username()).isPresent())throw bad("用户名已存在");User u=new User();u.username=input.username();u.passwordHash=encoder.encode(input.password());try{return ResponseEntity.status(201).body(new Token(jwt.issue(users.save(u).username),u.username));}catch(DataIntegrityViolationException e){throw bad("用户名已存在");}}
 public Token login(Credentials input){User u=users.findByUsername(input.username()).orElseThrow(()->unauthorized("用户名或密码错误"));if(u.deleted)throw unauthorized("该账号已注销");if(!encoder.matches(input.password(),u.passwordHash))throw unauthorized("用户名或密码错误");return new Token(jwt.issue(u.username),u.username);}
 public Token refresh(){User u=current();return new Token(jwt.issue(u.username),u.username);}
 public UserView me(){return UserView.of(current());}
 public java.util.Optional<User> findByUsername(String username){return users.findByUsername(username);}
 @Transactional public ProfileUpdateView updateProfile(ProfileInput input){User u=current();boolean renamed=false;if(input.username()!=null&&!input.username().equals(u.username)){if(users.findByUsername(input.username()).isPresent())throw bad("用户名已存在");u.username=input.username();renamed=true;}if(input.avatarUrl()!=null)u.avatarUrl=input.avatarUrl().isEmpty()?null:input.avatarUrl();if(input.bio()!=null)u.bio=input.bio().isEmpty()?null:input.bio();if(input.signature()!=null)u.signature=input.signature().isEmpty()?null:input.signature();try{users.save(u);}catch(DataIntegrityViolationException e){throw bad("用户名已存在");}return new ProfileUpdateView(renamed?jwt.issue(u.username):null,UserView.of(u));}
 public UserView verify(){User u=current();u.verified=true;return UserView.of(users.save(u));}
 /** 注销账号：软删用户，清空个人资料，帖子保留但作者信息不再可查 */
 @Transactional public ResponseEntity<Void> deleteAccount(){User u=current();u.deleted=true;u.bio=null;u.signature=null;u.avatarUrl=null;users.save(u);return ResponseEntity.noContent().build();}
 @Transactional public ResponseEntity<Void> follow(String username){User u=current();User target=users.findByUsername(username).orElseThrow(()->notFound("用户不存在"));if(target.deleted)throw notFound("用户不存在");if(u.id.equals(target.id))throw bad("不能关注自己");if(follows.findByFollowerAndFollowee(u,target).isEmpty()){Follow f=new Follow();f.follower=u;f.followee=target;try{follows.save(f);notif.notify(target,u,Notification.Kind.FOLLOW,"关注了你",null,null);}catch(DataIntegrityViolationException e){/* 并发关注，幂等忽略 */}}return ResponseEntity.status(201).build();}
 @Transactional public ResponseEntity<Void> unfollow(String username){User u=current();User target=users.findByUsername(username).orElseThrow(()->notFound("用户不存在"));follows.deleteByFollowerAndFollowee(u,target);return ResponseEntity.noContent().build();}
 public List<UserView> followers(String username){User target=users.findByUsername(username).orElseThrow(()->notFound("用户不存在"));return follows.findByFolloweeOrderByCreatedAtDesc(target).stream().map(f->UserView.of(f.follower)).toList();}
 public List<UserView> following(String username){User target=users.findByUsername(username).orElseThrow(()->notFound("用户不存在"));return follows.findByFolloweeOrderByCreatedAtDesc(target).stream().map(f->UserView.of(f.followee)).toList();}
 public UserDetailView userDetail(String username){User target=users.findByUsername(username).orElseThrow(()->notFound("用户不存在"));return UserDetailView.of(target,currentOrNull(),follows);}
 public List<UserView> searchUsers(String q){return users.findTop10ByUsernameContainingIgnoreCase(q).stream().map(UserView::of).toList();}
 public static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}public static ResponseStatusException unauthorized(String m){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,m);}public static ResponseStatusException forbidden(String m){return new ResponseStatusException(HttpStatus.FORBIDDEN,m);}public static ResponseStatusException notFound(String m){return new ResponseStatusException(HttpStatus.NOT_FOUND,m);}
}
