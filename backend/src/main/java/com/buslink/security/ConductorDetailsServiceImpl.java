package com.buslink.security;

import com.buslink.entity.Conductor;
import com.buslink.repository.ConductorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConductorDetailsServiceImpl implements UserDetailsService {

    private final ConductorRepository conductorRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        Conductor conductor = conductorRepository
                .findByEmail(email)
                .orElseThrow(
                        () -> new UsernameNotFoundException("Conductor not found with email: '" + email + "'"));
        return new ConductorPrincipal(conductor);
    }
}
