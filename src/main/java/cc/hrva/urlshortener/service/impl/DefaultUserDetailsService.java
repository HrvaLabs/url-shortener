package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.SecureTokens;
import cc.hrva.urlshortener.security.StoredPasswords;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultUserDetailsService implements UserDetailsService, SmartInitializingSingleton {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private volatile String noPasswordHash;

    public DefaultUserDetailsService(final UserRepository userRepository, @Lazy final PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        noPasswordHash();
    }

    @Override
    @Transactional
    public UserDetails loadUserByUsername(final String email) throws UsernameNotFoundException {
        return userRepository
                .findByEmail(email)
                .map(this::createSpringSecurityUser)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("User %s was not found in the database", email)));
    }

    private org.springframework.security.core.userdetails.User createSpringSecurityUser(final User user) {
        final var grantedAuthorities = user
                .getAuthorities()
                .stream()
                .map(authority -> new SimpleGrantedAuthority(authority.getName()))
                .toList();
        final var enabled = !Boolean.FALSE.equals(user.getActive());
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                StoredPasswords.isSet(user.getPassword()) ? user.getPassword() : noPasswordHash(),
                enabled,
                true,
                true,
                true,
                grantedAuthorities);
    }

    private String noPasswordHash() {
        var hash = noPasswordHash;
        if (hash == null) {
            synchronized (this) {
                hash = noPasswordHash;
                if (hash == null) {
                    hash = passwordEncoder.encode(SecureTokens.random());
                    noPasswordHash = hash;
                }
            }
        }

        return hash;
    }

}
