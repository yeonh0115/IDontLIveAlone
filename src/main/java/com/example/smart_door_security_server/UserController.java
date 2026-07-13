package com.example.smart_door_security_server;

import com.example.smart_door_security_server.User;
import com.example.smart_door_security_server.UserRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    @PostMapping("/update-profile")
    public ResponseEntity<User> updateProfile(@RequestBody UpdateProfileRequest request) {
        Optional<User> userOptional = userRepository.findByUserId(request.getUserId());
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        User user = userOptional.get();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setAvatar(request.getAvatar()); // 아바타 정보 저장

        userRepository.save(user); // SQL 테이블에 반영
        return ResponseEntity.ok(user);
    }

    @Getter
    @Setter
    public static class UpdateProfileRequest {
        private String userId;
        private String username;
        private String email;
        private String avatar;
    }


    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserInfo(@PathVariable("userId") String userId) {
        return userRepository.findByUserId(userId)
                .map(user -> {
                    UserResponse response = new UserResponse();
                    response.setUsername(user.getUsername());
                    response.setEmail(user.getEmail());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @Getter
    @Setter
    public static class UserResponse {
        private String username;
        private String email;
    }



    @GetMapping("/door-passwords")
    public ResponseEntity<String> getAllDoorPasswords() {
        List<User> allUsers = userRepository.findAll();
        String combinedPasswords = allUsers.stream()
                .map(User::getDoorPassword)
                .filter(pw -> pw != null && !pw.isEmpty())
                .collect(Collectors.joining(","));
        return ResponseEntity.ok(combinedPasswords);
    }

    @PostMapping("/sign-up")
    public ResponseEntity<String> signUp(@RequestBody SignUpRequest signUpRequest) {
        if (userRepository.findByUserId(signUpRequest.getId()).isPresent()) {
            return ResponseEntity.status(409).body("이미 존재하는 아이디입니다.");
        }

        User user = new User();
        user.setUserId(signUpRequest.getId());
        user.setPasswordHash(signUpRequest.getPw());
        user.setUsername(signUpRequest.getName());
        user.setPhone(signUpRequest.getPhone());
        user.setEmail(signUpRequest.getEmail());

        userRepository.save(user);
        return ResponseEntity.ok("성공");
    }

    @PostMapping("/login")
    public ResponseEntity<User> login(@RequestBody LoginRequest request) {
        System.out.println("ID: " + request.getId() + ", PW: " + request.getPw());
        Optional<User> userBox = userRepository.findByUserId(request.getId());

        if (userBox.isPresent()) {
            User user = userBox.get();
            if (user.getPasswordHash().equals(request.getPw())) {
                return ResponseEntity.ok(user);
            } else {
                return ResponseEntity.status(401).build();
            }
        }
        return ResponseEntity.status(401).build();
    }

    @PostMapping("/update-door-lock")
    public ResponseEntity<String> updateDoorLock(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String currentAccountPw = request.get("currentAccountPw");
        String newDoorPw = request.get("newDoorPw");

        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        User user = userOptional.get();
        if (!user.getPasswordHash().equals(currentAccountPw)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Wrong password");
        }

        user.setDoorPassword(newDoorPw);
        userRepository.save(user);
        return ResponseEntity.ok("success");
    }

    @Getter
    @Setter
    public static class LoginRequest {
        private String id;
        private String pw;
    }

    @Getter
    @Setter
    public static class SignUpRequest {
        private String id;
        private String pw;
        private String name;
        private String phone;
        private String email;
    }
}
