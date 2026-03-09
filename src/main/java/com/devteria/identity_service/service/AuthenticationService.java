package com.devteria.identity_service.service;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.devteria.identity_service.dto.request.AuthenticationRequest;
import com.devteria.identity_service.dto.request.IntrospectRequest;
import com.devteria.identity_service.dto.request.LogoutRequest;
import com.devteria.identity_service.dto.response.AuthenticationResponse;
import com.devteria.identity_service.dto.response.IntrospectResponse;
import com.devteria.identity_service.entity.InvalidatedToken;
import com.devteria.identity_service.entity.Role;
import com.devteria.identity_service.entity.User;
import com.devteria.identity_service.exception.AppException;
import com.devteria.identity_service.exception.ErrorCode;
import com.devteria.identity_service.repository.InvalidatedTokenRepository;
import com.devteria.identity_service.repository.UserRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationService {
    // private static final Logger log =
    // LoggerFactory.getLogger(AuthenticationService.class);
    UserRepository userRepository;
    InvalidatedTokenRepository invalidatedTokenRepository;
    PasswordEncoder passwordEncoder;
    com.devteria.identity_service.service.email.EmailQueueService emailQueueService;

    @NonFinal
    @Value("${jwt.signerKey}")
    protected String SIGNER_KEY;

    public IntrospectResponse introspect(IntrospectRequest request) throws JOSEException, ParseException {
        var token = request.getToken();

        boolean isvalid = true;
        try {
            verifyToken(token);
        } catch (AppException e) {
            isvalid = false;
        }

        return IntrospectResponse.builder()
                .valid(isvalid)
                .build();
    }

    public void logout(LogoutRequest request) throws JOSEException, ParseException {
        var signToken = verifyToken(request.getToken());

        String jti = signToken.getJWTClaimsSet().getJWTID();

        Date expirationTime = signToken.getJWTClaimsSet().getExpirationTime();

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jti)
                .expiryTime(expirationTime)
                .build();

        invalidatedTokenRepository.save(invalidatedToken);
    }

    private SignedJWT verifyToken(String token) throws JOSEException, ParseException {
        JWSVerifier jwsVerifier = new MACVerifier(SIGNER_KEY.getBytes());

        SignedJWT signedJWT = SignedJWT.parse(token);

        Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();

        var verified = signedJWT.verify(jwsVerifier);

        if (!(verified && expirationTime.after(new Date()))) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        if (invalidatedTokenRepository.existsById(signedJWT.getJWTClaimsSet().getJWTID())) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        return signedJWT;
    }

    public AuthenticationResponse authenticate(AuthenticationRequest authenticationRequest) {
        var user = userRepository.findByUsername(authenticationRequest.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTS));

        boolean authenticated = passwordEncoder.matches(authenticationRequest.getPassword(), user.getPassword());

        if (!authenticated) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        String token = generateToken(user);
        return AuthenticationResponse.builder()
                .token(token)
                .authenticated(authenticated)
                .build();

    }

    private String generateToken(User user) {

        JWSHeader jwsHeader = new JWSHeader(JWSAlgorithm.HS512);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(user.getUsername())
                .issuer("devteria.com")
                .issueTime(new Date())
                .expirationTime(new Date(
                        Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli()))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", getScopesAsString(user))
                .build();

        Payload payload = new Payload(jwtClaimsSet.toJSONObject());
        JWSObject jwsObject = new JWSObject(jwsHeader, payload);

        try {
            jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes()));
            return jwsObject.serialize();
        } catch (JOSEException e) {
            log.error("Cannot creat token", e);
            throw new RuntimeException(e);
        }
    }

    private String getScopesAsString(User user) {
        StringBuilder sb = new StringBuilder();
        for (Role role : user.getRoles()) {
            log.info("User role: {}", role.getName());
            sb.append("ROLE_").append(role.getName()).append(" ");
            if (role.getPermissions() != null) {
                role.getPermissions().forEach(permission -> {
                    log.info("Role permission: {}", permission.getName());
                    sb.append(permission.getName()).append(" ");
                });
            }
        }
        return sb.toString().trim();
    }

    public void requestPasswordReset(String email) {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTS));

        String newPassword = com.devteria.identity_service.util.PasswordGenerator.generate(8);

        // enqueue email job
        var job = new com.devteria.identity_service.service.email.EmailJob(
                user.getEmail(),
                com.devteria.identity_service.util.MailTemplate.RESET_PASSWORD_REQUEST.getSubject(),
                com.devteria.identity_service.util.MailTemplate.RESET_PASSWORD_REQUEST.getTemplate(),
                java.util.Map.of("name",
                        (user.getFirstName() == null ? "" : user.getFirstName()) + " "
                                + (user.getLastName() == null ? "" : user.getLastName()),
                        "newPassword", newPassword));
        emailQueueService.enqueue(job);

        // update password
        String newHashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(newHashedPassword);
        userRepository.save(user);
    }
}
