package com.buslink.security;

import com.buslink.entity.Conductor;
import com.buslink.enums.ConductorStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class ConductorPrincipal implements UserDetails {

    private final Conductor conductor;

    public ConductorPrincipal(Conductor conductor) {
        this.conductor = conductor;
    }

    public Conductor getConductor() {
        return conductor;
    }

    @Override
    public String getUsername() {
        return conductor.getEmail();
    }

    @Override
    public String getPassword() {
        return conductor.getPasswordHash();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    @Override
    public boolean isEnabled() {
        return conductor.getStatus() == ConductorStatus.ACTIVE;
    }
}
