package com.fairtix.fraud.api;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /auth/step-up/verify}; the token is a reCAPTCHA v2 response. */
public record StepUpVerifyRequest(@Size(max = 4096) String captchaToken) {
}
