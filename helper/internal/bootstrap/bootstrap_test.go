package bootstrap

import (
	"bytes"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	_ "github.com/mattn/go-sqlite3"
	sqlite3 "github.com/mattn/go-sqlite3"
)

func TestGoldenProtocolFrames(t *testing.T) {
	dbPath := createTypedDB(t)
	allow := testAllowlist(t, dbPath, "readonly")
	in := frames(
		`{"id":1,"op":"hello","protocolVersion":1,"driverVersion":"test","minProtocolVersion":1}`,
		mustJSON(t, map[string]any{"id": 2, "op": "open", "dbPath": dbPath, "readonly": true, "busyTimeoutMs": 100, "queryOnly": true}),
		`{"id":3,"op":"query","sql":"SELECT n,t FROM typed ORDER BY n","maxRows":10,"fetchSize":2}`,
		`{"id":4,"op":"fetch","cursorId":"c1","maxRows":1}`,
		`{"id":5,"op":"closeCursor","cursorId":"c1"}`,
		`{"id":6,"op":"ping"}`,
	)
	var out bytes.Buffer
	if err := Serve(bytes.NewReader(in), &out, allow); err != nil {
		t.Fatalf("Serve() error = %v", err)
	}
	sqliteVersion, _, _ := sqlite3.Version()
	want := []string{
		mustJSON(t, map[string]any{"id": float64(1), "ok": true, "op": "helloAck", "protocolVersion": float64(1), "helperVersion": HelperVersion, "sqliteVersion": sqliteVersion, "os": runtime.GOOS, "arch": runtime.GOARCH, "sqliteCompileOptions": anyStrings(sqliteCompileOptions()), "sqliteBinding": sqliteBindingInfo, "compileTimeCapabilities": anyStrings(compileTimeCapabilities()), "capabilities": []any{"open", "query", "cursorFetch", "readonlyAuthorizer", "exec", "transactions", "controlCancel"}, "limits": map[string]any{"maxFrameBytes": float64(MaxFrameBytes), "maxBatchRows": float64(MaxBatchRows), "maxBatchBytes": float64(MaxBatchBytes)}}),
		mustJSON(t, map[string]any{"id": float64(2), "ok": true, "op": "openAck", "canonicalDbPath": dbPath, "readonly": true, "wal": true, "sqliteVariableLimit": float64(32766), "allowlistVersion": float64(1), "allowlistHash": allow.Hash}),
		mustJSON(t, map[string]any{"id": float64(3), "ok": true, "op": "queryStarted", "cursorId": "c1", "columns": []any{map[string]any{"name": "n", "nullable": true}, map[string]any{"name": "t", "nullable": true}}}),
		mustJSON(t, map[string]any{"id": float64(4), "ok": true, "op": "rowBatch", "cursorId": "c1", "rows": []any{[]any{map[string]any{"type": "integer", "value": float64(7)}, map[string]any{"type": "text", "value": "hello"}}}, "done": false, "rowCount": float64(1), "truncated": false}),
		mustJSON(t, map[string]any{"id": float64(5), "ok": true, "op": "closeCursorAck", "cursorId": "c1", "finalized": true}),
		mustJSON(t, map[string]any{"id": float64(6), "ok": true, "op": "pong"}),
	}
	got := readAllPayloads(t, out.Bytes())
	if len(got) != len(want) {
		t.Fatalf("got %d frames, want %d: %v", len(got), len(want), got)
	}
	for i := range want {
		assertJSONEqual(t, got[i], want[i])
	}
}

func TestVersionReportIncludesCompatibilityDiagnostics(t *testing.T) {
	report := VersionReport()
	if report.HelperVersion != HelperVersion || report.ProtocolVersion != ProtocolVersion {
		t.Fatalf("unexpected version report: %+v", report)
	}
	if report.OS != runtime.GOOS || report.Arch != runtime.GOARCH {
		t.Fatalf("unexpected target in version report: %+v", report)
	}
	if report.SQLiteVersion == "" || report.SQLiteBinding == "" {
		t.Fatalf("missing sqlite diagnostics: %+v", report)
	}
	if len(report.CompileTimeCapabilities) == 0 {
		t.Fatalf("missing compile-time capabilities: %+v", report)
	}
}

func TestHelperVersionFlagPrintsDiagnosticsJSON(t *testing.T) {
	cmd := exec.Command("go", "run", "../../cmd/sshsqlite-helper", "--version")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("helper --version failed: %v\n%s", err, out)
	}
	var report versionReport
	if err := json.Unmarshal(out, &report); err != nil {
		t.Fatalf("invalid --version json: %v: %s", err, out)
	}
	if report.HelperVersion != HelperVersion || report.ProtocolVersion != ProtocolVersion || report.OS == "" || report.Arch == "" {
		t.Fatalf("unexpected --version report: %+v", report)
	}
}

func TestMalformedOversizedAndTruncatedFramesAreFatal(t *testing.T) {
	allow := allowlist{Version: 1, Hash: "sha256:test", Databases: []allowlistDB{{Path: "/tmp/none", Mode: "readonly"}}}
	for _, tc := range []struct {
		name string
		in   []byte
	}{
		{name: "malformed", in: frameBytes(`{"id":1,"op":"hello"`)},
		{name: "oversized", in: oversizedFrame()},
		{name: "truncated", in: []byte{0, 0, 0, 8, '{', '"'}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var out bytes.Buffer
			if err := Serve(bytes.NewReader(tc.in), &out, allow); err == nil {
				t.Fatalf("Serve() error = nil")
			}
			if out.Len() != 0 {
				t.Fatalf("fatal protocol error wrote stdout: %q", out.String())
			}
		})
	}
}

func TestDuplicateTopLevelKeyIsFatal(t *testing.T) {
	var out bytes.Buffer
	err := Serve(bytes.NewReader(frameBytes(`{"id":1,"id":2,"op":"ping"}`)), &out, allowlist{Version: 1})
	if err == nil || !strings.Contains(err.Error(), "duplicate") {
		t.Fatalf("Serve() error = %v, want duplicate-key fatal", err)
	}
	if out.Len() != 0 {
		t.Fatalf("fatal duplicate-key error wrote stdout")
	}
}

func TestQueryTypedValues(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true, "busyTimeoutMs": 100, "queryOnly": true}),
		`{"id":2,"op":"query","sql":"SELECT n,r,t,b,z FROM typed ORDER BY n","maxRows":10}`,
		`{"id":3,"op":"fetch","cursorId":"c1","maxRows":10}`,
	)
	assertJSONEqual(t, got[2], mustJSON(t, map[string]any{"id": float64(3), "ok": true, "op": "rowBatch", "cursorId": "c1", "rows": []any{[]any{map[string]any{"type": "integer", "value": float64(7)}, map[string]any{"type": "real", "value": 3.5}, map[string]any{"type": "text", "value": "hello"}, map[string]any{"type": "blob", "base64": "AAEC"}, map[string]any{"type": "null"}}}, "done": true, "rowCount": float64(1), "truncated": false}))
}

func TestQueryBindsIndexedParameters(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true}),
		mustJSON(t, map[string]any{"id": 2, "op": "query", "sql": "SELECT t FROM typed WHERE n = ? AND t <> ?", "params": []any{map[string]any{"type": "integer", "value": 7}, map[string]any{"type": "text", "value": "x"}}, "maxRows": 10}),
		`{"id":3,"op":"fetch","cursorId":"c1","maxRows":10}`,
	)
	assertJSONEqual(t, got[2], mustJSON(t, map[string]any{"id": float64(3), "ok": true, "op": "rowBatch", "cursorId": "c1", "rows": []any{[]any{map[string]any{"type": "text", "value": "hello"}}}, "done": true, "rowCount": float64(1), "truncated": false}))
}

func TestQueryParameterValidation(t *testing.T) {
	dbPath := createTypedDB(t)
	for _, tc := range []struct {
		name string
		req  map[string]any
	}{
		{name: "missing", req: map[string]any{"id": 2, "op": "query", "sql": "SELECT ?", "params": []any{}}},
		{name: "extra", req: map[string]any{"id": 2, "op": "query", "sql": "SELECT ?", "params": []any{map[string]any{"type": "integer", "value": 1}, map[string]any{"type": "integer", "value": 2}}}},
		{name: "sparse", req: map[string]any{"id": 2, "op": "query", "sql": "SELECT ?10", "params": []any{map[string]any{"type": "integer", "value": 1}}}},
		{name: "mixed", req: map[string]any{"id": 2, "op": "query", "sql": "SELECT :x", "params": []any{map[string]any{"type": "integer", "value": 1}}, "namedParams": map[string]any{":x": map[string]any{"type": "integer", "value": 1}}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
				mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true}),
				mustJSON(t, tc.req),
			)
			assertErrorCode(t, got[1], "badParams")
		})
	}
}

func TestReadonlyWriteRejected(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true, "busyTimeoutMs": 100, "queryOnly": true}),
		`{"id":2,"op":"query","sql":"INSERT INTO typed(n) VALUES (1)"}`,
	)
	assertErrorCode(t, got[1], "readonly")
}

func TestExecWritesAndRejectsRowsAndSchema(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readwrite"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": false, "writeBackupAcknowledged": true, "busyTimeoutMs": 100}),
		mustJSON(t, map[string]any{"id": 2, "op": "exec", "sql": "UPDATE typed SET t = ? WHERE n = ?", "params": []any{map[string]any{"type": "text", "value": "changed"}, map[string]any{"type": "integer", "value": 7}}}),
		`{"id":3,"op":"exec","sql":"SELECT n FROM typed"}`,
		`{"id":4,"op":"exec","sql":"CREATE TABLE blocked(id INTEGER)"}`,
	)
	assertJSONEqual(t, got[1], `{"id":2,"ok":true,"op":"execResult","changes":1,"lastInsertRowid":0}`)
	assertErrorCode(t, got[2], "notUpdate")
	assertErrorCode(t, got[3], "sqlitePrepare")
}

func TestTransactionsCommitAndRollback(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readwrite"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": false, "writeBackupAcknowledged": true, "busyTimeoutMs": 100}),
		`{"id":2,"op":"begin","mode":"deferred"}`,
		`{"id":3,"op":"exec","sql":"INSERT INTO typed(n,t) VALUES (8,'commit')"}`,
		`{"id":4,"op":"commit"}`,
		`{"id":5,"op":"begin","mode":"deferred"}`,
		`{"id":6,"op":"exec","sql":"INSERT INTO typed(n,t) VALUES (9,'rollback')"}`,
		`{"id":7,"op":"rollback"}`,
	)
	assertJSONEqual(t, got[1], `{"id":2,"ok":true,"op":"beginAck"}`)
	assertJSONEqual(t, got[3], `{"id":4,"ok":true,"op":"commitAck"}`)
	assertJSONEqual(t, got[6], `{"id":7,"ok":true,"op":"rollbackAck"}`)
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var count int
	if err := db.QueryRow("SELECT count(*) FROM typed WHERE n = 8").Scan(&count); err != nil || count != 1 {
		t.Fatalf("committed row count = %d, err = %v", count, err)
	}
	if err := db.QueryRow("SELECT count(*) FROM typed WHERE n = 9").Scan(&count); err != nil || count != 0 {
		t.Fatalf("rolled back row count = %d, err = %v", count, err)
	}
}

func TestMultipleStatementsRejected(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true}),
		`{"id":2,"op":"query","sql":"SELECT ';'; SELECT 2"}`,
	)
	assertErrorCode(t, got[1], "singleStatement")
}

func TestPathAllowlistAndSymlinkEscapeRejected(t *testing.T) {
	dir := t.TempDir()
	allowedDB := createDBAt(t, filepath.Join(dir, "allowed", "ok.db"))
	outsideDB := createDBAt(t, filepath.Join(dir, "outside.db"))
	link := filepath.Join(dir, "allowed", "escape.db")
	if err := os.Symlink(outsideDB, link); err != nil {
		t.Fatal(err)
	}
	allow := allowlist{Version: 1, Hash: "sha256:test", DirectoryPrefixes: []allowlistPrefix{{Path: filepath.Dir(allowedDB), Mode: "readonly"}}}
	got := runHelper(t, allow, mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": outsideDB, "readonly": true}))
	assertErrorCode(t, got[0], "pathAuthorization")
	got = runHelper(t, allow, mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": link, "readonly": true}))
	assertErrorCode(t, got[0], "pathAuthorization")
}

func TestBusyTimeoutBehavior(t *testing.T) {
	dbPath := createTypedDB(t)
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec("PRAGMA journal_mode=DELETE"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("BEGIN EXCLUSIVE"); err != nil {
		t.Fatal(err)
	}
	defer db.Exec("ROLLBACK")
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		mustJSON(t, map[string]any{"id": 1, "op": "open", "dbPath": dbPath, "readonly": true, "busyTimeoutMs": 25, "queryOnly": true}),
		`{"id":2,"op":"query","sql":"SELECT n FROM typed"}`,
	)
	if responseErrorCode(t, got[0]) == "sqliteOpen" {
		assertErrorCode(t, got[0], "sqliteOpen")
		return
	}
	assertErrorCode(t, got[1], "sqlitePrepare")
}

func TestPersistentStreamRepeatedRequestsBeforeEOF(t *testing.T) {
	dbPath := createTypedDB(t)
	got := runHelper(t, testAllowlist(t, dbPath, "readonly"),
		`{"id":1,"op":"ping"}`,
		`{"id":2,"op":"ping"}`,
		mustJSON(t, map[string]any{"id": 3, "op": "open", "dbPath": dbPath, "readonly": true}),
		`{"id":4,"op":"ping"}`,
	)
	assertJSONEqual(t, got[0], `{"id":1,"ok":true,"op":"pong"}`)
	assertJSONEqual(t, got[1], `{"id":2,"ok":true,"op":"pong"}`)
	assertJSONEqual(t, got[3], `{"id":4,"ok":true,"op":"pong"}`)
}

func runHelper(t *testing.T, allow allowlist, payloads ...string) []string {
	t.Helper()
	var out bytes.Buffer
	if err := Serve(bytes.NewReader(frames(payloads...)), &out, allow); err != nil {
		t.Fatalf("Serve() error = %v", err)
	}
	return readAllPayloads(t, out.Bytes())
}

func frameBytes(payload string) []byte {
	var b bytes.Buffer
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(len(payload)))
	b.Write(header[:])
	b.WriteString(payload)
	return b.Bytes()
}

func frames(payloads ...string) []byte {
	var b bytes.Buffer
	for _, p := range payloads {
		b.Write(frameBytes(p))
	}
	return b.Bytes()
}

func oversizedFrame() []byte {
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(MaxFrameBytes+1))
	return header[:]
}

func readAllPayloads(t *testing.T, b []byte) []string {
	t.Helper()
	var got []string
	r := bytes.NewReader(b)
	for r.Len() > 0 {
		payload, err := readFrame(r)
		if err != nil {
			t.Fatalf("readFrame() error = %v", err)
		}
		got = append(got, string(payload))
	}
	return got
}

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func anyStrings(values []string) []any {
	out := make([]any, len(values))
	for i, value := range values {
		out[i] = value
	}
	return out
}

func assertJSONEqual(t *testing.T, got, want string) {
	t.Helper()
	var g any
	if err := json.Unmarshal([]byte(got), &g); err != nil {
		t.Fatalf("got invalid json: %v: %s", err, got)
	}
	var w any
	if err := json.Unmarshal([]byte(want), &w); err != nil {
		t.Fatalf("want invalid json: %v: %s", err, want)
	}
	gb, _ := json.Marshal(g)
	wb, _ := json.Marshal(w)
	if string(gb) != string(wb) {
		t.Fatalf("json mismatch\ngot:  %s\nwant: %s", gb, wb)
	}
}

func assertErrorCode(t *testing.T, got, code string) {
	t.Helper()
	actual := responseErrorCode(t, got)
	if actual != code {
		t.Fatalf("got response %s, want error code %q", got, code)
	}
}

func responseErrorCode(t *testing.T, got string) string {
	t.Helper()
	var resp struct {
		OK    bool `json:"ok"`
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal([]byte(got), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.OK {
		t.Fatalf("got response %s, want error", got)
	}
	return resp.Error.Code
}

func createTypedDB(t *testing.T) string {
	t.Helper()
	return createDBAt(t, filepath.Join(t.TempDir(), "typed.db"))
}

func createDBAt(t *testing.T, path string) string {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	db, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = db.Exec(`CREATE TABLE typed(n INTEGER, r REAL, t TEXT, b BLOB, z); INSERT INTO typed VALUES(7, 3.5, 'hello', x'000102', NULL);`)
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	return canonical
}

func testAllowlist(t *testing.T, dbPath, mode string) allowlist {
	t.Helper()
	return allowlist{Version: 1, Hash: "sha256:test", Databases: []allowlistDB{{Path: dbPath, Mode: mode}}}
}
