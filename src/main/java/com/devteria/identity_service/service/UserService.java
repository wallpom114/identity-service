package com.devteria.identity_service.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.devteria.identity_service.dto.request.UserCreationRequest;
import com.devteria.identity_service.dto.request.UserUpdateRequest;
import com.devteria.identity_service.dto.response.UserResponse;
import com.devteria.identity_service.entity.User;
import com.devteria.identity_service.enums.Role;
import com.devteria.identity_service.exception.AppException;
import com.devteria.identity_service.exception.ErrorCode;
import com.devteria.identity_service.mapper.UserMapper;
import com.devteria.identity_service.repository.RoleRepository;
import com.devteria.identity_service.repository.UserRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor // annotation này nó sẽ tao ra constructor với tất cả các biến mà bạn define là
                         // final
// nếu bạn không muốn dùng @Autowired thì có thể dùng @RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
// annotation này sẽ làm cho các biến final được khởi tạo trong constructor

@Slf4j
public class UserService {

    UserRepository userRepository;
    RoleRepository roleRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;

    public UserResponse createUser(UserCreationRequest request) {
        // User user = new User();

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AppException(ErrorCode.USER_EXISTS);
        }

        User user = userMapper.toUser(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        HashSet<String> roles = new HashSet<>();
        roles.add(Role.USER.name());

        // user.setRoles(roles);

        // user.setUsername(request.getUsername());
        // user.setPassword(request.getPassword());
        // user.setFirstName(request.getFirstName());
        // user.setLastName(request.getLastName());
        // user.setDob(request.getDob());
        return userMapper.toUserResponse(userRepository.save(user));

    }

    public UserResponse updateUser(String userId, UserUpdateRequest request) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.USER_NOT_NULL);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        userMapper.updateUser(user, request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        var roles = roleRepository.findAllById(request.getRoles());
        user.setRoles(new HashSet<>(roles));

        // user.setPassword(request.getPassword());
        // user.setFirstName(request.getFirstName());
        // user.setLastName(request.getLastName());
        // user.setDob(request.getDob());
        return userMapper.toUserResponse(userRepository.save(user));
    }

    public UserResponse getMyInfo() {
        var context = SecurityContextHolder.getContext();

        String username = context.getAuthentication().getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.error("User not found: {}", username);
                    return new AppException(ErrorCode.USER_NOT_EXISTS);
                });

        return userMapper.toUserResponse(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<UserResponse> getAllUsers() {
        log.info("getAllUsers called - only ADMIN role can access this method");
        return userRepository.findAll().stream()
                .map(userMapper::toUserResponse)
                .toList();

    }

    @PostAuthorize("returnObject.username == authentication.name or hasRole('ADMIN') ")
    public UserResponse getUserById(String userId) {
        log.info("getUserById called - user can access only their own data");
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.USER_NOT_NULL);

        }
        return userMapper.toUserResponse(userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId)));
    }

    public void deleteUser(String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.USER_NOT_NULL);
        }
        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found with id: " + userId);
        }
        userRepository.deleteById(userId);
    }

}
