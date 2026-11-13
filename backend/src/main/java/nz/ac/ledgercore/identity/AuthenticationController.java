package nz.ac.ledgercore.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

	private final AuthenticationManager authenticationManager;
	private final IdentityService identityService;
	private final HttpSessionSecurityContextRepository securityContextRepository =
			new HttpSessionSecurityContextRepository();
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	public AuthenticationController(AuthenticationManager authenticationManager, IdentityService identityService) {
		this.authenticationManager = authenticationManager;
		this.identityService = identityService;
	}

	@GetMapping("/csrf")
	CsrfResponse csrf(@Parameter(hidden = true) CsrfToken token) {
		return new CsrfResponse(token.getToken());
	}

	@PostMapping("/register")
	@ApiResponse(responseCode = "201", description = "Customer created and signed in")
	@ApiResponse(responseCode = "409", description = "Email address already registered")
	ResponseEntity<IdentityService.CustomerProfile> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		IdentityService.CustomerProfile profile = identityService.register(request.email(), request.password(),
				request.displayName());
		login(request.email(), request.password(), servletRequest, servletResponse);
		return ResponseEntity.status(HttpStatus.CREATED).body(profile);
	}

	@PostMapping("/login")
	@ApiResponse(responseCode = "200", description = "Customer signed in")
	@ApiResponse(responseCode = "401", description = "Credentials were not recognized")
	IdentityService.CustomerProfile login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		Authentication authentication = authenticate(request.email(), request.password());
		return saveAuthentication(authentication, servletRequest, servletResponse);
	}

	@GetMapping("/me")
	IdentityService.CustomerProfile me(@AuthenticationPrincipal CustomerPrincipal principal) {
		return new IdentityService.CustomerProfile(principal.customerId(), principal.email(), principal.displayName());
	}

	@PostMapping("/logout")
	@ApiResponse(responseCode = "204", description = "Session ended")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		logoutHandler.logout(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

	private void login(String email, String password, HttpServletRequest request, HttpServletResponse response) {
		Authentication authentication = authenticate(email, password);
		saveAuthentication(authentication, request, response);
	}

	private Authentication authenticate(String email, String password) {
		try {
			return authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
		}
		catch (BadCredentialsException exception) {
			throw new IdentityException(IdentityErrorCode.AUTHENTICATION_FAILED, "Email or password was not recognized.");
		}
	}

	private IdentityService.CustomerProfile saveAuthentication(Authentication authentication,
			HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		CustomerPrincipal principal = (CustomerPrincipal) authentication.getPrincipal();
		return new IdentityService.CustomerProfile(principal.customerId(), principal.email(), principal.displayName());
	}

	public record CsrfResponse(String token) {
	}

	public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 12, max = 128) String password,
			@NotBlank @Size(max = 100) String displayName) {
	}

	public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 128) String password) {
	}
}
