package cloudide.sign;

import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;

/** One static method so CloudIDE can call apksig by reflection with plain strings. */
public final class SignMain {
    private SignMain() { }

    public static void sign(String in, String out, String pk8, String x509, int minSdk) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(new File(pk8).toPath())));
        X509Certificate cert;
        try (InputStream is = new FileInputStream(x509)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("debug", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }
}
