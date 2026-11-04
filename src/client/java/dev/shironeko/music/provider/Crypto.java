package dev.shironeko.music.provider;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Crypto {
  public static String hash(String algorithm, String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] aes(byte[] data, String key, String mode, boolean encrypt) throws Exception {
    Cipher c = Cipher.getInstance("AES/" + mode + "/PKCS5Padding");
    var k = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    if (mode.equals("CBC"))
      c.init(
          encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
          k,
          new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));
    else c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, k);
    return c.doFinal(data);
  }

  public static String qqSign(String payload) {
    String hex = hash("SHA-1", payload).toUpperCase(Locale.ROOT);
    int[] a = {23, 14, 6, 36, 16, 7, 19},
        b = {16, 1, 32, 12, 19, 27, 8, 5},
        scr =
            {
              89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161,
              121, 179
            };
    StringBuilder x = new StringBuilder("zzc"), y = new StringBuilder();
    for (int i : a) x.append(hex.charAt(i));
    for (int i : b) y.append(hex.charAt(i));
    byte[] raw = HexFormat.of().parseHex(hex);
    for (int i = 0; i < 20; i++) raw[i] ^= (byte) scr[i];
    return (x + Base64.getEncoder().encodeToString(raw).replaceAll("[/+=]", "") + y)
        .toLowerCase(Locale.ROOT);
  }

  public static long hash33(String text, long initial) {
    long h = initial;
    for (char c : text.toCharArray()) h = (h * 33 + c) & 0x7fffffff;
    return h;
  }
}
