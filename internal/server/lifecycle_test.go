package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/utils"
)

func isolatedServerConfig(t *testing.T) Config {
	t.Helper()
	return Config{
		BindAddr:       "127.0.0.1:0",
		DataDir:        t.TempDir(),
		CacheDir:       t.TempDir(),
		TorrentDir:     t.TempDir(),
		TMDBAPIKey:     "test-key",
		Version:        "dev",
		RemoteBindAddr: "127.0.0.1:0",
	}
}

func serverURL(h *Handle, path string) string {
	return "http://" + h.srv.Addr + path
}

func getJSON[T any](t *testing.T, client *http.Client, url string) T {
	t.Helper()
	resp, err := client.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("GET %s = %d: %s", url, resp.StatusCode, body)
	}
	var out T
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out
}

func requestJSON(t *testing.T, client *http.Client, method, url string, body any, wantStatus int) []byte {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(method, url, bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != wantStatus {
		t.Fatalf("%s %s = %d, want %d: %s", method, url, resp.StatusCode, wantStatus, responseBody)
	}
	return responseBody
}

func lanAddress(h *Handle) string {
	h.lanMu.Lock()
	defer h.lanMu.Unlock()
	if h.lanSrv == nil {
		return ""
	}
	return h.lanSrv.Addr
}

func waitForLANAddress(t *testing.T, h *Handle, wantRunning bool) string {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		addr := lanAddress(h)
		if (addr != "") == wantRunning {
			return addr
		}
		if time.Now().After(deadline) {
			t.Fatalf("LAN listener running=%v, want %v", addr != "", wantRunning)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestServerSettingsToggleLANListener(t *testing.T) {
	cfg := isolatedServerConfig(t)
	h, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Stop)
	client := &http.Client{Timeout: 2 * time.Second}

	snap := getJSON[settings.Settings](t, client, serverURL(h, "/api/settings"))
	snap.RemoteAccessEnabled = true
	snap.RemoteAccessToken = "toggle-secret"
	requestJSON(t, client, http.MethodPut, serverURL(h, "/api/settings"), snap, http.StatusOK)

	lanAddr := waitForLANAddress(t, h, true)
	req, err := http.NewRequest(http.MethodGet, "http://"+lanAddr+"/api/ping", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-Cove-Token", "toggle-secret")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("LAN ping = %d, want 200", resp.StatusCode)
	}

	snap.RemoteAccessEnabled = false
	requestJSON(t, client, http.MethodPut, serverURL(h, "/api/settings"), snap, http.StatusOK)
	waitForLANAddress(t, h, false)
}

func TestStartHonorsPreconfiguredRemoteAccessAndStopPreventsReopen(t *testing.T) {
	cfg := isolatedServerConfig(t)
	utils.SetDataDir(cfg.DataDir)
	profileStore, err := profiles.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	st, err := settings.New(profileStore.ActiveProfileID())
	if err != nil {
		t.Fatal(err)
	}
	snap := st.Get()
	snap.RemoteAccessEnabled = true
	snap.RemoteAccessToken = "initial-secret"
	if err := st.Set(snap); err != nil {
		t.Fatal(err)
	}

	h, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	lanAddr := waitForLANAddress(t, h, true)
	client := &http.Client{Timeout: 2 * time.Second}
	req, err := http.NewRequest(http.MethodGet, "http://"+lanAddr+"/api/ping", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-Cove-Token", "initial-secret")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("LAN ping = %d, want 200", resp.StatusCode)
	}

	h.Stop()
	h.startLAN("127.0.0.1:0", okHandler)
	if got := lanAddress(h); got != "" {
		t.Fatalf("stopped handle reopened LAN listener on %s", got)
	}
}

func TestServerProfileSwitchReloadsSubsystems(t *testing.T) {
	h, err := Start(isolatedServerConfig(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Stop)
	client := &http.Client{Timeout: 2 * time.Second}

	var list struct {
		Profiles        []profiles.Profile `json:"profiles"`
		ActiveProfileID string             `json:"active_profile_id"`
	}
	list = getJSON[struct {
		Profiles        []profiles.Profile `json:"profiles"`
		ActiveProfileID string             `json:"active_profile_id"`
	}](t, client, serverURL(h, "/api/profiles"))
	if len(list.Profiles) != 1 {
		t.Fatalf("profiles = %d, want 1", len(list.Profiles))
	}
	primaryID := list.ActiveProfileID

	raw := requestJSON(t, client, http.MethodPost, serverURL(h, "/api/profiles"), map[string]string{"name": "Kid"}, http.StatusOK)
	var kid profiles.Profile
	if err := json.Unmarshal(raw, &kid); err != nil {
		t.Fatal(err)
	}
	raw = requestJSON(t, client, http.MethodPost, serverURL(h, "/api/profiles/"+kid.ID+"/activate"), nil, http.StatusOK)
	var active profiles.Profile
	if err := json.Unmarshal(raw, &active); err != nil {
		t.Fatal(err)
	}
	if active.ID != kid.ID {
		t.Fatalf("active profile = %q, want %q", active.ID, kid.ID)
	}

	req, err := http.NewRequest(http.MethodDelete, serverURL(h, "/api/profiles/"+kid.ID), nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE profile = %d, want 204", resp.StatusCode)
	}

	deadline := time.Now().Add(2 * time.Second)
	for {
		list = getJSON[struct {
			Profiles        []profiles.Profile `json:"profiles"`
			ActiveProfileID string             `json:"active_profile_id"`
		}](t, client, serverURL(h, "/api/profiles"))
		if list.ActiveProfileID == primaryID {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("active profile = %q, want primary %q", list.ActiveProfileID, primaryID)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestServerProfileActivationRollsBackOnCorruptSidecar(t *testing.T) {
	cfg := isolatedServerConfig(t)
	utils.SetDataDir(cfg.DataDir)
	profileStore, err := profiles.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	primaryID := profileStore.ActiveProfileID()
	kid, err := profileStore.Create("Kid")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(cfg.DataDir, profiles.ProfileFileName("settings", kid.ID)),
		[]byte("{"),
		0o600,
	); err != nil {
		t.Fatal(err)
	}

	h, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Stop)
	client := &http.Client{Timeout: 2 * time.Second}

	requestJSON(
		t,
		client,
		http.MethodPost,
		serverURL(h, "/api/profiles/"+kid.ID+"/activate"),
		nil,
		http.StatusInternalServerError,
	)
	list := getJSON[struct {
		ActiveProfileID string `json:"active_profile_id"`
	}](t, client, serverURL(h, "/api/profiles"))
	if list.ActiveProfileID != primaryID {
		t.Fatalf("active profile = %q after failed activation, want %q", list.ActiveProfileID, primaryID)
	}

	reloaded, err := profiles.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.ActiveProfileID() != primaryID {
		t.Fatalf("persisted active profile = %q, want %q", reloaded.ActiveProfileID(), primaryID)
	}
}

func TestServerProfileSwitchReconcilesLANListener(t *testing.T) {
	cfg := isolatedServerConfig(t)
	utils.SetDataDir(cfg.DataDir)
	profileStore, err := profiles.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	primaryID := profileStore.ActiveProfileID()
	kid, err := profileStore.Create("Kid")
	if err != nil {
		t.Fatal(err)
	}
	kidSettings, err := settings.New(kid.ID)
	if err != nil {
		t.Fatal(err)
	}
	snap := kidSettings.Get()
	snap.RemoteAccessEnabled = true
	snap.RemoteAccessToken = "kid-secret"
	if err := kidSettings.Set(snap); err != nil {
		t.Fatal(err)
	}

	h, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Stop)
	client := &http.Client{Timeout: 2 * time.Second}
	waitForLANAddress(t, h, false)

	requestJSON(t, client, http.MethodPost, serverURL(h, "/api/profiles/"+kid.ID+"/activate"), nil, http.StatusOK)
	waitForLANAddress(t, h, true)

	requestJSON(t, client, http.MethodPost, serverURL(h, "/api/profiles/"+primaryID+"/activate"), nil, http.StatusOK)
	waitForLANAddress(t, h, false)
}

func TestStartContinuesWithCorruptBestEffortStoresAndBrokenImageCache(t *testing.T) {
	cfg := isolatedServerConfig(t)
	utils.SetDataDir(cfg.DataDir)
	profileStore, err := profiles.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	id := profileStore.ActiveProfileID()
	for _, base := range []string{"settings", "library", "activity"} {
		path := filepath.Join(cfg.DataDir, fmt.Sprintf("%s-%s.json", base, id))
		if err := os.WriteFile(path, []byte("{"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	cacheBlocker := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(cacheBlocker, []byte("file"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg.CacheDir = filepath.Join(cacheBlocker, "images")

	h, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Stop)
	client := &http.Client{Timeout: 2 * time.Second}
	body := getJSON[map[string]string](t, client, serverURL(h, "/api/ping"))
	if body["status"] != "ok" {
		t.Fatalf("ping body = %#v", body)
	}
	resp, err := client.Get(serverURL(h, "/api/img/w500/missing.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("image route = %d, want 404 when cache init failed", resp.StatusCode)
	}
}

func TestStartFailurePaths(t *testing.T) {
	t.Run("profiles", func(t *testing.T) {
		blocker := filepath.Join(t.TempDir(), "not-a-directory")
		if err := os.WriteFile(blocker, []byte("file"), 0o600); err != nil {
			t.Fatal(err)
		}
		h, err := Start(Config{
			BindAddr:   "127.0.0.1:0",
			DataDir:    blocker,
			CacheDir:   t.TempDir(),
			TorrentDir: t.TempDir(),
		})
		if err == nil || !strings.Contains(err.Error(), "could not init profiles") {
			t.Fatalf("Start = %#v, %v; want profiles error", h, err)
		}
	})

	t.Run("occupied main address", func(t *testing.T) {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer ln.Close()
		cfg := isolatedServerConfig(t)
		cfg.BindAddr = ln.Addr().String()
		h, err := Start(cfg)
		if err == nil || !strings.Contains(err.Error(), "bind ") {
			if h != nil {
				h.Stop()
			}
			t.Fatalf("Start = %#v, %v; want bind error", h, err)
		}
	})
}

func TestStopLANForcesActiveRequestClosedAfterTimeout(t *testing.T) {
	original := lanShutdownTimeout
	lanShutdownTimeout = 20 * time.Millisecond
	t.Cleanup(func() { lanShutdownTimeout = original })

	entered := make(chan struct{})
	h := &Handle{}
	h.startLAN("127.0.0.1:0", http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
	}))
	addr := lanAddress(h)
	if addr == "" {
		t.Fatal("LAN listener did not start")
	}

	requestDone := make(chan struct{})
	go func() {
		resp, _ := (&http.Client{}).Get("http://" + addr + "/blocking")
		if resp != nil {
			_ = resp.Body.Close()
		}
		close(requestDone)
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("blocking LAN handler was not entered")
	}

	h.stopLAN()
	if got := lanAddress(h); got != "" {
		t.Fatalf("LAN listener retained after forced close: %s", got)
	}
	select {
	case <-requestDone:
	case <-time.After(2 * time.Second):
		t.Fatal("active LAN request survived forced close")
	}
}

func TestStopForcesMainServerClosedAfterTimeout(t *testing.T) {
	original := serverShutdownTimeout
	serverShutdownTimeout = 20 * time.Millisecond
	t.Cleanup(func() { serverShutdownTimeout = original })

	entered := make(chan struct{})
	var enterOnce sync.Once
	srv := &http.Server{Handler: http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		enterOnce.Do(func() { close(entered) })
		<-r.Context().Done()
	})}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv.Addr = ln.Addr().String()
	go func() { _ = srv.Serve(ln) }()

	h := &Handle{cancel: func() {}, srv: srv}
	requestDone := make(chan struct{})
	go func() {
		resp, _ := (&http.Client{}).Get("http://" + srv.Addr + "/blocking")
		if resp != nil {
			_ = resp.Body.Close()
		}
		close(requestDone)
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("blocking main handler was not entered")
	}

	h.Stop()
	select {
	case <-requestDone:
	case <-time.After(2 * time.Second):
		t.Fatal("active main request survived forced close")
	}
	h.startLAN("127.0.0.1:0", okHandler)
	if got := lanAddress(h); got != "" {
		t.Fatalf("stopped handle reopened LAN listener on %s", got)
	}
}
