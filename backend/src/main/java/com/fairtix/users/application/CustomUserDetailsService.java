package com.fairtix.users.application;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.users.domain.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

  private final UserEmailLookup userEmailLookup;

  public CustomUserDetailsService(UserEmailLookup userEmailLookup) {
    this.userEmailLookup = userEmailLookup;
  }

  /** As login: ignoring case when exactly one account matches, else the exact match only. */
  @Override
  public UserDetails loadUserByUsername(String email)
      throws UsernameNotFoundException {

    User user = userEmailLookup.findForSignIn(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));

    return new CustomUserPrincipal(
        user.getId(),
        user.getEmail(),
        user.getPassword(),
        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
  }
}
