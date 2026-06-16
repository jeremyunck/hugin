package com.example.integration.github;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an RSA private key from PEM text, accepting both encodings GitHub may hand out.
 *
 * <p>GitHub App private keys downloaded from the App settings page are <b>PKCS#1</b>
 * ("BEGIN RSA PRIVATE KEY"), but {@link java.security.KeyFactory} only understands <b>PKCS#8</b>
 * ("BEGIN PRIVATE KEY"). To avoid pulling in BouncyCastle for one key parse, a PKCS#1 body is wrapped
 * in the minimal PKCS#8 {@code PrivateKeyInfo} DER structure before decoding.
 */
final class RsaPrivateKeys {

    private RsaPrivateKeys() {
    }

    /** Algorithm identifier DER for {@code rsaEncryption}: SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }. */
    private static final byte[] RSA_ALGORITHM_ID = {
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    static PrivateKey parse(String pem) throws Exception {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Private key PEM is empty");
        }
        String normalized = pem.replace("\\n", "\n").trim();
        boolean pkcs1 = normalized.contains("BEGIN RSA PRIVATE KEY");

        byte[] der = Base64.getMimeDecoder().decode(stripPem(normalized));
        if (pkcs1) {
            der = wrapPkcs1InPkcs8(der);
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Strips the PEM armor and surrounding whitespace, leaving only the base64 body. */
    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }

    /**
     * Wraps a PKCS#1 RSAPrivateKey DER blob in a PKCS#8 {@code PrivateKeyInfo}:
     * {@code SEQUENCE { INTEGER 0, AlgorithmIdentifier(rsaEncryption), OCTET STRING(pkcs1) }}.
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        // version INTEGER 0
        inner.write(new byte[]{0x02, 0x01, 0x00});
        // AlgorithmIdentifier
        inner.write(RSA_ALGORITHM_ID);
        // privateKey OCTET STRING { pkcs1 }
        inner.write(derElement((byte) 0x04, pkcs1));
        return derElement((byte) 0x30, inner.toByteArray());
    }

    /** Builds a single DER TLV element: tag, length (definite, possibly long-form), then content. */
    private static byte[] derElement(byte tag, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.write(derLength(content.length));
        out.write(content);
        return out.toByteArray();
    }

    /** Encodes a DER definite length: short form below 128, otherwise long form (0x80|count, bytes). */
    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        ByteArrayOutputStream lenBytes = new ByteArrayOutputStream();
        int remaining = length;
        while (remaining > 0) {
            lenBytes.write(remaining & 0xFF);
            remaining >>= 8;
        }
        byte[] be = lenBytes.toByteArray();
        // lenBytes is little-endian; emit count byte then big-endian length.
        byte[] result = new byte[be.length + 1];
        result[0] = (byte) (0x80 | be.length);
        for (int i = 0; i < be.length; i++) {
            result[i + 1] = be[be.length - 1 - i];
        }
        return result;
    }
}
