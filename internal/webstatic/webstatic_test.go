package webstatic

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"testing/fstest"
)

func preserveRegistration(t *testing.T) {
	t.Helper()
	original := registeredFS
	t.Cleanup(func() { registeredFS = original })
}

func TestMountWithoutRegistrationIsNoOp(t *testing.T) {
	preserveRegistration(t)
	registeredFS = nil
	mux := http.NewServeMux()
	Mount(mux)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want unregistered mux 404", rec.Code)
	}
}

func TestRegisterAndMountServeOnlyDistTree(t *testing.T) {
	preserveRegistration(t)
	Register(fstest.MapFS{
		"dist/index.html": &fstest.MapFile{Data: []byte("<h1>Cove</h1>")},
		"dist/app.js":     &fstest.MapFile{Data: []byte("console.log('cove')")},
		"private.txt":     &fstest.MapFile{Data: []byte("not public")},
	})
	mux := http.NewServeMux()
	Mount(mux)

	tests := []struct {
		path       string
		wantStatus int
		wantBody   string
	}{
		{"/", http.StatusOK, "<h1>Cove</h1>"},
		{"/app.js", http.StatusOK, "console.log('cove')"},
		{"/private.txt", http.StatusNotFound, ""},
	}
	for _, tt := range tests {
		t.Run(tt.path, func(t *testing.T) {
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, tt.path, nil))
			if rec.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tt.wantStatus)
			}
			if tt.wantBody != "" && rec.Body.String() != tt.wantBody {
				t.Fatalf("body = %q, want %q", rec.Body.String(), tt.wantBody)
			}
		})
	}
}

func TestMountHandlesFilesystemWithoutDist(t *testing.T) {
	preserveRegistration(t)
	Register(fstest.MapFS{"other/file.txt": &fstest.MapFile{Data: []byte("other")}})
	mux := http.NewServeMux()
	Mount(mux)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}
