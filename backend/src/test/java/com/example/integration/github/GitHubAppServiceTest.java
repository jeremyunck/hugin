package com.example.integration.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAppServiceTest {

    /** A PKCS#8 ("BEGIN PRIVATE KEY") PEM is parsed and can sign a verifiable signature. */
    @Test
    void parsesPkcs8KeyAndSigns() throws Exception {
        KeyPair pair = generateKeyPair();
        PrivateKey key = RsaPrivateKeys.parse(toPkcs8Pem(pair.getPrivate()));
        assertThat(key).isNotNull();
        assertThat(signsAndVerifies(key)).isTrue();
    }

    /** A PKCS#1 ("BEGIN RSA PRIVATE KEY") PEM — what GitHub hands out — is wrapped and parsed. */
    @Test
    void parsesPkcs1KeyAndSigns() throws Exception {
        KeyPair pair = generateKeyPair();
        PrivateKey key = RsaPrivateKeys.parse(toPkcs1Pem(pair.getPrivate()));
        assertThat(key).isNotNull();
        assertThat(signsAndVerifies(key)).isTrue();
    }

    /** Both encodings of the same key must yield the same private key material. */
    @Test
    void pkcs1AndPkcs8DescribeTheSameKey() throws Exception {
        KeyPair pair = generateKeyPair();
        RSAPrivateCrtKey fromPkcs1 = (RSAPrivateCrtKey) RsaPrivateKeys.parse(toPkcs1Pem(pair.getPrivate()));
        RSAPrivateCrtKey fromPkcs8 = (RSAPrivateCrtKey) RsaPrivateKeys.parse(toPkcs8Pem(pair.getPrivate()));
        assertThat(fromPkcs1.getModulus()).isEqualTo(fromPkcs8.getModulus());
        assertThat(fromPkcs1.getPrivateExponent()).isEqualTo(fromPkcs8.getPrivateExponent());
    }

    @Test
    void statusIsInactiveWhenNotConfigured() {
        GitHubProperties props = new GitHubProperties("", "", "", "", null, null, null);
        GitHubAppService service = new GitHubAppService(props, new ObjectMapper());

        GitHubStatus status = service.status();
        assertThat(status.active()).isFalse();
        assertThat(status.configured()).isFalse();
        assertThat(status.authMode()).isEqualTo("github-app");
        assertThat(service.installationToken()).isEmpty();
    }

    @Test
    void installUrlUsesAppSlug() {
        GitHubProperties props = new GitHubProperties("123", "my-bouw-app", "", "", null, null, null);
        GitHubAppService service = new GitHubAppService(props, new ObjectMapper());

        assertThat(service.installUrl(null))
                .contains("https://github.com/apps/my-bouw-app/installations/new");
        assertThat(service.installUrl("https://app.example.com/return"))
                .get().asString().contains("state=");
    }

    @Test
    void noInstallUrlWithoutSlug() {
        GitHubProperties props = new GitHubProperties("123", "", "", "", null, null, null);
        GitHubAppService service = new GitHubAppService(props, new ObjectMapper());
        assertThat(service.installUrl(null)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Java keys encode to PKCS#8 natively. */
    private static String toPkcs8Pem(PrivateKey key) {
        return wrap("PRIVATE KEY", key.getEncoded());
    }

    /** Derives a PKCS#1 PEM by unwrapping the inner RSAPrivateKey from the PKCS#8 encoding. */
    private static String toPkcs1Pem(PrivateKey key) {
        return wrap("RSA PRIVATE KEY", extractPkcs1(key.getEncoded()));
    }

    private static String wrap(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /**
     * Pulls the PKCS#1 RSAPrivateKey blob out of a PKCS#8 PrivateKeyInfo:
     * SEQUENCE { INTEGER version, SEQUENCE algId, OCTET STRING privateKey }.
     */
    private static byte[] extractPkcs1(byte[] pkcs8) {
        DerCursor cursor = new DerCursor(pkcs8);
        cursor.expect(0x30);
        cursor.readLength(); // outer SEQUENCE length
        cursor.expect(0x02);
        cursor.skip(cursor.readLength()); // version
        cursor.expect(0x30);
        cursor.skip(cursor.readLength()); // AlgorithmIdentifier
        cursor.expect(0x04);
        int len = cursor.readLength();
        return cursor.read(len); // the PKCS#1 DER
    }

    /** Minimal DER walker for the fixed PKCS#8 layout above. */
    private static final class DerCursor {
        private final byte[] data;
        private int pos;

        DerCursor(byte[] data) {
            this.data = data;
        }

        void expect(int tag) {
            int actual = data[pos++] & 0xFF;
            if (actual != tag) {
                throw new IllegalStateException("Expected DER tag " + tag + " but found " + actual);
            }
        }

        int readLength() {
            int first = data[pos++] & 0xFF;
            if (first < 0x80) {
                return first;
            }
            int count = first & 0x7F;
            int length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << 8) | (data[pos++] & 0xFF);
            }
            return length;
        }

        void skip(int n) {
            pos += n;
        }

        byte[] read(int n) {
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }
    }

    /** Signs sample bytes with the parsed key and verifies them with the derived public key. */
    private static boolean signsAndVerifies(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));

        byte[] data = "bouw.github.app".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }
}
