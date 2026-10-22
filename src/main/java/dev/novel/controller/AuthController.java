package dev.novel.controller;

import dev.novel.service.UserService;
import dev.novel.dto.ApiRequests.Registration;
import dev.novel.dto.response.CommonResponses.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService users;
    public AuthController(UserService users) { this.users=users; }
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken());
    }

    @GetMapping("/me")
    public UserResponse me(Principal principal) {
        return new UserResponse(principal.getName());
    }

    @PostMapping("/register")
    public ActionResponse register(@RequestBody Registration input) {
        users.register(input.username(), input.password());
        return ActionResponse.SUCCESS;
    }
}
