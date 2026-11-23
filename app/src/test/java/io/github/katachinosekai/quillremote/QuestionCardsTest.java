package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.Test;

public class QuestionCardsTest {
    static Map<String, Object> approvalMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("options", Arrays.asList("Yes", "No"));
        m.put("stop", Arrays.asList(false, true));
        m.put("recommended", 0);
        return m;
    }

    static Map<String, Object> q(String id, String text, String kind, Object... opts) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("text", text);
        m.put("kind", kind);
        m.put("options", Arrays.asList(opts));
        return m;
    }

    @Test
    public void parseApproval() {
        QuestionCards.Approval a = QuestionCards.parseApproval(approvalMap());
        assertEquals(Arrays.asList("Yes", "No"), a.options);
        assertTrue(a.stop[1]);
        assertFalse(a.stop[0]);
        assertEquals(0, a.recommended);
        assertFalse(a.dontRun);
    }

    @Test
    public void parseApprovalOddInput() {
        assertNull(QuestionCards.parseApproval(null));
        assertNull(QuestionCards.parseApproval(new HashMap<String, Object>()));
        Map<String, Object> m = approvalMap();
        m.put("recommended", -1);
        m.put("dontRun", true);
        m.put("stop", Arrays.asList(false));
        QuestionCards.Approval a = QuestionCards.parseApproval(m);
        assertEquals(-1, a.recommended);
        assertTrue(a.dontRun);
        assertFalse(a.stop[1]);
        m.put("recommended", 7);
        assertEquals(-1, QuestionCards.parseApproval(m).recommended);
    }

    @Test
    public void parseQuestions() {
        Map<String, Object> second = q("b", "Which?", "multi", "x", "y");
        second.put("num", 4);
        List<QuestionCards.Question> qs = QuestionCards.parseQuestions(Arrays.asList(q("a", "Go?", "choice", "p"), second, "junk", new HashMap<String, Object>()));
        assertEquals(2, qs.size());
        assertEquals(1, qs.get(0).num);
        assertEquals(4, qs.get(1).num);
        assertEquals("multi", qs.get(1).kind);
        assertEquals(Arrays.asList("x", "y"), qs.get(1).options);
        assertTrue(QuestionCards.parseQuestions(null).isEmpty());
    }

    @Test
    public void quickChoicesSuppressedInTurnWithPendingPlan() {
        List<String> kinds = Arrays.asList("user", "assistant", "plan", "user", "assistant", "plan", "assistant");
        List<Boolean> pend = Arrays.asList(false, false, true, false, false, false, false);
        assertEquals(new java.util.HashSet<>(Arrays.asList(1)), QuestionCards.quickSuppressed(kinds, pend));
        // plan item before the reply text of the same turn
        List<String> k2 = Arrays.asList("user", "plan", "assistant");
        assertEquals(new java.util.HashSet<>(Arrays.asList(2)), QuestionCards.quickSuppressed(k2, Arrays.asList(false, true, false)));
        assertTrue(QuestionCards.quickSuppressed(Arrays.asList("assistant"), Arrays.asList(false)).isEmpty());
    }

    @Test
    public void setKeyStableUntilQuestionsChange() {
        QuestionCards.Approval a = QuestionCards.parseApproval(approvalMap());
        List<QuestionCards.Question> one = QuestionCards.parseQuestions(Arrays.asList(q("a", "Go?", "choice", "p", "r")));
        String k = QuestionCards.setKey("plan", a, one);
        assertEquals(k, QuestionCards.setKey("plan", QuestionCards.parseApproval(approvalMap()), QuestionCards.parseQuestions(Arrays.asList(q("a", "Go?", "choice", "p", "r")))));
        assertNotEquals(k, QuestionCards.setKey("plan", a, QuestionCards.parseQuestions(Arrays.asList(q("a", "Go?", "choice", "p", "z")))));
        assertNotEquals(k, QuestionCards.setKey("plan", null, one));
        assertNotEquals(k, QuestionCards.setKey("plan 2", a, one));
    }

    @Test
    public void goAheadBody() {
        QuestionCards.Approval a = QuestionCards.parseApproval(approvalMap());
        Map<String, Object> ans = new HashMap<>();
        ans.put("a", 1);
        ans.put("b", new TreeSet<>(Arrays.asList(0, 2)));
        ans.put("seen:a", true);
        ans.put("approval:pick", 0);
        ans.put("changes:", "");
        assertEquals("approvePlan", QuestionCards.action(0));
        Map<String, Object> body = QuestionCards.body(a, 0, new ArrayList<>(), ans, "");
        assertEquals(0, body.get("approvalChoice"));
        Map<?, ?> pa = (Map<?, ?>) body.get("planAnswers");
        assertEquals(2, pa.size());
        assertFalse(body.containsKey("text"));
    }

    @Test
    public void goAheadExtrasCarryChangesText() {
        Map<String, Object> e = QuestionCards.goAheadExtras(2, "  keep it small ");
        assertEquals(2, e.get("choice"));
        assertEquals("keep it small", e.get("text"));
        Map<String, Object> none = QuestionCards.goAheadExtras(null, "   ");
        assertTrue(none.isEmpty());
        assertEquals("x", QuestionCards.goAheadExtras(null, "x").get("text"));
        assertFalse(QuestionCards.goAheadExtras(null, "x").containsKey("choice"));
    }

    @Test
    public void goAheadWithoutAnswersHasNoPlanAnswers() {
        Map<String, Object> body = QuestionCards.body(QuestionCards.parseApproval(approvalMap()), 1, new ArrayList<>(), new HashMap<>(), " fix it ");
        assertEquals(1, body.get("approvalChoice"));
        assertFalse(body.containsKey("planAnswers"));
        assertEquals("fix it", body.get("text"));
    }

    @Test
    public void dontRunIsRejectWithAnswersInText() {
        List<QuestionCards.Question> qs = QuestionCards.parseQuestions(Arrays.asList(q("a", "Which?", "choice", "red", "blue"), q("b", "Extras", "multi", "x", "y")));
        Map<String, Object> ans = new HashMap<>();
        ans.put("a", 1);
        ans.put("b", new TreeSet<>(Arrays.asList(0, 1)));
        assertEquals("rejectPlan", QuestionCards.action(QuestionCards.DONT_RUN));
        Map<String, Object> body = QuestionCards.body(null, QuestionCards.DONT_RUN, qs, ans, "later");
        assertFalse(body.containsKey("approvalChoice"));
        assertFalse(body.containsKey("planAnswers"));
        assertEquals("later\nAnswers to your questions: 1. Which?: blue; 2. Extras: x, y", body.get("text"));
        assertTrue(QuestionCards.body(null, QuestionCards.DONT_RUN, qs, new HashMap<>(), "").isEmpty());
    }

    @Test
    public void clickGuardBlocksFor600Ms() {
        QuestionCards.ClickGuard g = new QuestionCards.ClickGuard(1000);
        assertFalse(g.allows(1000));
        assertFalse(g.allows(1599));
        assertTrue(g.allows(1600));
    }
}
