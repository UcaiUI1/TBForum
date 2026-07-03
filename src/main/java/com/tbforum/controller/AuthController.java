package com.tbforum.controller;

import com.tbforum.model.dto.LoginRequestDTO;
import com.tbforum.model.dto.LoginResponseDTO;
import com.tbforum.model.dto.RegisterRequestDTO;
import com.tbforum.service.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDTO request) {
        try {
            String message = authService.register(request);
            return ResponseEntity.ok(new LoginResponseDTO(null, request.getUsername(), message));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new LoginResponseDTO(null, null, e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDTO request) {
        try {
            String token = authService.login(request);
            return ResponseEntity.ok(new LoginResponseDTO(token, request.getUsername(), "登录成功！"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(new LoginResponseDTO(null, null, "用户名或密码错误"));
        }
    }
}