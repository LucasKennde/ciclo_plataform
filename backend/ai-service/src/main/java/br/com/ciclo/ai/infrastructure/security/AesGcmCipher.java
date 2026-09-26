package br.com.ciclo.ai.infrastructure.security;

import br.com.ciclo.ai.application.AiPorts.Cipher;
import br.com.ciclo.ai.domain.AiCatalog.Provider;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmCipher implements Cipher {
  private final byte[] key;

  public AesGcmCipher(@Value("${app.ai-master-key:}") String raw) {
    byte[] decoded;
    try {
      decoded = raw.isBlank() ? new byte[0] : Base64.getDecoder().decode(raw);
    } catch (Exception e) {
      decoded = new byte[0];
    }
    key = decoded;
  }

  public boolean available() {
    return key.length == 32;
  }

  public String encrypt(String plain, Provider provider) {
    ensure();
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, iv));
      cipher.updateAAD(provider.name().getBytes(StandardCharsets.UTF_8));
      byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + enc.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(enc, 0, out, iv.length, enc.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao criptografar credencial.", e);
    }
  }

  public String decrypt(String value, Provider provider) {
    ensure();
    try {
      byte[] all = Base64.getDecoder().decode(value),
          iv = Arrays.copyOfRange(all, 0, 12),
          enc = Arrays.copyOfRange(all, 12, all.length);
      javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, iv));
      cipher.updateAAD(provider.name().getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Credencial não pôde ser descriptografada.");
    }
  }

  private void ensure() {
    if (!available())
      throw new IllegalStateException("A chave mestre deve ter 32 bytes em Base64.");
  }
}
