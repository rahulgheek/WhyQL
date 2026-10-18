package com.whyql.whyql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WhyqlApplicationTests {

	// JwtService requires JWT_SECRET (>= 32 chars) even in tests.
	@DynamicPropertySource
	static void testSecrets(DynamicPropertyRegistry registry) {
		registry.add("JWT_SECRET", () -> "test-secret-012345678901234567890123456789012345678901234");
		registry.add("ENCRYPTION_KEY", () -> "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=");
	}

	@Test
	void contextLoads() {
	}

}
