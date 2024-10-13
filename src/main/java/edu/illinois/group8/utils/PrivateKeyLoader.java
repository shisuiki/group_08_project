package edu.illinois.group8.utils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.Base64;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyFactory;

public class PrivateKeyLoader {
    public static PrivateKey loadPrivateKeyFromFile(String filePath) throws Exception {
        byte[] keyData = Files.readAllBytes(Paths.get(filePath));
        String pemEncodedKey = new String(keyData, "UTF-8");

        pemEncodedKey = pemEncodedKey.replaceAll("-----BEGIN (.*)-----", "")
                                      .replaceAll("-----END (.*)-----", "")
                                      .replaceAll("\r\n", "")
                                      .replaceAll("\n", "")
                                      .trim();

        byte[] decodedKeyData = Base64.getDecoder().decode(pemEncodedKey);

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decodedKeyData);

        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
