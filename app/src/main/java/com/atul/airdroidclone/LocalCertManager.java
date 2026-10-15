package com.dialingassistant.agent;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Generates (once) and loads a self-signed TLS certificate stored in the app's
 * private data directory. Used by LocalNetworkServer for HTTPS + WSS on LAN.
 *
 * First-time trust: the user visits https://<device-ip>:8765 in a browser and
 * accepts the security warning. After that all subsequent connections are silent.
 */
public class LocalCertManager {

    private static final String TAG = "LocalCertManager";
    private static final String KEYSTORE_FILE = "local_server.p12";
    private static final String KEYSTORE_ALIAS = "agent_android";
    private static final char[] KEYSTORE_PASS = "agentlocal".toCharArray();
    private static final int KEY_BITS = 2048;
    /** Cert validity: 10 years */
    private static final long VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    private final Context context;

    public LocalCertManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns a PKCS12 KeyStore containing the self-signed cert + private key.
     * Generates it on first call and caches it on disk.
     */
    public KeyStore getKeyStore() throws Exception {
        File ksFile = new File(context.getFilesDir(), KEYSTORE_FILE);
        if (ksFile.exists()) {
            try {
                return loadKeyStore(ksFile);
            } catch (Exception e) {
                Log.w(TAG, "Existing keystore invalid, regenerating: " + e.getMessage());
                ksFile.delete();
            }
        }
        return generateAndSave(ksFile);
    }

    private KeyStore loadKeyStore(File file) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(file)) {
            ks.load(fis, KEYSTORE_PASS);
        }
        Log.d(TAG, "Loaded existing keystore from " + file.getPath());
        return ks;
    }

    private KeyStore generateAndSave(File file) throws Exception {
        Log.d(TAG, "Generating new self-signed TLS certificate…");

        // 1. Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_BITS, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // 2. Build X.509 v3 certificate via BouncyCastle
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=AgentAndroid,O=DialingAssistant,C=IN");
        BigInteger serial = BigInteger.valueOf(now);
        Date notBefore = new Date(now - 1000L);
        Date notAfter  = new Date(now + VALIDITY_MS);

        SubjectPublicKeyInfo pubKeyInfo =
                SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, pubKeyInfo);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(kp.getPrivate());

        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));

        // 3. Pack into PKCS12 KeyStore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, KEYSTORE_PASS);
        ks.setKeyEntry(KEYSTORE_ALIAS, kp.getPrivate(), KEYSTORE_PASS,
                new java.security.cert.Certificate[]{ cert });

        // 4. Persist to private storage
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, KEYSTORE_PASS);
        }

        Log.d(TAG, "Self-signed cert generated and saved to " + file.getPath());
        return ks;
    }
}
