package ps.ghars.sessions;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private app files are authenticated and encrypted by a non-exportable device key. */
final class SecureStore {
    static final String EMPTY_STATE = "{\"version\":1,\"students\":[],\"sessions\":[],\"reminders\":[],\"settings\":{\"defaultReminder\":-1}}";
    private static final String ALIAS = "ghars.device.data.v1";
    private static final byte[] MAGIC = new byte[]{'G','H','L','1'};
    private SecureStore() { }

    static synchronized String read(Context context, String name, String fallback) throws Exception {
        AtomicFile file = file(context, name);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return fallback;
        byte[] encoded = file.readFully();
        if (encoded.length < 32 || encoded.length > SchemaValidator.MAX_BYTES + 1024) throw new Exception("ملف البيانات تالف؛ احتفظي به واستعيدي نسخة احتياطية");
        ByteBuffer data = ByteBuffer.wrap(encoded);
        for (byte b : MAGIC) if (data.get() != b) throw new Exception("صيغة تخزين غير صالحة");
        byte[] iv = new byte[12];
        data.get(iv);
        byte[] encrypted = new byte[data.remaining()];
        data.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    static synchronized void write(Context context, String name, String text) throws Exception {
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        if (plain.length > SchemaValidator.MAX_BYTES) throw new Exception("حجم البيانات كبير جداً");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        ByteBuffer encoded = ByteBuffer.allocate(4 + 12 + encrypted.length);
        encoded.put(MAGIC).put(cipher.getIV()).put(encrypted);
        AtomicFile file = file(context, name);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(encoded.array());
            file.finishWrite(out);
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            throw error;
        } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }

    private static AtomicFile file(Context context, String name) {
        if (!name.matches("[a-z-]+")) throw new IllegalArgumentException("Invalid storage name");
        return new AtomicFile(new File(context.getNoBackupFilesDir(), name + ".ghl"));
    }
    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new Exception("مفتاح الجهاز غير متاح؛ استعيدي نسخة احتياطية مشفّرة");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
