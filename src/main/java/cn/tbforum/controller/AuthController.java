package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api") public class AuthController {
 private final UserService userService;
 public AuthController(UserService u){userService=u;}
 @PostMapping("/auth/register") public ResponseEntity<Token> register(@Valid @RequestBody Credentials input){return userService.register(input);}
 @PostMapping("/auth/login") public Token login(@Valid @RequestBody Credentials input){return userService.login(input);}
 @GetMapping("/auth/me") public UserView me(){return userService.me();}
 @PostMapping("/auth/refresh") public Token refresh(){return userService.refresh();}
 @PatchMapping("/auth/me/verified") public UserView verify(){return userService.verify();}
 @PatchMapping("/auth/me") public ProfileUpdateView updateProfile(@Valid @RequestBody ProfileInput input){return userService.updateProfile(input);}
 @DeleteMapping("/auth/me") public ResponseEntity<Void> deleteAccount(){return userService.deleteAccount();}
}
