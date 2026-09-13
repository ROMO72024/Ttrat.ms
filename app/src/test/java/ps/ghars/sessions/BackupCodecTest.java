package ps.ghars.sessions;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class BackupCodecTest {
    private static final char[] PASSWORD = "test-only-password-غرس".toCharArray();
    private static final String DATA = "{\"version\":1,\"notes\":\"جلسة نطق • تقدم ملحوظ\"}";
    @Test public void arabicRoundTripAndRandomizedEncryption() throws Exception {
        byte[] a = BackupCodec.encrypt(DATA, PASSWORD);
        byte[] b = BackupCodec.encrypt(DATA, PASSWORD);
        assertEquals(DATA, BackupCodec.decrypt(a, PASSWORD));
        assertEquals(DATA, BackupCodec.decrypt(b, PASSWORD));
        assertFalse(Arrays.equals(a, b));
        assertFalse(new String(a, java.nio.charset.StandardCharsets.UTF_8).contains("تقدم"));
    }
    @Test public void wrongPasswordNeverReturnsPlaintext() throws Exception {
        byte[] encoded = BackupCodec.encrypt(DATA, PASSWORD);
        assertThrows(Exception.class, () -> BackupCodec.decrypt(encoded, "wrong-password".toCharArray()));
    }
    @Test public void alteredHeaderAndCiphertextAreRejected() throws Exception {
        byte[] encoded = BackupCodec.encrypt(DATA, PASSWORD);
        byte[] header = encoded.clone(); header[12] ^= 1;
        byte[] body = encoded.clone(); body[body.length - 1] ^= 1;
        assertThrows(Exception.class, () -> BackupCodec.decrypt(header, PASSWORD));
        assertThrows(Exception.class, () -> BackupCodec.decrypt(body, PASSWORD));
    }
    @Test public void invalidFilesAndShortPasswordsAreRejected() {
        assertThrows(Exception.class, () -> BackupCodec.decrypt(new byte[8], PASSWORD));
        assertThrows(Exception.class, () -> BackupCodec.decrypt(new byte[100], PASSWORD));
        assertThrows(Exception.class, () -> BackupCodec.encrypt(DATA, "short".toCharArray()));
    }
}
