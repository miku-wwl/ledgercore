package nz.ac.ledgercore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
		FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(new CorrelationIdFilter());
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	@Bean
	UserDetailsService denyAuthenticationUntilIdentityModuleIsImplemented() {
		return username -> {
			throw new UsernameNotFoundException("Identity authentication is not configured yet.");
		};
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
						"/swagger-ui.html")
				.permitAll()
				.anyRequest().authenticated())
			.csrf(Customizer.withDefaults())
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, exception) -> writeProblem(request, response,
						HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required."))
				.accessDeniedHandler((request, response, exception) -> writeProblem(request, response,
						HttpStatus.FORBIDDEN, "Forbidden", "The request is not permitted.")))
			.build();
	}

	private static void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			String title, String detail) throws IOException {
		Object requestId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
		String correlationId = requestId instanceof String id ? id : "unavailable";
		response.setStatus(status.value());
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + escape(title)
				+ "\",\"status\":" + status.value() + ",\"detail\":\"" + escape(detail)
				+ "\",\"correlationId\":\"" + escape(correlationId) + "\"}");
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
