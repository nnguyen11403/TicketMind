package com.ticketmind.backend.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Transparently encrypts a {@code String} column on write and decrypts it on
 * read.
 *
 * <p>Applied per-attribute rather than globally ({@code autoApply = false}) so
 * that adding it to a column is a deliberate act. An encrypted column can no
 * longer be filtered, sorted, or indexed in SQL, so it is only correct for
 * free-text the database never has to reason about.
 *
 * <p>Spring Boot registers a {@code SpringBeanContainer} with Hibernate, which
 * is what lets this be a {@code @Component} with constructor injection instead
 * of reaching for a static holder.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private final FieldCipher cipher;

	public EncryptedStringConverter(FieldCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return cipher.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		return cipher.decrypt(dbData);
	}
}
