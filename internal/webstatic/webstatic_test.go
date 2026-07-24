package webstatic

import (
	"errors"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"testing"
	"testing/fstest"
)

type failingSubFS struct{}

func (failingSubFS) Open(string) (fs.File, error) {
	return nil, fs.ErrNotExist
}

func (failingSubFS) Sub(string) (fs.FS, error) {
	return nil, errors.New("sub failed")
}

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

func TestMountHandlesSubFilesystemFailure(t *testing.T) {
	preserveRegistration(t)
	Register(failingSubFS{})
	mux := http.NewServeMux()
	Mount(mux)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404 after sub-filesystem failure", rec.Code)
	}
}

func TestMountedFilesSupportHeadAndRejectWrites(t *testing.T) {
	preserveRegistration(t)
	Register(fstest.MapFS{
		"dist/index.html": &fstest.MapFile{Data: []byte("<h1>Cove</h1>")},
	})
	mux := http.NewServeMux()
	Mount(mux)

	head := httptest.NewRecorder()
	mux.ServeHTTP(head, httptest.NewRequest(http.MethodHead, "/", nil))
	if head.Code != http.StatusOK {
		t.Fatalf("HEAD status = %d, want 200", head.Code)
	}
	if head.Body.Len() != 0 {
		t.Fatalf("HEAD body length = %d, want 0", head.Body.Len())
	}
	if got := head.Header().Get("Content-Length"); got != "13" {
		t.Fatalf("HEAD Content-Length = %q, want 13", got)
	}

	post := httptest.NewRecorder()
	mux.ServeHTTP(post, httptest.NewRequest(http.MethodPost, "/", nil))
	if post.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST status = %d, want 405", post.Code)
	}
}
