package edu.illinois.group8.utils;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class Cryptography {
    private static byte[] buildPkcs8KeyFromPkcs1Key(byte[] innerKey) {
        var result = new byte[innerKey.length + 26];
        System.arraycopy(Base64.getDecoder().decode("MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKY="), 0, result, 0, 26);
        System.arraycopy(BigInteger.valueOf(result.length - 4).toByteArray(), 0, result, 2, 2);
        System.arraycopy(BigInteger.valueOf(innerKey.length).toByteArray(), 0, result, 24, 2);
        System.arraycopy(innerKey, 0, result, 26, innerKey.length);
        return result;
    }

    public static PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(path)));
        return loadPrivateKeyFromPem(pem);
    }

    public static PrivateKey loadPrivateKeyFromPem(String pem) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        pem = pem.replaceAll("-----\\w+ RSA PRIVATE KEY-----", "").replaceAll("\\s", "");

        byte[] bytes1 = Base64.getDecoder().decode(pem);
        byte[] bytes8 = buildPkcs8KeyFromPkcs1Key(bytes1);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes8);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static String signMessage(String message, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA/PSS");
        signature.initSign(privateKey);
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

}
