package com.example.sandrix.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Pure-Java PKCS12 release keystore &amp; self-signed X.509 certificate generator
 * plus {@code release-signing.properties} management (no {@code sun.security.*} dependencies).
 */
public final class KeystoreGenerator {

    public static final String SIGNING_PROPS_FILE = "release-signing.properties";

    public static final class SigningConfigModel {
        public final String storeFilePath;
        public final String storeType;
        public final String storePassword;
        public final String keyAlias;
        public final String keyPassword;
        public final boolean configured;

        public SigningConfigModel(
                String storeFilePath,
                String storeType,
                String storePassword,
                String keyAlias,
                String keyPassword,
                boolean configured) {
            this.storeFilePath = storeFilePath != null ? storeFilePath : "";
            this.storeType = (storeType != null && !storeType.trim().isEmpty()) ? storeType.trim() : "PKCS12";
            this.storePassword = storePassword != null ? storePassword : "";
            this.keyAlias = keyAlias != null ? keyAlias : "";
            this.keyPassword = keyPassword != null ? keyPassword : "";
            this.configured = configured;
        }
    }

    public static final class KeystoreInspection {
        public final boolean valid;
        public final String errorMessage;
        public final String storeType;
        public final List<String> aliases;
        public final String subject;
        public final String sha256;
        public final String validUntil;

        public KeystoreInspection(
                boolean valid,
                String errorMessage,
                String storeType,
                List<String> aliases,
                String subject,
                String sha256,
                String validUntil) {
            this.valid = valid;
            this.errorMessage = errorMessage != null ? errorMessage : "";
            this.storeType = storeType != null ? storeType : "";
            this.aliases = aliases != null ? Collections.unmodifiableList(aliases) : Collections.emptyList();
            this.subject = subject != null ? subject : "";
            this.sha256 = sha256 != null ? sha256 : "";
            this.validUntil = validUntil != null ? validUntil : "";
        }
    }

    private KeystoreGenerator() {}

    public static X509Certificate generatePkcs12Keystore(
            File outFile,
            String storePassword,
            String keyAlias,
            String keyPassword,
            String commonName,
            String organization,
            String country,
            int validityYears) throws Exception {
        if (outFile == null) {
            throw new IllegalArgumentException("Output keystore file cannot be null.");
        }
        String cleanStorePass = storePassword != null ? storePassword : "";
        String cleanKeyPass = (keyPassword != null && !keyPassword.isEmpty()) ? keyPassword : cleanStorePass;
        String cleanAlias = (keyAlias != null && !keyAlias.trim().isEmpty()) ? keyAlias.trim() : "release";
        if (cleanStorePass.length() < 6) {
            throw new IllegalArgumentException("Keystore password must be at least 6 characters.");
        }

        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create parent folder for keystore.");
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair keyPair = kpg.generateKeyPair();

        int years = Math.max(1, Math.min(50, validityYears));
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(notBefore);
        cal.add(Calendar.YEAR, years);
        Date notAfter = cal.getTime();

        X509Certificate cert = createSelfSignedCertificate(
                keyPair,
                commonName != null && !commonName.trim().isEmpty() ? commonName.trim() : "Sandrix Release",
                organization != null && !organization.trim().isEmpty() ? organization.trim() : "Sandrix Studio",
                sanitizeCountry(country),
                notBefore,
                notAfter);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(cleanAlias, keyPair.getPrivate(), cleanKeyPass.toCharArray(), new Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            ks.store(fos, cleanStorePass.toCharArray());
        }
        return cert;
    }

    public static KeystoreInspection inspectKeystore(File keystoreFile, String storePassword) {
        if (keystoreFile == null || !keystoreFile.isFile()) {
            return new KeystoreInspection(false, "Keystore file does not exist.", "",
                    Collections.emptyList(), "", "", "");
        }
        char[] pass = storePassword != null ? storePassword.toCharArray() : new char[0];
        String[] types = {"PKCS12", "JKS", "BKS"};
        Throwable lastErr = null;
        for (String type : types) {
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(fis, pass);
                List<String> aliases = new ArrayList<>();
                Enumeration<String> en = ks.aliases();
                String subject = "";
                String sha256 = "";
                String validUntil = "";
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                while (en.hasMoreElements()) {
                    String a = en.nextElement();
                    if (ks.isKeyEntry(a)) {
                        aliases.add(a);
                        Certificate c = ks.getCertificate(a);
                        if (subject.isEmpty() && c instanceof X509Certificate) {
                            X509Certificate x = (X509Certificate) c;
                            subject = x.getSubjectX500Principal().getName();
                            sha256 = sha256Hex(x.getEncoded());
                            validUntil = df.format(x.getNotAfter());
                        }
                    }
                }
                if (aliases.isEmpty()) {
                    return new KeystoreInspection(false, "Keystore has no private key entries.", type,
                            aliases, "", "", "");
                }
                return new KeystoreInspection(true, "", type, aliases, subject, sha256, validUntil);
            } catch (Throwable t) {
                lastErr = t;
            }
        }
        String err = lastErr != null && lastErr.getMessage() != null
                ? lastErr.getMessage()
                : "Unable to open keystore with supplied password.";
        return new KeystoreInspection(false, err, "", Collections.emptyList(), "", "", "");
    }

    public static SigningConfigModel loadSigningConfig(File projectDir) {
        if (projectDir == null || !projectDir.exists()) {
            return new SigningConfigModel("", "PKCS12", "", "release", "", false);
        }
        File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(projectDir);
        File propsFile = new File(effectiveRoot, SIGNING_PROPS_FILE);
        if (!propsFile.isFile()) {
            propsFile = new File(projectDir, SIGNING_PROPS_FILE);
        }
        if (!propsFile.isFile()) {
            return new SigningConfigModel("release-key.p12", "PKCS12", "", "release", "", false);
        }
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            p.load(fis);
        } catch (IOException e) {
            return new SigningConfigModel("release-key.p12", "PKCS12", "", "release", "", false);
        }
        String storeFile = p.getProperty("storeFile", "").trim();
        String storeType = p.getProperty("storeType", "PKCS12").trim();
        String storePass = p.getProperty("storePassword", "").trim();
        String alias = p.getProperty("keyAlias", "release").trim();
        String keyPass = p.getProperty("keyPassword", storePass).trim();
        boolean valid = !storeFile.isEmpty() && !storePass.isEmpty() && !alias.isEmpty();
        return new SigningConfigModel(storeFile, storeType, storePass, alias, keyPass, valid);
    }

    public static File saveSigningConfig(File projectDir, SigningConfigModel model) throws IOException {
        if (projectDir == null || !projectDir.exists()) {
            throw new IOException("No active project directory.");
        }
        File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(projectDir);
        File propsFile = new File(effectiveRoot, SIGNING_PROPS_FILE);

        StringBuilder sb = new StringBuilder();
        sb.append("# Sandrix Release Signing Configuration (ignored by Git)\n");
        sb.append("storeFile=").append(escapeProp(model.storeFilePath)).append('\n');
        sb.append("storeType=").append(escapeProp(model.storeType)).append('\n');
        sb.append("storePassword=").append(escapeProp(model.storePassword)).append('\n');
        sb.append("keyAlias=").append(escapeProp(model.keyAlias)).append('\n');
        sb.append("keyPassword=").append(escapeProp(model.keyPassword.isEmpty() ? model.storePassword : model.keyPassword)).append('\n');

        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        ensureGitIgnoreProtectsSecrets(projectDir);
        if (!effectiveRoot.equals(projectDir)) {
            ensureGitIgnoreProtectsSecrets(effectiveRoot);
        }
        return propsFile;
    }

    public static boolean clearSigningConfig(File projectDir) {
        if (projectDir == null) return false;
        boolean deleted = false;
        File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(projectDir);
        File f1 = new File(effectiveRoot, SIGNING_PROPS_FILE);
        if (f1.exists()) deleted |= f1.delete();
        File f2 = new File(projectDir, SIGNING_PROPS_FILE);
        if (f2.exists()) deleted |= f2.delete();
        return deleted;
    }

    public static void ensureGitIgnoreProtectsSecrets(File dir) throws IOException {
        if (dir == null || !dir.isDirectory()) return;
        File gitignore = new File(dir, ".gitignore");
        String existing = "";
        if (gitignore.exists() && gitignore.isFile()) {
            try (FileInputStream fis = new FileInputStream(gitignore);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
                existing = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        List<String> lines = new ArrayList<>();
        if (!existing.isEmpty()) {
            lines.addAll(Arrays.asList(existing.split("\\r?\\n")));
        }
        String[] entries = {SIGNING_PROPS_FILE, "*.p12", "*.jks", "*.keystore"};
        boolean changed = false;
        for (String entry : entries) {
            boolean found = false;
            for (String line : lines) {
                if (line.trim().equals(entry)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add(entry);
                changed = true;
            }
        }
        if (changed || !gitignore.exists()) {
            try (FileOutputStream fos = new FileOutputStream(gitignore)) {
                fos.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // ==================== Minimal RFC 5280 DER X.509 v3 Encoder ====================

    private static X509Certificate createSelfSignedCertificate(
            KeyPair keyPair,
            String commonName,
            String organization,
            String country,
            Date notBefore,
            Date notAfter) throws Exception {
        // SHA256withRSA AlgorithmIdentifier: 1.2.840.113549.1.1.11 + NULL
        byte[] algId = derSequence(
                derObjectIdentifier(new int[]{1, 2, 840, 113549, 1, 1, 11}),
                new byte[]{0x05, 0x00});

        // Version v3 = [0] EXPLICIT INTEGER 2
        byte[] version = derTagged(0, derInteger(BigInteger.valueOf(2)));

        // Positive 64-bit random serial number
        byte[] serialBytes = new byte[8];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] &= 0x7F;
        if (serialBytes[0] == 0) serialBytes[0] = 1;
        byte[] serial = derInteger(new BigInteger(1, serialBytes));

        byte[] dn = encodeX500Name(country, organization, commonName);
        byte[] validity = derSequence(encodeAsn1Time(notBefore), encodeAsn1Time(notAfter));
        byte[] spki = keyPair.getPublic().getEncoded();

        byte[] tbs = derSequence(
                version,
                serial,
                algId,
                dn, // issuer
                validity,
                dn, // subject
                spki);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();

        byte[] bitStringSig = derBitString(sigBytes);
        byte[] certDer = derSequence(tbs, algId, bitStringSig);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
        cert.verify(keyPair.getPublic());
        return cert;
    }

    private static byte[] encodeX500Name(String country, String org, String cn) throws IOException {
        byte[] rdnCountry = derSet(derSequence(
                derObjectIdentifier(new int[]{2, 5, 4, 6}),
                derPrintableString(country)));
        byte[] rdnOrg = derSet(derSequence(
                derObjectIdentifier(new int[]{2, 5, 4, 10}),
                derUtf8String(org)));
        byte[] rdnCn = derSet(derSequence(
                derObjectIdentifier(new int[]{2, 5, 4, 3}),
                derUtf8String(cn)));
        return derSequence(rdnCountry, rdnOrg, rdnCn);
    }

    private static byte[] encodeAsn1Time(Date date) throws IOException {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        if (year >= 1950 && year <= 2049) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return derWrap(0x17, fmt.format(date).getBytes(StandardCharsets.US_ASCII));
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return derWrap(0x18, fmt.format(date).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static byte[] derSequence(byte[]... items) throws IOException {
        return derWrap(0x30, concat(items));
    }

    private static byte[] derSet(byte[]... items) throws IOException {
        return derWrap(0x31, concat(items));
    }

    private static byte[] derTagged(int tagNumber, byte[] content) throws IOException {
        return derWrap(0xA0 | (tagNumber & 0x1F), content);
    }

    private static byte[] derInteger(BigInteger value) throws IOException {
        return derWrap(0x02, value.toByteArray());
    }

    private static byte[] derBitString(byte[] bytes) throws IOException {
        byte[] content = new byte[bytes.length + 1];
        content[0] = 0x00;
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return derWrap(0x03, content);
    }

    private static byte[] derUtf8String(String s) throws IOException {
        return derWrap(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derPrintableString(String s) throws IOException {
        return derWrap(0x13, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derObjectIdentifier(int[] arcs) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(body, arcs[i]);
        }
        return derWrap(0x06, body.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        if (value < 128) {
            out.write(value);
            return;
        }
        byte[] buf = new byte[5];
        int pos = buf.length;
        buf[--pos] = (byte) (value & 0x7F);
        value >>>= 7;
        while (value > 0) {
            buf[--pos] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(buf, pos, buf.length - pos);
    }

    private static byte[] derWrap(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else {
            byte[] lenBytes = new byte[4];
            int count = 0;
            int tmp = len;
            while (tmp > 0) {
                lenBytes[3 - count] = (byte) (tmp & 0xFF);
                tmp >>>= 8;
                count++;
            }
            out.write(0x80 | count);
            out.write(lenBytes, 4 - count, count);
        }
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.write(a);
        }
        return out.toByteArray();
    }

    private static String sanitizeCountry(String country) {
        if (country == null) return "US";
        String clean = country.trim().replaceAll("[^A-Za-z]", "").toUpperCase(Locale.US);
        return clean.length() == 2 ? clean : "US";
    }

    private static String escapeProp(String val) {
        if (val == null) return "";
        return val.replace("\\", "\\\\");
    }

    private static String sha256Hex(byte[] encoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
