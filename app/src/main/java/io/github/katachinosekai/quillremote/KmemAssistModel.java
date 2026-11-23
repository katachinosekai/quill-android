package io.github.katachinosekai.quillremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The kmem assistant's state as Quill Desktop reports it (pure parts: actions, the save-timeout question, countdown). */
final class KmemAssistModel {
    /** The save-timeout question: text and its labelled choices (index = the value for POST choose). */
    static final class Question {
        final String text;
        final List<String> choices;

        Question(String text, List<String> choices) {
            this.text = text;
            this.choices = choices;
        }
    }

    final String state, waiting, model, modelSource, reason, button;
    final long epoch, rev;
    /** Seconds left of the save, -1 = none. */
    final int saveLeftS;
    final Set<String> actions;
    final Question question;
    /** When this snapshot arrived (for the local countdown). */
    final long at;

    KmemAssistModel(String state, String waiting, String model, String modelSource, long epoch, long rev, int saveLeftS,
                    String reason, String button, Set<String> actions, Question question, long at) {
        this.state = state;
        this.waiting = waiting;
        this.model = model;
        this.modelSource = modelSource;
        this.epoch = epoch;
        this.rev = rev;
        this.saveLeftS = saveLeftS;
        this.reason = reason;
        this.button = button;
        this.actions = actions;
        this.question = question;
        this.at = at;
    }

    boolean can(String action) {
        return actions.contains(action);
    }

    /** Parse the "assistant" object of a reply; actions and question may also sit beside it (body). Null if neither exists. */
    static KmemAssistModel parse(JSONObject body, long now) {
        JSONObject as = body == null ? null : body.optJSONObject("assistant");
        if (as == null) {
            return null;
        }
        JSONArray acts = as.optJSONArray("actions") != null ? as.optJSONArray("actions") : body.optJSONArray("actions");
        JSONObject q = as.optJSONObject("question") != null ? as.optJSONObject("question") : body.optJSONObject("question");
        return new KmemAssistModel(as.optString("state"), as.optString("waiting"), as.optString("model"),
                as.optString("modelSource"), as.optLong("epoch"), as.optLong("rev"),
                as.has("saveLeftS") ? as.optInt("saveLeftS") : -1, as.optString("reason"), as.optString("button"),
                actions(acts), question(q), now);
    }

    static Set<String> actions(JSONArray a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            l.add(a.optString(i));
        }
        return actionSet(l);
    }

    static Set<String> actionSet(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String v : l) {
            if (v != null && !v.isEmpty()) {
                s.add(v);
            }
        }
        return Collections.unmodifiableSet(s);
    }

    static Question question(JSONObject q) {
        if (q == null) {
            return null;
        }
        List<String> c = new ArrayList<>();
        JSONArray arr = q.optJSONArray("choices");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            c.add(arr.optString(i));
        }
        return question(q.optString("text"), c);
    }

    /** A card needs a text and at least one choice; null otherwise. */
    static Question question(String text, List<String> choices) {
        return text == null || text.isEmpty() || choices == null || choices.isEmpty() ? null : new Question(text, choices);
    }

    /** Seconds still left at time now (ms), counting down from the snapshot; -1 = no countdown. */
    int leftS(long now) {
        return left(saveLeftS, at, now);
    }

    static int left(int saveLeftS, long at, long now) {
        if (saveLeftS < 0) {
            return -1;
        }
        return (int) Math.max(0, saveLeftS - Math.max(0, now - at) / 1000);
    }

    /** "saving its handoff, then stopping · 42 s" style state line. */
    String stateLine(long now) {
        String s = KmemModels.stateText(state);
        if ("saving".equals(state)) {
            s = "saving its handoff, then stopping";
        }
        int left = leftS(now);
        return left >= 0 ? s + " · " + left + " s" : s;
    }

    /** Two chat inputs are allowed only when Quill Desktop offers "send". */
    boolean canSend() {
        return can("send");
    }

    /** Next snapshot is newer or different enough that the items must be refetched. */
    boolean itemsChanged(long epoch, long rev) {
        return epoch != this.epoch || rev != this.rev;
    }

    /** True if the reply code means the button was already taken by the other app. */
    static boolean alreadyDone(int code, String error) {
        return code == 409 && "stale".equals(error);
    }

    /** The header's model line: "Model: Online Claude (default)" for the default model, else the model and where it comes from. */
    static String modelLine(String model, String source) {
        if ("New sessions".equals(source)) {
            source = "Online Claude (default)"; // older Quill Desktop; the assistant no longer follows New sessions
        }
        if ("Online Claude (default)".equals(source) && ("default".equals(model) || model.isEmpty())) {
            return "Model: Online Claude (default)";
        }
        return "Model: " + model + (source.isEmpty() ? "" : " (" + source + ")");
    }
}
