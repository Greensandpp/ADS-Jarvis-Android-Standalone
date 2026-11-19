package com.adsjarvis.standalone.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Password-encrypted backup: PBKDF2-HMAC-SHA256 (120k iterations) → AES-256-GCM.
 * GCM authenticates the data; a SHA-256 of the plaintext is also checked so a
 * wrong password, truncation or tampering is reported, never silently loaded.
 * API keys are never included in backups.
 */
public final class Backup {
    public static final int ITERATIONS = 120_000;

    public static final class BackupException extends Exception {
        public BackupException(String m) { super(m); }
    }

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(new PBEKeySpec(password, salt, ITERATIONS, 256)).getEncoded();
    }

    public static String encrypt(JSONObject payload, char[] password) throws BackupException {
        if (password == null || password.length < 8) throw new BackupException("Use a backup password of at least 8 characters.");
        try {
            byte[] plain = payload.toString().getBytes(StandardCharsets.UTF_8);
            SecureRandom rnd = new SecureRandom();
            byte[] salt = new byte[16], iv = new byte[12];
            rnd.nextBytes(salt); rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive(password, salt), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            Base64.Encoder b = Base64.getEncoder();
            return new JSONObject().put("format", "greensoil-jarvis-backup").put("v", 1).put("kdf", "PBKDF2-SHA256")
                .put("iter", ITERATIONS).put("salt", b.encodeToString(salt)).put("iv", b.encodeToString(iv))
                .put("sha256", b.encodeToString(MessageDigest.getInstance("SHA-256").digest(plain)))
                .put("data", b.encodeToString(ct)).toString();
        } catch (BackupException e) { throw e; }
        catch (Exception e) { throw new BackupException("Encryption failed: " + e.getClass().getSimpleName()); }
    }

    public static JSONObject decrypt(String file, char[] password) throws BackupException {
        JSONObject o;
        try { o = new JSONObject(file); } catch (JSONException e) { throw new BackupException("Not a JARVIS backup file."); }
        if (!"greensoil-jarvis-backup".equals(o.optString("format"))) throw new BackupException("Not a JARVIS backup file.");
        try {
            Base64.Decoder d = Base64.getDecoder();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(password, d.decode(o.getString("salt"))), "AES"),
                new GCMParameterSpec(128, d.decode(o.getString("iv"))));
            byte[] plain = c.doFinal(d.decode(o.getString("data")));
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(plain);
            if (!MessageDigest.isEqual(sha, d.decode(o.getString("sha256")))) throw new BackupException("Integrity check failed.");
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new BackupException("Wrong password or the backup was modified.");
        } catch (BackupException e) { throw e; }
        catch (Exception e) { throw new BackupException("Backup could not be read: " + e.getClass().getSimpleName()); }
    }
}
