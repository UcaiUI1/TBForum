package cn.tbforum.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// History API 路由的 SPA 回退：所有前端路径都交由 index.html 渲染
@Controller
public class SpaController {
    @GetMapping({"/global", "/profile", "/search", "/forum/**", "/tag/**", "/post/**", "/u/**"})
    public String spa() {
        return "forward:/index.html";
    }
}
