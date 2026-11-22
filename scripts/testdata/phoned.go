package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type item map[string]any

type session struct {
	ID, Title, Folder, Group, Status string
	Kmem, Orig, Suggest              string
	Epoch                            int
	Rev                              int64
	Items                            []item
	Queue                            []string
}

type change struct {
	cursor int64
	body   map[string]any
}

type server struct {
	mu        sync.Mutex
	wake      *sync.Cond
	key       string
	boot      string
	cursor    int64
	changes   []change
	sessions  []*session
	control   bool
	paused    bool
	noAPI     bool
	downUntil time.Time
	noHandoff bool
	lockOn    bool
	unlockTok string
	wrongPw   int
	handoffs  []map[string]any
	events    []map[string]any
	nextID    int64
	idem      map[string][2]string
	log       []string
}

func (s *server) find(id string) *session {
	for _, x := range s.sessions {
		if x.ID == id {
			return x
		}
	}
	return nil
}

func (s *server) sessionJSON(x *session) map[string]any {
	pending := map[string]int{"permissions": 0, "questions": 0, "plans": 0}
	for _, it := range x.Items {
		if r, ok := it["request"].(map[string]any); ok && r["state"] == "pending" {
			switch it["kind"] {
			case "permission":
				pending["permissions"]++
			case "question":
				pending["questions"]++
			case "plan":
				pending["plans"]++
			}
		}
	}
	m := map[string]any{"id": x.ID, "title": x.Title, "folder": "/home/user/Projects/" + x.Folder, "folderName": x.Folder,
		"group": x.Group, "status": x.Status, "busy": x.Status == "running", "model": "opus", "mode": "default", "backend": "online",
		"context": map[string]int{"used": 45000, "max": 200000}, "pending": pending, "queue": len(x.Queue), "background": 0,
		"epoch": x.Epoch, "rev": x.Rev, "updatedMs": time.Now().UnixMilli(), "focused": x == s.sessions[0], "tokens": 1234000}
	if x.Kmem != "" {
		m["kmem"] = x.Kmem
	}
	if x.Orig != "" {
		m["renamed"] = true
	}
	m["project"] = x.Group
	if x.Suggest != "" {
		m["suggestion"] = x.Suggest
	}
	return m
}

func (s *server) touch(x *session, it item) {
	x.Rev++
	if it != nil {
		it["rev"] = x.Rev
	}
	s.emit(map[string]any{"type": "session", "session": s.sessionJSON(x)})
}

func (s *server) emit(body map[string]any) {
	s.cursor++
	s.changes = append(s.changes, change{s.cursor, body})
	s.wake.Broadcast()
}

func (s *server) add(x *session, it item) item {
	seq := 1
	if n := len(x.Items); n > 0 {
		seq = x.Items[n-1]["seq"].(int) + 1
	}
	it["seq"] = seq
	if k := it["kind"]; (k == "user" || k == "assistant") && it["timeMs"] == nil {
		it["timeMs"] = time.Now().UnixMilli()
	}
	x.Items = append(x.Items, it)
	s.touch(x, it)
	return it
}

func (s *server) usage() map[string]any {
	now := time.Now().UnixMilli()
	until := int64(0)
	if s.paused {
		until = now + 1800000
	}
	return map[string]any{"paused": s.paused, "pausedUntilMs": until, "fiveHour": map[string]any{"pct": 64, "resetsAtMs": now + 3600000},
		"week": map[string]any{"pct": 14, "resetsAtMs": now + 86400000}, "credits": "available", "todayTokens": 5600000, "weekTokens": 42000000}
}

func reply(w http.ResponseWriter, code int, body map[string]any) {
	body["v"] = 1
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(body)
}

func fail(w http.ResponseWriter, code int, c string) {
	reply(w, code, map[string]any{"error": map[string]any{"code": c, "message": c}})
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Quill-Key")), []byte(s.key)) != 1 {
		fail(w, 403, "forbidden")
		return
	}
	if r.URL.Path == "/debug/lock" {
		s.mu.Lock()
		s.lockOn = r.URL.Query().Get("on") == "1"
		s.unlockTok, s.wrongPw = "", 0
		s.mu.Unlock()
		reply(w, 200, map[string]any{})
		return
	}
	if r.URL.Path == "/debug/relock" {
		s.mu.Lock()
		s.unlockTok = ""
		s.mu.Unlock()
		reply(w, 200, map[string]any{})
		return
	}
	s.mu.Lock()
	locked := s.lockOn && (s.unlockTok == "" || r.Header.Get("X-Quill-Unlock") != s.unlockTok)
	s.mu.Unlock()
	if locked && !strings.HasPrefix(r.URL.Path, "/debug/") && r.URL.Path != "/phone/hello" && r.URL.Path != "/phone/unlock" {
		fail(w, 401, "locked")
		return
	}
	if r.URL.Path == "/debug/down" {
		// drop every /phone/ connection for ?s= seconds (Quill Desktop unreachable, e.g. away from home)
		n, _ := strconv.Atoi(r.URL.Query().Get("s"))
		s.mu.Lock()
		s.downUntil = time.Now().Add(time.Duration(n) * time.Second)
		s.mu.Unlock()
		reply(w, 200, map[string]any{})
		return
	}
	s.mu.Lock()
	down := time.Now().Before(s.downUntil)
	s.mu.Unlock()
	if down && strings.HasPrefix(r.URL.Path, "/phone/") {
		if hj, ok := w.(http.Hijacker); ok {
			if c, _, err := hj.Hijack(); err == nil {
				c.Close()
				return
			}
		}
		panic(http.ErrAbortHandler)
	}
	if r.URL.Path == "/debug/noapi" {
		s.mu.Lock()
		s.noAPI = r.URL.Query().Get("on") == "1"
		s.mu.Unlock()
		reply(w, 200, map[string]any{})
		return
	}
	s.mu.Lock()
	noAPI := s.noAPI
	s.mu.Unlock()
	if noAPI && strings.HasPrefix(r.URL.Path, "/phone/") {
		fail(w, 404, "not_found")
		return
	}
	var body map[string]any
	raw := []byte{}
	if r.Method == http.MethodPost {
		raw, _ = io.ReadAll(io.LimitReader(r.Body, 65537))
		if len(raw) > 65536 {
			fail(w, 413, "too_large")
			return
		}
		if json.Unmarshal(raw, &body) != nil {
			fail(w, 400, "bad_request")
			return
		}
		k := r.Header.Get("Idempotency-Key")
		if k == "" {
			fail(w, 400, "idempotency_key_missing")
			return
		}
		sum := sha256.Sum256(append([]byte(r.Method+" "+r.URL.Path+" "), raw...))
		s.mu.Lock()
		if prev, ok := s.idem[k]; ok {
			s.mu.Unlock()
			if prev[0] != hex.EncodeToString(sum[:]) {
				fail(w, 422, "idempotency_mismatch")
				return
			}
			w.Header().Set("Idempotent-Replay", "true")
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, prev[1])
			return
		}
		s.mu.Unlock()
		rec := &recorder{ResponseWriter: w}
		s.post(rec, r, body)
		s.mu.Lock()
		s.idem[k] = [2]string{hex.EncodeToString(sum[:]), rec.buf.String()}
		s.log = append(s.log, r.URL.Path+" "+string(raw))
		s.mu.Unlock()
		return
	}
	s.get(w, r)
}

type recorder struct {
	http.ResponseWriter
	buf strings.Builder
}

func (r *recorder) Write(b []byte) (int, error) {
	r.buf.Write(b)
	return r.ResponseWriter.Write(b)
}

func (s *server) get(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	q := r.URL.Query()
	switch {
	case p == "/phone/sessions":
		s.mu.Lock()
		defer s.mu.Unlock()
		var list []any
		for _, x := range s.sessions {
			list = append(list, s.sessionJSON(x))
		}
		reply(w, 200, map[string]any{"boot": s.boot, "cursor": s.cursor, "control": s.control, "usage": s.usage(),
			"models":   map[string]any{"online": []string{"opus", "sonnet"}, "local": []string{}},
			"modes":    map[string]any{"online": []string{"default", "acceptEdits", "plan", "auto"}, "local": []string{"default"}},
			"projects": s.projects(),
			"actions":  []string{"rename", "resume", "end", "delete", "project"},
			"sessions": list})
	case p == "/phone/handoffs":
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.noHandoff {
			fail(w, 404, "not_found")
			return
		}
		reply(w, 200, map[string]any{"handoffs": s.handoffs})
	case p == "/phone/handoffs/slug":
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.noHandoff {
			fail(w, 404, "not_found")
			return
		}
		reply(w, 200, map[string]any{"slug": slugOf(q.Get("title"))})
	case p == "/debug/nohandoffs":
		s.mu.Lock()
		defer s.mu.Unlock()
		s.noHandoff = q.Get("on") == "1"
		reply(w, 200, map[string]any{})
	case p == "/phone/folders":
		reply(w, 200, map[string]any{"folders": []any{map[string]string{"path": "/home/user/Projects/demo", "name": "demo"}}})
	case p == "/phone/stream":
		cur, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
		wait, _ := strconv.Atoi(q.Get("wait"))
		if wait <= 0 || wait > 25 {
			wait = 25
		}
		deadline := time.Now().Add(time.Duration(wait) * time.Second)
		s.mu.Lock()
		defer s.mu.Unlock()
		go func() { time.Sleep(time.Until(deadline)); s.mu.Lock(); s.wake.Broadcast(); s.mu.Unlock() }()
		for s.cursor <= cur && time.Now().Before(deadline) {
			s.wake.Wait()
		}
		var out []any
		for _, c := range s.changes {
			if c.cursor > cur {
				out = append(out, c.body)
			}
		}
		if out == nil {
			out = []any{}
		}
		reply(w, 200, map[string]any{"boot": s.boot, "cursor": s.cursor, "reset": cur > s.cursor, "changes": out})
	case strings.HasPrefix(p, "/phone/session/") && strings.HasSuffix(p, "/items"):
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/phone/session/"), "/items")
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(id)
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		items := []item{}
		reset := false
		if q.Has("epoch") {
			ep, _ := strconv.Atoi(q.Get("epoch"))
			since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
			if ep != x.Epoch {
				reset = true
				items = x.Items
			} else {
				for _, it := range x.Items {
					if it["rev"].(int64) > since {
						items = append(items, it)
					}
				}
			}
		} else {
			items = x.Items
		}
		// /debug/blank: incremental fetches see an empty reply (a lost update); a full window has its text.
		out := make([]item, 0, len(items))
		for _, it := range items {
			if full, ok := it["healText"].(string); ok {
				c := item{}
				for k, v := range it {
					if k != "healText" {
						c[k] = v
					}
				}
				if q.Has("epoch") && !reset {
					c["text"] = ""
				} else {
					c["text"] = full
				}
				it = c
			}
			out = append(out, it)
		}
		items = out
		queue := []any{}
		for i, t := range x.Queue {
			queue = append(queue, map[string]any{"index": i, "text": t})
		}
		reply(w, 200, map[string]any{"id": x.ID, "epoch": x.Epoch, "rev": x.Rev, "reset": reset, "hasMore": false, "items": items, "queue": queue})
	case p == "/debug/busy":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		if q.Get("on") == "1" {
			x.Status = "running"
		} else {
			x.Status = "idle"
		}
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/control":
		s.mu.Lock()
		defer s.mu.Unlock()
		s.control = q.Get("on") == "1"
		s.emit(map[string]any{"type": "control", "control": s.control})
		reply(w, 200, map[string]any{})
	case p == "/debug/pause":
		s.mu.Lock()
		defer s.mu.Unlock()
		s.paused = q.Get("on") == "1"
		s.emit(map[string]any{"type": "usage", "usage": s.usage()})
		reply(w, 200, map[string]any{})
	case p == "/debug/long":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		for i := 1; i <= 40; i++ {
			s.add(x, item{"kind": "assistant", "text": fmt.Sprintf("Filler line %d for scrolling.", i)})
		}
		s.add(x, item{"kind": "assistant", "text": "Newest filler message."})
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/blank":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		it := s.add(x, item{"kind": "assistant", "text": "", "healText": "Healed reply after a lost update."})
		s.touch(x, it)
		reply(w, 200, map[string]any{})
	case p == "/debug/planq":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		qs := []any{
			map[string]any{"id": 1, "text": "Should rule 21 stay as it is?", "kind": "yesno", "recommended": -1},
			map[string]any{"id": 2, "text": "Which channel?", "kind": "single", "options": []string{"Stable", "Beta"}, "recommended": 1},
			map[string]any{"id": 3, "text": "Which parts?", "kind": "multi", "options": []string{"a: Plan-first", "b: Auto mode"}, "recommended": -1},
			map[string]any{"id": 4, "text": "Anything else?", "kind": "text", "recommended": -1},
			map[string]any{"id": 5, "num": 7, "text": "Push the tag too?", "kind": "yesno", "options": []string{"Yes", "No"}, "recommended": 1},
		}
		s.add(x, item{"kind": "plan", "text": "1. Do it\n\nQuestions:\n1. Should rule 21 stay as it is?", "request": map[string]any{"id": "req_planq", "state": "pending", "questions": qs}})
		x.Status = "waiting"
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/plan":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		req := map[string]any{"id": "req_plan" + q.Get("n"), "state": "pending", "choices": []string{"Ship as v0.1.18", "Replace the v0.1.17 files"}}
		if q.Get("rec") != "" {
			r, _ := strconv.Atoi(q.Get("rec"))
			req["recommended"] = r
		}
		s.add(x, item{"kind": "plan", "text": "1. Build\n2. Publish\n\nShip as v0.1.18 (recommended), or replace the v0.1.17 files?", "request": req})
		x.Status = "waiting"
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/perm":
		// a fresh pending permission request (for the notification's Allow / Deny buttons)
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		s.add(x, item{"kind": "permission", "request": map[string]any{"id": q.Get("req"), "state": "pending", "tool": "Bash", "detail": "rm -rf build/", "canAllowAlways": true}})
		x.Status = "waiting"
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/suggest":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		x.Suggest = q.Get("text")
		s.touch(x, nil)
		reply(w, 200, map[string]any{})
	case p == "/debug/event":
		s.mu.Lock()
		defer s.mu.Unlock()
		x := s.find(q.Get("id"))
		if x == nil {
			fail(w, 404, "not_found")
			return
		}
		s.nextID++
		s.events = append(s.events, map[string]any{"id": s.nextID, "time": time.Now().UnixMilli(), "type": q.Get("type"),
			"rc": "", "quill": x.ID, "title": x.Title, "folder": x.Group})
		s.wake.Broadcast()
		reply(w, 200, map[string]any{})
	case p == "/events":
		since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
		wait, _ := strconv.Atoi(q.Get("wait"))
		if wait < 0 || wait > 25 {
			wait = 25
		}
		deadline := time.Now().Add(time.Duration(wait) * time.Second)
		s.mu.Lock()
		defer s.mu.Unlock()
		go func() { time.Sleep(time.Until(deadline)); s.mu.Lock(); s.wake.Broadcast(); s.mu.Unlock() }()
		for s.nextID < since && time.Now().Before(deadline) {
			s.wake.Wait()
		}
		out := []any{}
		for _, e := range s.events {
			if e["id"].(int64) >= since {
				out = append(out, e)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"version": 1, "boot": s.boot, "next": s.nextID + 1, "events": out})
	case p == "/debug/log":
		s.mu.Lock()
		defer s.mu.Unlock()
		reply(w, 200, map[string]any{"log": s.log})
	default:
		fail(w, 404, "not_found")
	}
}

func (s *server) post(w http.ResponseWriter, r *http.Request, body map[string]any) {
	p := r.URL.Path
	s.mu.Lock()
	defer s.mu.Unlock()
	if p == "/phone/hello" {
		need := s.lockOn && (s.unlockTok == "" || r.Header.Get("X-Quill-Unlock") != s.unlockTok)
		reply(w, 200, map[string]any{"phoneId": "a1b2c3d4", "control": s.control, "boot": s.boot, "quill": "9.9.9", "unlock": need})
		return
	}
	if p == "/phone/unlock" {
		if pw, _ := body["password"].(string); pw != "test-password-1" {
			s.wrongPw++
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(403)
			json.NewEncoder(w).Encode(map[string]any{"v": 1, "error": map[string]any{"code": "wrong_password", "message": fmt.Sprintf("%d of 5 tries", s.wrongPw)}})
			return
		}
		s.unlockTok = fmt.Sprintf("tok%d", time.Now().UnixNano())
		reply(w, 200, map[string]any{"token": s.unlockTok, "idleMin": 15, "needed": true})
		return
	}
	if p == "/phone/unlock/touch" {
		reply(w, 200, map[string]any{"ok": true, "idleMin": 15})
		return
	}
	if !s.control {
		fail(w, 403, "control_off")
		return
	}
	if p == "/phone/usage/resume" {
		s.paused = false
		u := s.usage()
		s.emit(map[string]any{"type": "usage", "usage": u})
		reply(w, 200, map[string]any{"usage": u})
		return
	}
	if p == "/phone/sessions/new" {
		folder, _ := body["folder"].(string)
		if !strings.HasPrefix(folder, "/home/user/Projects/") {
			fail(w, 400, "bad_request")
			return
		}
		title, kmem, note := "New session", "", ""
		if st, has := body["start"]; has {
			m, _ := st.(map[string]any)
			switch m["kmem"] {
			case "resume":
				name, _ := m["name"].(string)
				if name == "quill-tester" {
					fail(w, 409, "blocked")
					return
				}
				if name == "" {
					fail(w, 422, "bad_request")
					return
				}
				title, kmem, note = name, name, "Resumed kmem handoff "+name
			case "new":
				t, _ := m["title"].(string)
				if strings.TrimSpace(t) != "" {
					title = strings.TrimSpace(t)
				}
				kmem = slugOf(title)
				note = "kmem name: " + kmem
			default:
				fail(w, 422, "bad_request")
				return
			}
		}
		s.log = append(s.log, fmt.Sprintf("new session start=%v", body["start"]))
		x := &session{ID: s.newID(), Title: title, Folder: strings.TrimPrefix(folder, "/home/user/Projects/"),
			Group: "Active", Status: "idle", Kmem: kmem, Epoch: 1}
		s.sessions = append([]*session{x}, s.sessions...)
		s.add(x, item{"kind": "notice", "text": fmt.Sprintf("Started with %v · %v · %v", body["backend"], body["model"], body["mode"])})
		if note != "" {
			s.add(x, item{"kind": "notice", "text": note})
		}
		reply(w, 200, map[string]any{"session": s.sessionJSON(x)})
		return
	}
	if strings.HasPrefix(p, "/phone/handoffs/") {
		hp := strings.Split(strings.TrimPrefix(p, "/phone/handoffs/"), "/")
		at := -1
		for i, h := range s.handoffs {
			if len(hp) == 2 && h["name"] == hp[0] {
				at = i
			}
		}
		if at < 0 {
			fail(w, 404, "not_found")
			return
		}
		h := s.handoffs[at]
		switch hp[1] {
		case "resume":
			if o, ok := h["openIn"].(map[string]any); ok {
				reply(w, 200, map[string]any{"session": s.sessionJSON(s.find(o["id"].(string)))})
				return
			}
			n := &session{ID: s.newID(), Title: hp[0], Folder: "demo", Group: "Quill", Status: "idle", Kmem: hp[0], Epoch: 1}
			s.sessions = append([]*session{n}, s.sessions...)
			s.handoffs = append(s.handoffs[:at], s.handoffs[at+1:]...)
			s.add(n, item{"kind": "notice", "text": "Resumed fresh from handoff " + hp[0]})
			s.touch(n, nil)
			reply(w, 200, map[string]any{"session": s.sessionJSON(n)})
		case "delete":
			if h["openIn"] != nil {
				fail(w, 409, "blocked")
				return
			}
			s.handoffs = append(s.handoffs[:at], s.handoffs[at+1:]...)
			reply(w, 200, map[string]any{"removed": hp[0]})
		default:
			fail(w, 404, "not_found")
		}
		return
	}
	parts := strings.Split(strings.TrimPrefix(p, "/phone/session/"), "/")
	if len(parts) < 2 || !strings.HasPrefix(p, "/phone/session/") {
		fail(w, 404, "not_found")
		return
	}
	x := s.find(parts[0])
	if x == nil {
		fail(w, 404, "not_found")
		return
	}
	switch parts[1] {
	case "send":
		text, _ := body["text"].(string)
		if strings.TrimSpace(text) == "" {
			fail(w, 400, "bad_request")
			return
		}
		if x.Status == "running" {
			x.Queue = append(x.Queue, text)
			s.touch(x, nil)
			reply(w, 200, map[string]any{"queued": true})
			return
		}
		s.add(x, item{"kind": "user", "text": text})
		x.Status = "running"
		s.touch(x, nil)
		go s.answerLater(x, text)
		reply(w, 200, map[string]any{"queued": false})
	case "answer":
		action, _ := body["action"].(string)
		if action == "choice" {
			seq := int(body["seq"].(float64))
			idx := int(body["index"].(float64))
			for _, it := range x.Items {
				if it["seq"] == seq && it["choicesOpen"] == true {
					it["choicesOpen"] = false
					s.touch(x, it)
					opt := it["choices"].([]string)[idx]
					s.add(x, item{"kind": "user", "text": opt})
					reply(w, 200, map[string]any{"item": it})
					return
				}
			}
			fail(w, 409, "stale")
			return
		}
		id, _ := body["requestId"].(string)
		for _, it := range x.Items {
			req, ok := it["request"].(map[string]any)
			if !ok || req["id"] != id {
				continue
			}
			if req["state"] != "pending" {
				fail(w, 409, "stale")
				return
			}
			if c, has := body["choice"]; has {
				opts, _ := req["choices"].([]string)
				ci, isNum := c.(float64)
				if action != "approvePlan" || !isNum || int(ci) < 0 || int(ci) >= len(opts) {
					fail(w, 422, "bad_request")
					return
				}
			}
			states := map[string]string{"allow": "allowed", "allowAlways": "allowedAlways", "deny": "denied", "approvePlan": "allowed",
				"rejectPlan": "denied", "answer": "allowed", "rotate": "allowed", "dismiss": "denied"}
			req["state"] = states[action]
			s.touch(x, it)
			if x.Status == "waiting" {
				x.Status = "idle"
				s.touch(x, nil)
			}
			reply(w, 200, map[string]any{"item": it})
			return
		}
		fail(w, 404, "not_found")
	case "queue":
		if len(parts) < 3 {
			fail(w, 404, "not_found")
			return
		}
		idx := int(body["index"].(float64))
		text, _ := body["text"].(string)
		if idx < 0 || idx >= len(x.Queue) || x.Queue[idx] != text {
			fail(w, 409, "stale")
			return
		}
		x.Queue = append(x.Queue[:idx], x.Queue[idx+1:]...)
		if parts[2] == "send-now" {
			s.add(x, item{"kind": "user", "text": text})
		}
		s.touch(x, nil)
		queue := []any{}
		for i, t := range x.Queue {
			queue = append(queue, map[string]any{"index": i, "text": t})
		}
		reply(w, 200, map[string]any{"queue": queue})
	case "rename":
		t, _ := body["title"].(string)
		if len(t) > 200 {
			fail(w, 400, "bad_request")
			return
		}
		if t == "" {
			if x.Orig != "" {
				x.Title, x.Orig = x.Orig, ""
			}
		} else {
			if x.Orig == "" {
				x.Orig = x.Title
			}
			x.Title = t
		}
		s.touch(x, nil)
		reply(w, 200, map[string]any{"session": s.sessionJSON(x)})
	case "resume":
		s.add(x, item{"kind": "notice", "text": "Resumed"})
		s.touch(x, nil)
		reply(w, 200, map[string]any{"session": s.sessionJSON(x)})
	case "end":
		for i, y := range s.sessions {
			if y == x {
				s.sessions = append(s.sessions[:i], s.sessions[i+1:]...)
				break
			}
		}
		name := x.Kmem
		if name == "" {
			name = slugOf(x.Title)
		}
		s.handoffs = append([]map[string]any{{"name": name, "savedMs": time.Now().UnixMilli(), "openIn": nil}}, s.handoffs...)
		s.emit(map[string]any{"type": "removed", "id": x.ID})
		reply(w, 200, map[string]any{"saving": true})
	case "delete":
		for i, y := range s.sessions {
			if y == x {
				s.sessions = append(s.sessions[:i], s.sessions[i+1:]...)
				break
			}
		}
		s.emit(map[string]any{"type": "removed", "id": x.ID})
		reply(w, 200, map[string]any{"removed": x.ID})
	case "project":
		pr, has := body["project"].(string)
		if !has || len(pr) > 200 {
			fail(w, 400, "bad_request")
			return
		}
		if pr == "" {
			pr = "Other"
		}
		x.Group = pr
		s.touch(x, nil)
		reply(w, 200, map[string]any{"session": s.sessionJSON(x)})
	case "interrupt":
		was := x.Status == "running"
		x.Status = "idle"
		s.touch(x, nil)
		reply(w, 200, map[string]any{"wasBusy": was})
	default:
		fail(w, 404, "not_found")
	}
}

func (s *server) answerLater(x *session, text string) {
	time.Sleep(400 * time.Millisecond)
	s.mu.Lock()
	it := s.add(x, item{"kind": "assistant", "text": "Working on: " + text, "streaming": true})
	s.mu.Unlock()
	time.Sleep(400 * time.Millisecond)
	s.mu.Lock()
	defer s.mu.Unlock()
	it["text"] = "Done: **" + text + "**.\n\nPush now, or wait for the release?"
	it["streaming"] = false
	it["choices"] = []string{"Push now", "Wait for the release"}
	it["choicesOpen"] = true
	s.touch(x, it)
	x.Status = "idle"
	s.touch(x, nil)
	if len(x.Queue) > 0 {
		next := x.Queue[0]
		x.Queue = x.Queue[1:]
		s.add(x, item{"kind": "user", "text": next})
	}
}

func cert() (tls.Certificate, string) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "phoned"}, NotBefore: time.Now().Add(-time.Hour),
		NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, _ := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	sum := sha256.Sum256(der)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k}, hex.EncodeToString(sum[:])
}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: phoned <port> <key>")
		os.Exit(2)
	}
	port, _ := strconv.Atoi(os.Args[1])
	s := &server{key: os.Args[2], boot: strconv.FormatInt(time.Now().UnixNano(), 36), control: true, idem: map[string][2]string{}}
	s.handoffs = []map[string]any{
		{"name": "zomboid-fps", "savedMs": int64(1795250000000), "openIn": nil},
		{"name": "quill-tester", "savedMs": int64(1795240000000), "openIn": map[string]any{"id": "session_A1", "title": "Fix edits"}},
		{"name": "old-notes", "savedMs": int64(1795230000000), "openIn": nil},
	}
	s.wake = sync.NewCond(&s.mu)
	a := &session{ID: "session_A1", Title: "Fix edits", Folder: "demo", Group: "Active", Status: "idle", Kmem: "Quill Tester", Epoch: 1}
	b := &session{ID: "session_C3", Title: "Zomboid launcher", Folder: "zomboid", Group: "Zomboid", Status: "waiting", Epoch: 1}
	c := &session{ID: "session_B2", Title: "Old server notes", Folder: "server", Group: "Archive", Status: "stopped", Epoch: 1}
	s.sessions = []*session{a, b, c}
	s.add(a, item{"kind": "user", "text": "Rename the greeting"})
	s.add(a, item{"kind": "assistant", "text": "I'll update `main.go`."})
	s.add(a, item{"kind": "tool", "tool": map[string]any{"name": "Edit", "id": "t1", "summary": "Edit main.go", "done": true, "isError": false, "result": "updated", "resultTruncated": false},
		"diff": map[string]any{"path": "main.go", "lines": []map[string]string{{"op": "-", "text": "hi"}, {"op": "+", "text": "hello"}}, "truncated": false}})
	s.add(b, item{"kind": "user", "text": "Push the launcher"})
	s.add(b, item{"kind": "permission", "request": map[string]any{"id": "req_push", "state": "pending", "tool": "Bash", "detail": "git push origin master", "canAllowAlways": true}})
	s.add(c, item{"kind": "notice", "text": "Session stopped"})
	cr, fp := cert()
	ln, err := tls.Listen("tcp", ":"+strconv.Itoa(port), &tls.Config{Certificates: []tls.Certificate{cr}})
	if err != nil {
		panic(err)
	}
	fmt.Println(ln.Addr().(*net.TCPAddr).Port, fp)
	go http.Serve(ln, s)
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)
	<-ch
}

func slugOf(t string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(strings.TrimSpace(t)) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
			dash = false
		} else if b.Len() > 0 && !dash {
			b.WriteByte('-')
			dash = true
		}
	}
	return strings.TrimSuffix(b.String(), "-")
}

var newSeq int

func (s *server) newID() string {
	newSeq++
	return fmt.Sprintf("session_N%d", newSeq)
}

func (s *server) projects() []string {
	seen := map[string]bool{}
	var out []string
	for _, x := range s.sessions {
		if !seen[x.Group] {
			seen[x.Group] = true
			out = append(out, x.Group)
		}
	}
	sort.Strings(out)
	return out
}
