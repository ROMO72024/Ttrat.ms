package ps.ghars.sessions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncModelTest {
    private JSONObject student(String id) throws Exception {
        JSONObject s = new JSONObject("{\"name\":\"أحمد محمد علي\",\"classroom\":\"روضة\",\"shift\":\"morning\",\"phone\":\"0591234567\",\"notes\":\"ملاحظات محفوظة\",\"goals\":\"هدف\",\"archived\":false,\"plans\":{\"speech\":{\"total\":20,\"weekly\":2},\"behavior\":{\"total\":0,\"weekly\":0}}}");
        return s.put("id", id);
    }
    private JSONObject state() throws Exception {
        JSONObject root = new JSONObject("{\"version\":1,\"students\":[],\"sessions\":[],\"reminders\":[],\"settings\":{\"defaultReminder\":-1}}");
        root.getJSONArray("students").put(student("s1"));
        root.getJSONArray("sessions").put(new JSONObject("{\"id\":\"x1\",\"type\":\"speech\",\"start\":\"2026-10-01T09:00\",\"duration\":30,\"reminderMinutes\":10,\"status\":\"completed\",\"note\":\"جلسة قديمة\",\"seriesId\":\"\",\"studentIds\":[\"s1\"],\"attendance\":{\"s1\":{\"status\":\"present\",\"note\":\"جيد\",\"progress\":3}}}"));
        root.getJSONArray("reminders").put(new JSONObject("{\"id\":\"r1\",\"title\":\"تذكير خاص\",\"at\":\"2026-10-02T10:00\",\"enabled\":true}"));
        return SchemaValidator.validate(root.toString());
    }
    private JSONObject response(JSONObject source) throws Exception {
        return new JSONObject().put("serverTime", "2026-09-16T10:00:00Z").put("records", SyncModel.records(source)).put("conflicts", new JSONArray());
    }
    private JSONObject withBase(JSONObject root) throws Exception {
        JSONObject base = new JSONObject(); JSONArray records = SyncModel.records(root);
        for (int i=0;i<records.length();i++) { JSONObject r=records.getJSONObject(i); base.put(r.getString("id"), r.getJSONObject("values")); }
        root.put("_cloud", new JSONObject().put("serverId","school").put("accountId","accountA").put("base",base).put("conflicts",new JSONObject()));
        return root;
    }
    @Test public void legacyV1DataAndIdsSurviveFirstAcknowledgement() throws Exception {
        JSONObject old=state(), merged=SyncModel.apply(old,old,response(old));
        assertEquals(1,merged.getInt("version")); assertTrue(SyncModel.same(old.get("students"),merged.get("students")));
        assertTrue(SyncModel.same(old.get("sessions"),merged.get("sessions")));assertTrue(SyncModel.same(old.get("reminders"),merged.get("reminders")));
        assertEquals(0,SyncModel.pending(merged));
    }
    @Test public void onlyStudentAndSessionEntitiesSyncNotPersonalRemindersOrCodes() throws Exception {
        JSONArray request=SyncModel.requestRecords(state());assertEquals(2,request.length());
        assertFalse(request.toString().contains("تذكير خاص"));assertTrue(request.toString().contains("ملاحظات محفوظة"));
        assertTrue(request.getJSONObject(0).isNull("base"));
    }
    @Test public void remotePlanAndInFlightLocalClassBothSurvive() throws Exception {
        JSONObject sent=withBase(state()),current=SyncModel.copy(sent),remote=SyncModel.copy(sent);
        current.getJSONArray("students").getJSONObject(0).put("classroom","صف جديد");
        remote.getJSONArray("students").getJSONObject(0).getJSONObject("plans").getJSONObject("speech").put("total",30);
        JSONObject result=SyncModel.apply(sent,current,response(remote)),s=result.getJSONArray("students").getJSONObject(0);
        assertEquals("صف جديد",s.getString("classroom"));assertEquals(30,s.getJSONObject("plans").getJSONObject("speech").getInt("total"));
        assertEquals(1,SyncModel.pending(result));
    }
    @Test public void inFlightEditsToSameFieldBecomeVisibleConflicts() throws Exception {
        JSONObject sent=withBase(state()),current=SyncModel.copy(sent),remote=SyncModel.copy(sent);
        current.getJSONArray("students").getJSONObject(0).put("name","تعديل الهاتف");remote.getJSONArray("students").getJSONObject(0).put("name","تعديل الشيت");
        JSONObject result=SyncModel.apply(sent,current,response(remote));assertEquals("تعديل الهاتف",result.getJSONArray("students").getJSONObject(0).getString("name"));
        assertTrue(result.getJSONObject("_cloud").getJSONObject("conflicts").has("student:s1|name"));
        JSONArray retry=SyncModel.requestRecords(result);assertEquals("تعديل الشيت",retry.getJSONObject(0).getJSONObject("values").getString("name"));
    }
    @Test public void serverConflictKeepsLocalValueUntilExplicitResolution() throws Exception {
        JSONObject sent=withBase(state()),current=SyncModel.copy(sent),remote=SyncModel.copy(sent);
        current.getJSONArray("students").getJSONObject(0).getJSONObject("plans").getJSONObject("speech").put("total",25);
        remote.getJSONArray("students").getJSONObject(0).getJSONObject("plans").getJSONObject("speech").put("total",30);
        JSONObject r=response(remote);r.getJSONArray("conflicts").put(new JSONObject().put("id","student:s1").put("field","speechTotal"));
        JSONObject result=SyncModel.apply(current,current,r);assertEquals(25,result.getJSONArray("students").getJSONObject(0).getJSONObject("plans").getJSONObject("speech").getInt("total"));
        assertTrue(result.getJSONObject("_cloud").getJSONObject("conflicts").has("student:s1|speechTotal"));
    }
    @Test public void anOpenFormDoesNotOverwriteAJustPulledStudentPlan() throws Exception {
        JSONObject before=state(),edited=SyncModel.copy(before),current=SyncModel.copy(before);
        edited.getJSONArray("students").getJSONObject(0).put("phone","12345");current.getJSONArray("students").getJSONObject(0).getJSONObject("plans").getJSONObject("speech").put("total",40);
        JSONObject result=SyncModel.mergeEdit(before,edited,current),s=result.getJSONArray("students").getJSONObject(0);
        assertEquals("12345",s.getString("phone"));assertEquals(40,s.getJSONObject("plans").getJSONObject("speech").getInt("total"));
    }
    @Test public void staleFormCompetingFieldDoesNotReplaceDiskData() throws Exception {
        JSONObject before=state(),edited=SyncModel.copy(before),current=SyncModel.copy(before);
        edited.getJSONArray("students").getJSONObject(0).put("name","هاتف");current.getJSONArray("students").getJSONObject(0).put("name","محاسب");
        try{SyncModel.mergeEdit(before,edited,current);fail("must reject stale edit");}catch(Exception expected){assertTrue(expected.getMessage().contains("تغيّرت"));}
        assertEquals("محاسب",current.getJSONArray("students").getJSONObject(0).getString("name"));
    }
    @Test public void aNewLocalStudentDuringNetworkWaitIsNotDropped() throws Exception {
        JSONObject sent=state(),current=SyncModel.copy(sent);current.getJSONArray("students").put(student("s2"));
        JSONObject result=SyncModel.apply(sent,current,response(sent));assertEquals(2,result.getJSONArray("students").length());assertEquals(1,SyncModel.pending(result));
    }
    @Test public void remoteSessionAndStudentArriveTogetherWithoutDuplicatingAttendance() throws Exception {
        JSONObject local=state(),remote=SyncModel.copy(local);remote.getJSONArray("students").put(student("s2"));
        JSONObject x=SyncModel.copy(remote.getJSONArray("sessions").getJSONObject(0));x.put("id","x2");remote.getJSONArray("sessions").put(x);
        JSONObject result=SyncModel.apply(local,local,response(remote));assertEquals(2,result.getJSONArray("sessions").length());
        assertEquals(2,SyncModel.metrics(result).getJSONObject("s1").getInt("speechDone"));
        result=SyncModel.apply(result,result,response(remote));assertEquals(2,result.getJSONArray("sessions").length());
    }
    @Test public void malformedOrIncompleteResponseCannotBeAcknowledged() throws Exception {
        JSONObject local=state(),r=response(local);r.getJSONArray("records").remove(0);
        try{SyncModel.apply(local,local,r);fail("must reject missing student");}catch(Exception expected){assertTrue(expected.getMessage().contains("غير مكتمل"));}
        assertEquals(1,local.getJSONArray("students").length());
    }
}
