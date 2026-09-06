package com.buslink.security;

import com.buslink.entity.Admin;
import com.buslink.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDetailsServiceImpl implements UserDetailsService {

    private final AdminRepository adminRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        Admin admin = adminRepository
                .findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Admin not found with email: '" + email + "'"));
        return new AdminPrincipal(admin);
    }
}
