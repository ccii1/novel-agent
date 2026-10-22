package dev.novel.controller;

import dev.novel.service.UserService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService users;
    public AuthController(UserService users) { this.users=users; }
    public record Registration(String username,String password) {}
    @GetMapping("/csrf") public Object csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @GetMapping("/me") public Object me(Principal principal) { return Map.of("username",principal.getName()); }
    @PostMapping("/register") public Object register(@RequestBody Registration input) {
        users.register(input.username(),input.password());return Map.of("ok",true);
    }
}
