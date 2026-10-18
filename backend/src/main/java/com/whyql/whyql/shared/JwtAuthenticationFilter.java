package com.whyql.whyql.shared;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.List;

/**
 * Validates our own Bearer access tokens on every request. Principal = user id
 * (JWT subject), details = email. Requests without a header pass through to the
 * chain (public endpoints); requests with a bad token get a 401 immediately so a
 * stale token never falls through to an authenticated-only endpoint as anonymous.
 *
 * <p>Plain {@link Filter} on purpose, not OncePerRequestFilter: the single-execution
 * guarantee is a request attribute, and this avoids any dependence on framework
 * init callbacks (which MockMvc-driven chains exercise differently).
 *
 * <p>Lives in the shared module root package so every module's security chain may
 * use it (Spring Modulith only exposes module-root package types across boundaries).
 */
@Component
public class JwtAuthenticationFilter implements Filter {

  private static final String ALREADY_FILTERED =
      JwtAuthenticationFilter.class.getName() + ".FILTERED";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    if (httpRequest.getAttribute(ALREADY_FILTERED) != null) {
      chain.doFilter(request, response);
      return;
    }
    httpRequest.setAttribute(ALREADY_FILTERED, Boolean.TRUE);

    String header = httpRequest.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      chain.doFilter(request, response);
      return;
    }

    try {
      DecodedJWT decoded = jwtService.verifyAccessToken(header.substring(7).trim());
      if (!"access".equals(decoded.getClaim("typ").asString())) {
        throw new JWTVerificationException("not an access token");
      }
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(decoded.getSubject(), null,
              List.of(new SimpleGrantedAuthority("ROLE_USER")));
      authentication.setDetails(decoded.getClaim("email").asString());
      SecurityContextHolder.getContext().setAuthentication(authentication);
      chain.doFilter(request, response);
    } catch (JWTVerificationException e) {
      httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      httpResponse.setContentType("application/json");
      httpResponse.getWriter()
          .write("{\"code\":\"INVALID_ACCESS_TOKEN\",\"message\":\"invalid or expired access token\"}");
    }
  }
}
