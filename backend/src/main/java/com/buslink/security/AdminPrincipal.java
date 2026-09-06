package com.buslink.security;

import com.buslink.entity.Admin;
import com.buslink.enums.AdminStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AdminPrincipal implements UserDetails {

    private final Admin admin;

    public AdminPrincipal(Admin admin) {
        this.admin = admin;
    }

    public Admin getAdmin() {
        return admin;
    }

    @Override
    public String getUsername() {
        return admin.getEmail();
    }

    @Override
    public String getPassword() {
        return admin.getPasswordHash();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Override
    public boolean isEnabled() {
        return admin.getStatus() == AdminStatus.ACTIVE;
    }
}
