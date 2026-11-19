package com.adsjarvis.standalone;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the owner's AI API key encrypted with a non-exportable AES-GCM key
 * in the Android Keystore. Only ciphertext is written to SharedPreferences.
 * The key never leaves the device, is never logged, and app backup is off.
 */
final class KeyVault {
    private static final String ALIAS = "jarvis_ai_key_v1";
    private final String PREF;
    private final SharedPreferences prefs;

    KeyVault(SharedPreferences prefs) { this(prefs, "ai_key_enc"); }
    /** Same Keystore key, different ciphertext slot (e.g. PB7 token). */
    KeyVault(SharedPreferences prefs, String slot) { this.prefs = prefs; this.PREF = slot; }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return g.generateKey();
    }

    boolean has() { return prefs.contains(PREF); }

    boolean save(String apiKey) {
        try {
            if (apiKey == null || apiKey.trim().isEmpty()) { prefs.edit().remove(PREF).apply(); return true; }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString(PREF, Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String load() {
        String v = prefs.getString(PREF, null);
        if (v == null) return "";
        try {
            String[] parts = v.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
