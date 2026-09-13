package ps.ghars.sessions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable versioned backup: authenticated header, PBKDF2-SHA256, AES-256-GCM. */
public final class BackupCodec {
    private static final byte[] MAGIC = new byte[]{'G','H','A','R','S','B','K','1'};
    private static final int ITERATIONS = 600000;
    private static final int HEADER_SIZE = 8 + 4 + 16 + 12;
    private BackupCodec() { }

    public static byte[] encrypt(String json, char[] password) throws Exception {
        validatePassword(password);
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(iv);
        byte[] header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(ITERATIONS).put(salt).put(iv).array();
        byte[] key = derive(password, salt, ITERATIONS);
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(plain, (byte) 0); }
    }

    public static String decrypt(byte[] bytes, char[] password) throws Exception {
        validatePassword(password);
        if (bytes == null || bytes.length < HEADER_SIZE + 16 || bytes.length > SchemaValidator.MAX_BYTES + HEADER_SIZE + 16) throw new Exception("حجم النسخة أو صيغتها غير صالح");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (byte b : MAGIC) if (b != buffer.get()) throw new Exception("اختاري ملف نسخة غرس المشفّرة بصيغة .ghars");
        int iterations = buffer.getInt();
        if (iterations != ITERATIONS) throw new Exception("إصدار النسخة غير مدعوم");
        byte[] salt = new byte[16], iv = new byte[12];
        buffer.get(salt); buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
        byte[] key = derive(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(Arrays.copyOf(bytes, HEADER_SIZE));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public static void validatePassword(char[] password) throws Exception {
        if (password == null || password.length < 8 || password.length > 512) throw new Exception("استخدمي كلمة مرور من 8 إلى 512 حرفاً واحفظيها؛ لا يمكن استعادتها");
    }
    private static byte[] derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
