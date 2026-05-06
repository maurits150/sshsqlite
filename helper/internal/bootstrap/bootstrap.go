package bootstrap

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"database/sql/driver"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	sqlite3 "github.com/mattn/go-sqlite3"
)

const (
	ProtocolVersion = 1
	HelperVersion   = "0.1.0-phase1"
	MaxFrameBytes   = 1 << 20
	MaxBatchRows    = 500
	MaxBatchBytes   = 1 << 20
	MaxSQLBytes     = 1 << 20

	sqliteVariableLimitID = 9
	sqliteBindingInfo     = "cgo dynamic sqlite via github.com/mattn/go-sqlite3 unless build tags/toolchain provide static linking"
)

type versionReport struct {
	HelperVersion           string   `json:"helperVersion"`
	ProtocolVersion         int      `json:"protocolVersion"`
	ProtocolMinVersion      int      `json:"protocolMinVersion"`
	ProtocolMaxVersion      int      `json:"protocolMaxVersion"`
	OS                      string   `json:"os"`
	Arch                    string   `json:"arch"`
	SQLiteVersion           string   `json:"sqliteVersion"`
	SQLiteCompileOptions    []string `json:"sqliteCompileOptions,omitempty"`
	SQLiteBinding           string   `json:"sqliteBinding"`
	CompileTimeCapabilities []string `json:"compileTimeCapabilities"`
}

type allowlist struct {
	Version           int               `json:"version"`
	Databases         []allowlistDB     `json:"databases"`
	DirectoryPrefixes []allowlistPrefix `json:"directoryPrefixes"`
	AllowSymlinks     bool              `json:"allowSymlinks"`
	Hash              string            `json:"-"`
}

type allowlistDB struct {
	Path string `json:"path"`
	Mode string `json:"mode"`
}

type allowlistPrefix struct {
	Path string `json:"path"`
	Mode string `json:"mode"`
}

type server struct {
	in             io.Reader
	out            *bufio.Writer
	allow          allowlist
	opened         bool
	readonly       bool
	activeTx       bool
	conn           driver.Conn
	cursor         *cursor
	lastClosedID   string
	nextCursor     int64
	sqliteVarLimit int
	writeMu        sync.Mutex
	activeMu       sync.Mutex
	activeID       int64
	activeCancel   context.CancelFunc
}

type cursor struct {
	id      string
	rows    driver.Rows
	cols    []column
	maxRows int
	count   int
	done    bool
	cancel  context.CancelFunc
}

type column struct {
	Name         string `json:"name"`
	DeclaredType string `json:"declaredType,omitempty"`
	SQLiteType   string `json:"sqliteType,omitempty"`
	Nullable     bool   `json:"nullable"`
}

type envelope struct {
	ID int64  `json:"id"`
	Op string `json:"op"`
}

type helloRequest struct {
	ID                 int64  `json:"id"`
	Op                 string `json:"op"`
	ProtocolVersion    int    `json:"protocolVersion"`
	DriverVersion      string `json:"driverVersion"`
	MinProtocolVersion int    `json:"minProtocolVersion"`
}

type openRequest struct {
	ID                      int64  `json:"id"`
	Op                      string `json:"op"`
	DBPath                  string `json:"dbPath"`
	Readonly                bool   `json:"readonly"`
	WriteBackupAcknowledged bool   `json:"writeBackupAcknowledged"`
	AdminSQL                bool   `json:"adminSql"`
	AllowSchemaChanges      bool   `json:"allowSchemaChanges"`
	BusyTimeoutMs           int    `json:"busyTimeoutMs"`
	QueryOnly               bool   `json:"queryOnly"`
}

type queryRequest struct {
	ID          int64            `json:"id"`
	Op          string           `json:"op"`
	SQL         string           `json:"sql"`
	Params      []value          `json:"params,omitempty"`
	NamedParams map[string]value `json:"namedParams,omitempty"`
	MaxRows     int              `json:"maxRows"`
	FetchSize   int              `json:"fetchSize"`
	TimeoutMs   int              `json:"timeoutMs"`
}

type fetchRequest struct {
	ID        int64  `json:"id"`
	Op        string `json:"op"`
	CursorID  string `json:"cursorId"`
	MaxRows   int    `json:"maxRows"`
	TimeoutMs int    `json:"timeoutMs"`
}

type closeCursorRequest struct {
	ID       int64  `json:"id"`
	Op       string `json:"op"`
	CursorID string `json:"cursorId"`
}

type execRequest struct {
	ID          int64            `json:"id"`
	Op          string           `json:"op"`
	SQL         string           `json:"sql"`
	Params      []value          `json:"params,omitempty"`
	NamedParams map[string]value `json:"namedParams,omitempty"`
	TimeoutMs   int              `json:"timeoutMs"`
}

type beginRequest struct {
	ID   int64  `json:"id"`
	Op   string `json:"op"`
	Mode string `json:"mode"`
}

type cancelRequest struct {
	ID       int64  `json:"id"`
	Op       string `json:"op"`
	TargetID int64  `json:"targetId"`
}

type successBase struct {
	ID int64  `json:"id"`
	OK bool   `json:"ok"`
	Op string `json:"op"`
}

type errorResponse struct {
	ID    int64         `json:"id,omitempty"`
	OK    bool          `json:"ok"`
	Op    string        `json:"op"`
	Error protocolError `json:"error"`
}

type protocolError struct {
	Code             string `json:"code"`
	Message          string `json:"message"`
	SQLiteCode       int    `json:"sqliteCode,omitempty"`
	SQLiteExtended   int    `json:"sqliteExtendedCode,omitempty"`
	SQLState         string `json:"sqlState"`
	VendorCode       int    `json:"vendorCode,omitempty"`
	ConnectionBroken bool   `json:"connectionBroken"`
	Retryable        bool   `json:"retryable"`
}

// Main runs the helper executable entry point.
func Main() {
	stdio := flag.Bool("stdio", false, "run framed stdio protocol")
	version := flag.Bool("version", false, "print helper version diagnostics as JSON")
	diagnostics := flag.Bool("diagnostics", false, "print helper diagnostics as JSON")
	allowlistPath := flag.String("allowlist", "", "trusted allowlist config path")
	flag.Parse()
	if *version || *diagnostics {
		if err := json.NewEncoder(os.Stdout).Encode(VersionReport()); err != nil {
			fmt.Fprintf(os.Stderr, "diagnostics error: %v\n", err)
			os.Exit(1)
		}
		return
	}
	if !*stdio {
		fmt.Fprintln(os.Stderr, "sshsqlite-helper requires --stdio, --version, or --diagnostics")
		os.Exit(2)
	}
	path := *allowlistPath
	if path == "" {
		path = os.Getenv("SSHSQLITE_ALLOWLIST")
	}
	if path == "" {
		path = "/etc/sshsqlite/allowlist.json"
	}
	allow, err := loadAllowlist(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "allowlist error: %v\n", err)
		os.Exit(1)
	}
	if err := Serve(os.Stdin, os.Stdout, allow); err != nil {
		fmt.Fprintf(os.Stderr, "protocol error: %v\n", err)
		os.Exit(1)
	}
}

func VersionReport() versionReport {
	sqliteVersion, _, _ := sqlite3.Version()
	return versionReport{
		HelperVersion:           HelperVersion,
		ProtocolVersion:         ProtocolVersion,
		ProtocolMinVersion:      ProtocolVersion,
		ProtocolMaxVersion:      ProtocolVersion,
		OS:                      runtime.GOOS,
		Arch:                    runtime.GOARCH,
		SQLiteVersion:           sqliteVersion,
		SQLiteCompileOptions:    sqliteCompileOptions(),
		SQLiteBinding:           sqliteBindingInfo,
		CompileTimeCapabilities: compileTimeCapabilities(),
	}
}

func Serve(in io.Reader, out io.Writer, allow allowlist) error {
	s := &server{in: in, out: bufio.NewWriter(out), allow: allow, sqliteVarLimit: 999}
	defer s.close()
	work := make(chan []byte)
	done := make(chan error, 1)
	go func() {
		for payload := range work {
			if err := s.handle(payload); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	for {
		select {
		case err := <-done:
			return err
		default:
		}
		payload, err := readFrame(s.in)
		if errors.Is(err, io.EOF) {
			close(work)
			return <-done
		}
		if err != nil {
			close(work)
			return err
		}
		if err := rejectDuplicateTopLevelKeys(payload); err != nil {
			close(work)
			return err
		}
		if s.isCancel(payload) {
			if err := s.handleCancel(payload); err != nil {
				close(work)
				return err
			}
			continue
		}
		select {
		case work <- payload:
		case err := <-done:
			return err
		}
	}
}

func (s *server) isCancel(payload []byte) bool {
	var env envelope
	return json.Unmarshal(payload, &env) == nil && env.Op == "cancel"
}

func (s *server) handleCancel(payload []byte) error {
	var req cancelRequest
	if err := decodeStrict(payload, &req); err != nil {
		return s.writeResponse(errorResponse{OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
	}
	if req.ID <= 0 || req.TargetID <= 0 {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badRequest", "id and targetId are required", false, false, nil)})
	}
	s.activeMu.Lock()
	accepted := s.activeID == req.TargetID && s.activeCancel != nil
	if accepted {
		s.activeCancel()
	}
	s.activeMu.Unlock()
	if !accepted {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("cancel", "target request is not active", false, false, nil)})
	}
	return s.writeResponse(struct {
		ID       int64  `json:"id"`
		OK       bool   `json:"ok"`
		Op       string `json:"op"`
		TargetID int64  `json:"targetId"`
	}{ID: req.ID, OK: true, Op: "cancelAck", TargetID: req.TargetID})
}

func (s *server) beginActive(id int64, cancel context.CancelFunc) {
	s.activeMu.Lock()
	s.activeID = id
	s.activeCancel = cancel
	s.activeMu.Unlock()
}

func (s *server) endActive(id int64) {
	s.activeMu.Lock()
	if s.activeID == id {
		s.activeID = 0
		s.activeCancel = nil
	}
	s.activeMu.Unlock()
}

func (s *server) requestContext(id int64, timeoutMs int) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	if timeoutMs > 0 {
		timer := time.AfterFunc(time.Duration(timeoutMs)*time.Millisecond, cancel)
		baseCancel := cancel
		cancel = func() {
			timer.Stop()
			baseCancel()
		}
	}
	s.beginActive(id, cancel)
	return ctx, func() {
		s.endActive(id)
		cancel()
	}
}

func (s *server) writeResponse(v any) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return writeResponse(s.out, v)
}

func (s *server) handle(payload []byte) error {
	var env envelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return s.writeResponse(errorResponse{OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
	}
	if env.ID <= 0 || env.Op == "" {
		return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", "id and op are required", false, false, nil)})
	}
	switch env.Op {
	case "hello":
		var req helloRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.hello(req)
	case "open":
		var req openRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.open(req)
	case "ping":
		return s.writeResponse(successBase{ID: env.ID, OK: true, Op: "pong"})
	case "query":
		var req queryRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.query(req)
	case "fetch":
		var req fetchRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.fetch(req)
	case "closeCursor":
		var req closeCursorRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.closeCursor(req)
	case "exec":
		var req execRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.exec(req)
	case "begin":
		var req beginRequest
		if err := decodeStrict(payload, &req); err != nil {
			return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("badRequest", err.Error(), false, false, err)})
		}
		return s.begin(req)
	case "commit":
		return s.finishTx(env.ID, "commit")
	case "rollback":
		return s.finishTx(env.ID, "rollback")
	default:
		return s.writeResponse(errorResponse{ID: env.ID, OK: false, Op: "error", Error: makeError("unsupportedOperation", "unsupported operation", false, false, nil)})
	}
}

func (s *server) hello(req helloRequest) error {
	if req.ProtocolVersion != ProtocolVersion || req.MinProtocolVersion > ProtocolVersion {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("protocolVersion", "unsupported protocol version", false, false, nil)})
	}
	report := VersionReport()
	return s.writeResponse(struct {
		ID                      int64    `json:"id"`
		OK                      bool     `json:"ok"`
		Op                      string   `json:"op"`
		ProtocolVersion         int      `json:"protocolVersion"`
		HelperVersion           string   `json:"helperVersion"`
		SQLiteVersion           string   `json:"sqliteVersion"`
		OS                      string   `json:"os"`
		Arch                    string   `json:"arch"`
		SQLiteCompileOptions    []string `json:"sqliteCompileOptions,omitempty"`
		SQLiteBinding           string   `json:"sqliteBinding"`
		CompileTimeCapabilities []string `json:"compileTimeCapabilities"`
		Capabilities            []string `json:"capabilities"`
		Limits                  struct {
			MaxFrameBytes int `json:"maxFrameBytes"`
			MaxBatchRows  int `json:"maxBatchRows"`
			MaxBatchBytes int `json:"maxBatchBytes"`
		} `json:"limits"`
	}{ID: req.ID, OK: true, Op: "helloAck", ProtocolVersion: ProtocolVersion, HelperVersion: HelperVersion, SQLiteVersion: report.SQLiteVersion, OS: report.OS, Arch: report.Arch, SQLiteCompileOptions: report.SQLiteCompileOptions, SQLiteBinding: report.SQLiteBinding, CompileTimeCapabilities: report.CompileTimeCapabilities, Capabilities: []string{"open", "query", "cursorFetch", "readonlyAuthorizer", "exec", "transactions", "controlCancel"}, Limits: struct {
		MaxFrameBytes int `json:"maxFrameBytes"`
		MaxBatchRows  int `json:"maxBatchRows"`
		MaxBatchBytes int `json:"maxBatchBytes"`
	}{MaxFrameBytes: MaxFrameBytes, MaxBatchRows: MaxBatchRows, MaxBatchBytes: MaxBatchBytes}})
}

func compileTimeCapabilities() []string {
	return []string{"cgo", "sqliteVersion", "sqliteCompileOptions", "readonlyAuthorizer", "sqliteInterrupt", "extendedResultCodes", "prepareTailValidation", "sqliteLimits", "extensionLoadingDisabled"}
}

func sqliteCompileOptions() []string {
	db, err := sql.Open("sqlite3", ":memory:")
	if err != nil {
		return nil
	}
	defer db.Close()
	rows, err := db.Query("PRAGMA compile_options")
	if err != nil {
		return nil
	}
	defer rows.Close()
	var options []string
	for rows.Next() {
		var option string
		if err := rows.Scan(&option); err != nil {
			return options
		}
		options = append(options, option)
	}
	return options
}

func (s *server) open(req openRequest) error {
	if s.opened {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("openState", "database already opened", false, false, nil)})
	}
	if req.AdminSQL || req.AllowSchemaChanges {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("policy", "admin SQL and schema changes are not enabled", false, false, nil)})
	}
	canonical, mode, err := s.authorizePath(req.DBPath)
	if err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("pathAuthorization", err.Error(), false, false, err)})
	}
	if !req.Readonly && (mode != "readwrite" || !req.WriteBackupAcknowledged) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("readonlyRequired", "read-write open is not authorized", false, false, nil)})
	}
	dsnMode := "ro"
	if !req.Readonly {
		dsnMode = "rw"
	}
	dsn := fmt.Sprintf("file:%s?mode=%s&_busy_timeout=%d", urlPath(canonical), dsnMode, req.BusyTimeoutMs)
	if req.Readonly || req.QueryOnly {
		dsn += "&_query_only=true"
	}
	drv := &sqlite3.SQLiteDriver{ConnectHook: func(conn *sqlite3.SQLiteConn) error {
		if req.Readonly {
			conn.RegisterAuthorizer(readonlyAuthorizer)
		} else {
			conn.RegisterAuthorizer(dataEditAuthorizer)
		}
		old := conn.SetLimit(sqliteVariableLimitID, -1)
		s.sqliteVarLimit = old
		return nil
	}}
	conn, err := drv.Open(dsn)
	if err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqliteOpen", "sqlite open failed", false, isBusy(err), err)})
	}
	s.conn = conn
	s.opened = true
	s.readonly = req.Readonly
	return s.writeResponse(struct {
		ID                  int64  `json:"id"`
		OK                  bool   `json:"ok"`
		Op                  string `json:"op"`
		CanonicalDBPath     string `json:"canonicalDbPath"`
		Readonly            bool   `json:"readonly"`
		WAL                 bool   `json:"wal"`
		SQLiteVariableLimit int    `json:"sqliteVariableLimit"`
		AllowlistVersion    int    `json:"allowlistVersion"`
		AllowlistHash       string `json:"allowlistHash"`
	}{ID: req.ID, OK: true, Op: "openAck", CanonicalDBPath: canonical, Readonly: req.Readonly, WAL: true, SQLiteVariableLimit: s.sqliteVarLimit, AllowlistVersion: s.allow.Version, AllowlistHash: s.allow.Hash})
}

func (s *server) exec(req execRequest) error {
	if !s.opened {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("openRequired", "open is required before exec", false, false, nil)})
	}
	if s.readonly {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("readonly", "readonly connections cannot execute mutating SQL", false, false, nil)})
	}
	if len(req.SQL) == 0 || len(req.SQL) > MaxSQLBytes {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badSql", "sql is empty or too large", false, false, nil)})
	}
	if hasTrailingStatement(req.SQL) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("singleStatement", "exec must contain exactly one statement", false, false, nil)})
	}
	if len(req.Params) > 0 && len(req.NamedParams) > 0 {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "params and namedParams cannot be mixed", false, false, nil)})
	}
	if len(req.NamedParams) > 0 {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "namedParams are not supported yet", false, false, nil)})
	}
	if hasSparseNumericParameter(req.SQL) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "sparse ?NNN parameters are not supported", false, false, nil)})
	}
	if rowReturningSQL(req.SQL) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("notUpdate", "executeUpdate requires a statement that does not return rows", false, false, nil)})
	}
	stmt, err := s.conn.Prepare(req.SQL)
	if err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqlitePrepare", "sqlite prepare failed", false, isBusy(err), err)})
	}
	defer stmt.Close()
	args, err := bindArgs(stmt, req.Params)
	if err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", err.Error(), false, false, err)})
	}
	execStmt, ok := stmt.(driver.StmtExecContext)
	if !ok {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("unsupported", "driver does not support exec context", false, false, nil)})
	}
	ctx, finish := s.requestContext(req.ID, req.TimeoutMs)
	defer finish()
	res, err := execStmt.ExecContext(ctx, args)
	if err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqliteExec", "sqlite exec failed", ctx.Err() != nil || isCancelErr(err), isBusy(err), err)})
	}
	changes, _ := res.RowsAffected()
	lastID, _ := res.LastInsertId()
	return s.writeResponse(struct {
		ID              int64  `json:"id"`
		OK              bool   `json:"ok"`
		Op              string `json:"op"`
		Changes         int64  `json:"changes"`
		LastInsertRowid int64  `json:"lastInsertRowid"`
	}{ID: req.ID, OK: true, Op: "execResult", Changes: changes, LastInsertRowid: lastID})
}

func (s *server) begin(req beginRequest) error {
	if !s.opened {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("openRequired", "open is required before begin", false, false, nil)})
	}
	if s.readonly {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("readonly", "readonly connections cannot begin write transactions", false, false, nil)})
	}
	if s.activeTx {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("transactionState", "transaction already active", false, false, nil)})
	}
	mode := strings.ToLower(req.Mode)
	sql := "BEGIN"
	if mode == "immediate" {
		sql = "BEGIN IMMEDIATE"
	} else if mode == "exclusive" {
		sql = "BEGIN EXCLUSIVE"
	} else if mode != "" && mode != "deferred" {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badRequest", "invalid transaction mode", false, false, nil)})
	}
	if err := s.execSimple(sql); err != nil {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqliteTransaction", "sqlite begin failed", false, isBusy(err), err)})
	}
	s.activeTx = true
	return s.writeResponse(successBase{ID: req.ID, OK: true, Op: "beginAck"})
}

func (s *server) finishTx(id int64, op string) error {
	if !s.opened {
		return s.writeResponse(errorResponse{ID: id, OK: false, Op: "error", Error: makeError("openRequired", "open is required before transaction operation", false, false, nil)})
	}
	if s.readonly {
		return s.writeResponse(errorResponse{ID: id, OK: false, Op: "error", Error: makeError("readonly", "readonly connections cannot use transactions", false, false, nil)})
	}
	if !s.activeTx {
		return s.writeResponse(errorResponse{ID: id, OK: false, Op: "error", Error: makeError("transactionState", "no active transaction", false, false, nil)})
	}
	if s.cursor != nil {
		s.cursor.rows.Close()
		s.cursor = nil
	}
	if err := s.execSimple(strings.ToUpper(op)); err != nil {
		return s.writeResponse(errorResponse{ID: id, OK: false, Op: "error", Error: makeError("sqliteTransaction", "sqlite "+op+" failed", false, isBusy(err), err)})
	}
	s.activeTx = false
	return s.writeResponse(successBase{ID: id, OK: true, Op: op + "Ack"})
}

func (s *server) execSimple(sqlText string) error {
	stmt, err := s.conn.Prepare(sqlText)
	if err != nil {
		return err
	}
	defer stmt.Close()
	execStmt, ok := stmt.(driver.StmtExecContext)
	if !ok {
		return errors.New("driver does not support exec context")
	}
	_, err = execStmt.ExecContext(context.Background(), nil)
	return err
}

func (s *server) query(req queryRequest) error {
	if !s.opened {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("openRequired", "open is required before query", false, false, nil)})
	}
	if len(req.SQL) == 0 || len(req.SQL) > MaxSQLBytes {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badSql", "sql is empty or too large", false, false, nil)})
	}
	if hasTrailingStatement(req.SQL) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("singleStatement", "query must contain exactly one statement", false, false, nil)})
	}
	if len(req.Params) > 0 && len(req.NamedParams) > 0 {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "params and namedParams cannot be mixed", false, false, nil)})
	}
	if len(req.NamedParams) > 0 {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "namedParams are not supported yet", false, false, nil)})
	}
	if hasSparseNumericParameter(req.SQL) {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", "sparse ?NNN parameters are not supported", false, false, nil)})
	}
	if s.cursor != nil {
		s.cursor.rows.Close()
		s.cursor = nil
	}
	stmt, err := s.conn.Prepare(req.SQL)
	if err != nil {
		if isAuth(err) {
			return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("readonly", "query requires a read-only statement", false, false, err)})
		}
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqlitePrepare", "sqlite prepare failed", false, isBusy(err), err)})
	}
	if ro, ok := stmt.(interface{ Readonly() bool }); ok && !ro.Readonly() {
		stmt.Close()
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("readonly", "query requires a read-only statement", false, false, nil)})
	}
	qstmt, ok := stmt.(driver.StmtQueryContext)
	if !ok {
		stmt.Close()
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("unsupported", "driver does not support query context", false, false, nil)})
	}
	args, err := bindArgs(stmt, req.Params)
	if err != nil {
		stmt.Close()
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("badParams", err.Error(), false, false, err)})
	}
	ctx, cancel := context.WithCancel(context.Background())
	if req.TimeoutMs > 0 {
		timer := time.AfterFunc(time.Duration(req.TimeoutMs)*time.Millisecond, cancel)
		baseCancel := cancel
		cancel = func() {
			timer.Stop()
			baseCancel()
		}
	}
	s.beginActive(req.ID, cancel)
	rows, err := qstmt.QueryContext(ctx, args)
	s.endActive(req.ID)
	if err != nil {
		cancel()
		stmt.Close()
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqliteQuery", "sqlite query failed", false, isBusy(err), err)})
	}
	cols := rows.Columns()
	if len(cols) == 0 {
		rows.Close()
		cancel()
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("notQuery", "statement returned no columns", false, false, nil)})
	}
	cid := fmt.Sprintf("c%d", atomic.AddInt64(&s.nextCursor, 1))
	limit := req.MaxRows
	if limit <= 0 {
		limit = 0
	}
	c := &cursor{id: cid, rows: rows, maxRows: limit, cancel: cancel}
	for _, name := range cols {
		c.cols = append(c.cols, column{Name: name, Nullable: true})
	}
	s.cursor = c
	return s.writeResponse(struct {
		ID       int64    `json:"id"`
		OK       bool     `json:"ok"`
		Op       string   `json:"op"`
		CursorID string   `json:"cursorId"`
		Columns  []column `json:"columns"`
	}{ID: req.ID, OK: true, Op: "queryStarted", CursorID: cid, Columns: c.cols})
}

func (s *server) fetch(req fetchRequest) error {
	c := s.cursor
	if c == nil || c.id != req.CursorID {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("cursor", "unknown cursor", false, false, nil)})
	}
	s.beginActive(req.ID, c.cancel)
	defer s.endActive(req.ID)
	maxRows := req.MaxRows
	if maxRows <= 0 || maxRows > MaxBatchRows {
		maxRows = MaxBatchRows
	}
	rows := make([][]value, 0, maxRows)
	done := false
	truncated := false
	for len(rows) < maxRows {
		if c.maxRows > 0 && c.count >= c.maxRows {
			done = true
			truncated = true
			break
		}
		dest := make([]driver.Value, len(c.cols))
		err := c.rows.Next(dest)
		if errors.Is(err, io.EOF) {
			done = true
			break
		}
		if err != nil {
			c.rows.Close()
			if c.cancel != nil {
				c.cancel()
			}
			s.lastClosedID = c.id
			s.cursor = nil
			return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("sqliteStep", "sqlite step failed", false, isBusy(err), err)})
		}
		row := make([]value, len(dest))
		for i, v := range dest {
			row[i] = encodeValue(v)
		}
		rows = append(rows, row)
		c.count++
	}
	if done {
		c.rows.Close()
		if c.cancel != nil {
			c.cancel()
		}
		s.lastClosedID = c.id
		s.cursor = nil
	}
	return s.writeResponse(struct {
		ID        int64     `json:"id"`
		OK        bool      `json:"ok"`
		Op        string    `json:"op"`
		CursorID  string    `json:"cursorId"`
		Rows      [][]value `json:"rows"`
		Done      bool      `json:"done"`
		RowCount  int       `json:"rowCount"`
		Truncated bool      `json:"truncated"`
	}{ID: req.ID, OK: true, Op: "rowBatch", CursorID: req.CursorID, Rows: rows, Done: done, RowCount: c.count, Truncated: truncated})
}

func (s *server) closeCursor(req closeCursorRequest) error {
	finalized := false
	if s.cursor != nil && s.cursor.id == req.CursorID {
		s.cursor.rows.Close()
		if s.cursor.cancel != nil {
			s.cursor.cancel()
		}
		s.lastClosedID = s.cursor.id
		s.cursor = nil
		finalized = true
	} else if s.lastClosedID == req.CursorID {
		finalized = true
	} else {
		return s.writeResponse(errorResponse{ID: req.ID, OK: false, Op: "error", Error: makeError("cursor", "unknown cursor", false, false, nil)})
	}
	return s.writeResponse(struct {
		ID        int64  `json:"id"`
		OK        bool   `json:"ok"`
		Op        string `json:"op"`
		CursorID  string `json:"cursorId"`
		Finalized bool   `json:"finalized"`
	}{ID: req.ID, OK: true, Op: "closeCursorAck", CursorID: req.CursorID, Finalized: finalized})
}

type value struct {
	Type   string `json:"type"`
	Value  any    `json:"value,omitempty"`
	Base64 string `json:"base64,omitempty"`
}

func encodeValue(v driver.Value) value {
	switch x := v.(type) {
	case nil:
		return value{Type: "null"}
	case int64:
		return value{Type: "integer", Value: x}
	case float64:
		return value{Type: "real", Value: x}
	case bool:
		if x {
			return value{Type: "integer", Value: int64(1)}
		}
		return value{Type: "integer", Value: int64(0)}
	case []byte:
		return value{Type: "blob", Base64: base64.StdEncoding.EncodeToString(x)}
	case string:
		return value{Type: "text", Value: x}
	default:
		return value{Type: "text", Value: fmt.Sprint(x)}
	}
}

func bindArgs(stmt driver.Stmt, params []value) ([]driver.NamedValue, error) {
	want := stmt.NumInput()
	if want >= 0 && len(params) != want {
		return nil, fmt.Errorf("parameter count mismatch: got %d, want %d", len(params), want)
	}
	args := make([]driver.NamedValue, len(params))
	for i, param := range params {
		v, err := decodeValue(param)
		if err != nil {
			return nil, fmt.Errorf("parameter %d: %w", i+1, err)
		}
		args[i] = driver.NamedValue{Ordinal: i + 1, Value: v}
	}
	return args, nil
}

func decodeValue(v value) (driver.Value, error) {
	switch v.Type {
	case "null":
		return nil, nil
	case "integer":
		return int64FromJSON(v.Value)
	case "real":
		return float64FromJSON(v.Value)
	case "text":
		s, ok := v.Value.(string)
		if !ok {
			return nil, errors.New("text value must be a string")
		}
		return s, nil
	case "blob":
		b, err := base64.StdEncoding.DecodeString(v.Base64)
		if err != nil {
			return nil, err
		}
		return b, nil
	default:
		return nil, fmt.Errorf("unsupported parameter type %q", v.Type)
	}
}

func int64FromJSON(v any) (int64, error) {
	switch x := v.(type) {
	case float64:
		i := int64(x)
		if float64(i) != x {
			return 0, errors.New("integer value is not an int64")
		}
		return i, nil
	case int64:
		return x, nil
	case int:
		return int64(x), nil
	default:
		return 0, errors.New("integer value must be numeric")
	}
}

func float64FromJSON(v any) (float64, error) {
	x, ok := v.(float64)
	if !ok {
		return 0, errors.New("real value must be numeric")
	}
	return x, nil
}

func (s *server) close() {
	if s.cursor != nil {
		s.cursor.rows.Close()
		if s.cursor.cancel != nil {
			s.cursor.cancel()
		}
	}
	if s.activeTx {
		_ = s.execSimple("ROLLBACK")
	}
	if s.conn != nil {
		s.conn.Close()
	}
}

func readFrame(r io.Reader) ([]byte, error) {
	var header [4]byte
	_, err := io.ReadFull(r, header[:])
	if errors.Is(err, io.EOF) {
		return nil, io.EOF
	}
	if err != nil {
		return nil, fmt.Errorf("truncated frame header: %w", err)
	}
	length := binary.BigEndian.Uint32(header[:])
	if length == 0 || length > MaxFrameBytes {
		return nil, fmt.Errorf("invalid frame length %d", length)
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, fmt.Errorf("truncated frame payload: %w", err)
	}
	if !json.Valid(payload) {
		return nil, errors.New("malformed json")
	}
	return payload, nil
}

func writeResponse(w *bufio.Writer, v any) error {
	payload, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if len(payload) > MaxFrameBytes {
		return errors.New("response exceeds max frame size")
	}
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(len(payload)))
	if _, err := w.Write(header[:]); err != nil {
		return err
	}
	if _, err := w.Write(payload); err != nil {
		return err
	}
	return w.Flush()
}

func decodeStrict(payload []byte, target any) error {
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.DisallowUnknownFields()
	if err := dec.Decode(target); err != nil {
		return err
	}
	if dec.More() {
		return errors.New("extra json data")
	}
	return nil
}

func rejectDuplicateTopLevelKeys(payload []byte) error {
	dec := json.NewDecoder(bytes.NewReader(payload))
	tok, err := dec.Token()
	if err != nil {
		return err
	}
	if d, ok := tok.(json.Delim); !ok || d != '{' {
		return errors.New("request must be a json object")
	}
	seen := map[string]bool{}
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return err
		}
		key, ok := keyTok.(string)
		if !ok {
			return errors.New("object key must be string")
		}
		if seen[key] {
			return fmt.Errorf("duplicate top-level key %q", key)
		}
		seen[key] = true
		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return err
		}
	}
	_, err = dec.Token()
	return err
}

func makeError(code, message string, broken, retryable bool, err error) protocolError {
	pe := protocolError{Code: code, Message: message, SQLState: "HY000", ConnectionBroken: broken, Retryable: retryable}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		pe.Code = "cancelled"
		pe.Message = "operation cancelled"
		pe.SQLState = "HYT00"
		return pe
	}
	var sqliteErr sqlite3.Error
	if errors.As(err, &sqliteErr) {
		pe.SQLiteCode = int(sqliteErr.Code)
		pe.SQLiteExtended = int(sqliteErr.ExtendedCode)
		pe.VendorCode = int(sqliteErr.ExtendedCode)
		if sqliteErr.Code == sqlite3.ErrInterrupt {
			pe.Code = "cancelled"
			pe.Message = "operation cancelled"
			pe.SQLState = "HYT00"
		}
	}
	return pe
}

func isBusy(err error) bool {
	var sqliteErr sqlite3.Error
	return errors.As(err, &sqliteErr) && (sqliteErr.Code == sqlite3.ErrBusy || sqliteErr.Code == sqlite3.ErrLocked)
}

func isCancelErr(err error) bool {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var sqliteErr sqlite3.Error
	return errors.As(err, &sqliteErr) && sqliteErr.Code == sqlite3.ErrInterrupt
}

func isAuth(err error) bool {
	var sqliteErr sqlite3.Error
	return errors.As(err, &sqliteErr) && int(sqliteErr.Code) == 23
}

func readonlyAuthorizer(op int, arg1, arg2, _ string) int {
	switch op {
	case sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION:
		if strings.EqualFold(arg1, "load_extension") || strings.EqualFold(arg2, "load_extension") {
			return sqlite3.SQLITE_DENY
		}
		return sqlite3.SQLITE_OK
	case sqlite3.SQLITE_PRAGMA:
		return sqlite3.SQLITE_OK
	default:
		return sqlite3.SQLITE_DENY
	}
}

func dataEditAuthorizer(op int, arg1, arg2, _ string) int {
	switch op {
	case sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION, sqlite3.SQLITE_TRANSACTION:
		if strings.EqualFold(arg1, "load_extension") || strings.EqualFold(arg2, "load_extension") {
			return sqlite3.SQLITE_DENY
		}
		return sqlite3.SQLITE_OK
	case sqlite3.SQLITE_INSERT, sqlite3.SQLITE_UPDATE, sqlite3.SQLITE_DELETE:
		if strings.HasPrefix(strings.ToLower(arg1), "sqlite_") || strings.HasPrefix(strings.ToLower(arg2), "sqlite_") {
			return sqlite3.SQLITE_DENY
		}
		return sqlite3.SQLITE_OK
	case sqlite3.SQLITE_PRAGMA:
		name := strings.ToLower(arg1)
		switch name {
		case "database_list", "table_xinfo", "index_list", "index_info", "foreign_key_list", "busy_timeout", "compile_options", "schema_version", "user_version":
			return sqlite3.SQLITE_OK
		default:
			return sqlite3.SQLITE_DENY
		}
	case sqlite3.SQLITE_CREATE_INDEX, sqlite3.SQLITE_CREATE_TABLE, sqlite3.SQLITE_CREATE_TEMP_INDEX, sqlite3.SQLITE_CREATE_TEMP_TABLE,
		sqlite3.SQLITE_CREATE_TEMP_TRIGGER, sqlite3.SQLITE_CREATE_TEMP_VIEW, sqlite3.SQLITE_CREATE_TRIGGER, sqlite3.SQLITE_CREATE_VIEW,
		sqlite3.SQLITE_CREATE_VTABLE, sqlite3.SQLITE_DROP_INDEX, sqlite3.SQLITE_DROP_TABLE, sqlite3.SQLITE_DROP_TEMP_INDEX,
		sqlite3.SQLITE_DROP_TEMP_TABLE, sqlite3.SQLITE_DROP_TEMP_TRIGGER, sqlite3.SQLITE_DROP_TEMP_VIEW, sqlite3.SQLITE_DROP_TRIGGER,
		sqlite3.SQLITE_DROP_VIEW, sqlite3.SQLITE_DROP_VTABLE, sqlite3.SQLITE_ALTER_TABLE, sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH:
		return sqlite3.SQLITE_DENY
	default:
		return sqlite3.SQLITE_DENY
	}
}

func rowReturningSQL(sql string) bool {
	tokens := sqlTokens(sql)
	if len(tokens) == 0 {
		return false
	}
	first := strings.ToLower(tokens[0])
	if first == "select" || first == "pragma" || first == "values" || first == "with" {
		return true
	}
	for _, token := range tokens {
		if strings.EqualFold(token, "returning") {
			return true
		}
	}
	return false
}

func sqlTokens(sql string) []string {
	var tokens []string
	lineComment := false
	blockComment := false
	quote := rune(0)
	var b strings.Builder
	flush := func() {
		if b.Len() > 0 {
			tokens = append(tokens, b.String())
			b.Reset()
		}
	}
	for i, r := range sql {
		if lineComment {
			if r == '\n' || r == '\r' {
				lineComment = false
			}
			continue
		}
		if blockComment {
			if r == '/' && i > 0 && sql[i-1] == '*' {
				blockComment = false
			}
			continue
		}
		if quote != 0 {
			if r == quote {
				if quote == '\'' && i+1 < len(sql) && sql[i+1] == '\'' {
					continue
				}
				quote = 0
			}
			continue
		}
		if r == '-' && i+1 < len(sql) && sql[i+1] == '-' {
			flush()
			lineComment = true
			continue
		}
		if r == '/' && i+1 < len(sql) && sql[i+1] == '*' {
			flush()
			blockComment = true
			continue
		}
		if r == '\'' || r == '"' || r == '`' {
			flush()
			quote = r
			continue
		}
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '_' {
			b.WriteRune(r)
			continue
		}
		flush()
	}
	flush()
	return tokens
}

func hasTrailingStatement(sql string) bool {
	inLineComment := false
	inBlockComment := false
	quote := rune(0)
	for i, r := range sql {
		if inLineComment {
			if r == '\n' || r == '\r' {
				inLineComment = false
			}
			continue
		}
		if inBlockComment {
			if r == '/' && i > 0 && sql[i-1] == '*' {
				inBlockComment = false
			}
			continue
		}
		if quote != 0 {
			if r == quote {
				if quote == '\'' && i+1 < len(sql) && sql[i+1] == '\'' {
					continue
				}
				quote = 0
			}
			continue
		}
		if r == '-' && i+1 < len(sql) && sql[i+1] == '-' {
			inLineComment = true
			continue
		}
		if r == '/' && i+1 < len(sql) && sql[i+1] == '*' {
			inBlockComment = true
			continue
		}
		if r == '\'' || r == '"' || r == '`' {
			quote = r
			continue
		}
		if r == ';' {
			return hasNonCommentText(sql[i+1:])
		}
	}
	return false
}

func hasSparseNumericParameter(sql string) bool {
	inLineComment := false
	inBlockComment := false
	quote := rune(0)
	numbered := map[int]bool{}
	plainCount := 0
	for i := 0; i < len(sql); i++ {
		ch := rune(sql[i])
		if inLineComment {
			if ch == '\n' || ch == '\r' {
				inLineComment = false
			}
			continue
		}
		if inBlockComment {
			if ch == '/' && i > 0 && sql[i-1] == '*' {
				inBlockComment = false
			}
			continue
		}
		if quote != 0 {
			if ch == quote {
				if quote == '\'' && i+1 < len(sql) && sql[i+1] == '\'' {
					i++
					continue
				}
				quote = 0
			}
			continue
		}
		if ch == '-' && i+1 < len(sql) && sql[i+1] == '-' {
			inLineComment = true
			continue
		}
		if ch == '/' && i+1 < len(sql) && sql[i+1] == '*' {
			inBlockComment = true
			continue
		}
		if ch == '\'' || ch == '"' || ch == '`' {
			quote = ch
			continue
		}
		if ch != '?' {
			continue
		}
		j := i + 1
		for j < len(sql) && sql[j] >= '0' && sql[j] <= '9' {
			j++
		}
		if j == i+1 {
			plainCount++
			continue
		}
		var n int
		for _, d := range sql[i+1 : j] {
			n = n*10 + int(d-'0')
		}
		if n <= 0 {
			return true
		}
		numbered[n] = true
		i = j - 1
	}
	if len(numbered) == 0 {
		return false
	}
	max := 0
	for n := range numbered {
		if n > max {
			max = n
		}
	}
	return max > len(numbered)+plainCount
}

func hasNonCommentText(s string) bool {
	for len(s) > 0 {
		s = strings.TrimSpace(s)
		if s == "" {
			return false
		}
		if strings.HasPrefix(s, "--") {
			if idx := strings.IndexAny(s, "\r\n"); idx >= 0 {
				s = s[idx+1:]
				continue
			}
			return false
		}
		if strings.HasPrefix(s, "/*") {
			idx := strings.Index(s[2:], "*/")
			if idx >= 0 {
				s = s[idx+4:]
				continue
			}
			return false
		}
		return true
	}
	return false
}

func loadAllowlist(path string) (allowlist, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return allowlist{}, err
	}
	var a allowlist
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&a); err != nil {
		return allowlist{}, err
	}
	if a.Version != 1 {
		return allowlist{}, errors.New("unsupported allowlist version")
	}
	if len(a.Databases) == 0 && len(a.DirectoryPrefixes) == 0 {
		return allowlist{}, errors.New("empty allowlist")
	}
	sum := sha256.Sum256(b)
	a.Hash = "sha256:" + hex.EncodeToString(sum[:8])
	return a, nil
}

func (s *server) authorizePath(requested string) (string, string, error) {
	if requested == "" || strings.ContainsRune(requested, 0) || !filepath.IsAbs(requested) {
		return "", "", errors.New("dbPath must be an absolute path")
	}
	canonical, err := filepath.EvalSymlinks(requested)
	if err != nil {
		return "", "", err
	}
	info, err := os.Stat(canonical)
	if err != nil {
		return "", "", err
	}
	if !info.Mode().IsRegular() {
		return "", "", errors.New("dbPath must resolve to a regular file")
	}
	if !s.allow.AllowSymlinks && canonical != filepath.Clean(requested) {
		return "", "", errors.New("symlink database paths are not allowed")
	}
	for _, db := range s.allow.Databases {
		p, err := filepath.EvalSymlinks(db.Path)
		if err != nil {
			continue
		}
		if canonical == p {
			return canonical, db.Mode, nil
		}
	}
	for _, prefix := range s.allow.DirectoryPrefixes {
		p, err := filepath.EvalSymlinks(prefix.Path)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(p, canonical)
		if err == nil && rel != "." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && rel != ".." {
			return canonical, prefix.Mode, nil
		}
	}
	return "", "", errors.New("dbPath is outside allowlist")
}

func urlPath(path string) string {
	return strings.ReplaceAll(path, "#", "%23")
}
