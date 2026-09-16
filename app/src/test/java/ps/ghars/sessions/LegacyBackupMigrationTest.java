package ps.ghars.sessions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyBackupMigrationTest {
    @Test public void portableBackupRetains150LegacyStudentsAndSessionAttendance() throws Exception {
        JSONObject legacy = new JSONObject("{\"version\":1,\"students\":[],\"sessions\":[],\"reminders\":[],\"settings\":{\"defaultReminder\":-1}}");
        JSONArray students = legacy.getJSONArray("students");
        for (int i=0;i<150;i++) students.put(new JSONObject()
            .put("id","s"+i).put("name","طالب اختبار "+i).put("classroom","الروضة")
            .put("shift","morning").put("phone","0590000000").put("notes","ملاحظة محفوظة "+i)
            .put("goals","هدف محفوظ "+i).put("archived",false)
            .put("plans",new JSONObject("{\"speech\":{\"total\":20,\"weekly\":2},\"behavior\":{\"total\":0,\"weekly\":0}}")));
        legacy.getJSONArray("sessions").put(new JSONObject("{\"id\":\"x1\",\"type\":\"speech\",\"start\":\"2026-10-01T09:00\",\"duration\":30,\"reminderMinutes\":-1,\"status\":\"completed\",\"note\":\"جلسة قديمة\",\"seriesId\":\"\",\"studentIds\":[\"s0\"],\"attendance\":{\"s0\":{\"status\":\"present\",\"note\":\"حضور محفوظ\",\"progress\":3}}}"));
        legacy = SchemaValidator.validate(legacy.toString());
        char[] password = "migration-test-only-123".toCharArray();
        byte[] portable = BackupCodec.encrypt(legacy.toString(),password);
        JSONObject restored = SchemaValidator.validate(BackupCodec.decrypt(portable,password));
        assertEquals(150,restored.getJSONArray("students").length());
        assertTrue(SyncModel.same(legacy,restored));
        assertEquals(1,SyncModel.metrics(restored).getJSONObject("s0").getInt("speechDone"));
        assertFalse(restored.has("_cloud"));
    }
}
