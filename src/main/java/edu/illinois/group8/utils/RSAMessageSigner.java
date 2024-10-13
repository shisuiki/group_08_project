package edu.illinois.group8.utils;

import java.security.Signature;
import java.security.PrivateKey;
import java.util.Base64;

public class RSAMessageSigner {
    public static String signPssText(PrivateKey privateKey, String text) throws Exception {
        byte[] message = text.getBytes("UTF-8");

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message);

        byte[] signedBytes = signature.sign();
        return Base64.getEncoder().encodeToString(signedBytes);
    }
}
