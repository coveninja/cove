package profiles

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func profileHandlerRequest(mux *http.ServeMux, method, path, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func TestHandleListCreateAndValidation(t *testing.T) {
	s := newStore(t)
	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	rec := profileHandlerRequest(mux, http.MethodPost, "/api/profiles", `{"name":"  Kid  "}`)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var created Profile
	require.NoError(t, json.NewDecoder(rec.Body).Decode(&created))
	assert.Equal(t, "Kid", created.Name)
	stored, ok := s.Get(created.ID)
	require.True(t, ok)
	assert.Equal(t, "Kid", stored.Name)

	cases := []struct {
		name   string
		method string
		body   string
		status int
	}{
		{name: "malformed JSON", method: http.MethodPost, body: `{`, status: http.StatusBadRequest},
		{name: "missing name", method: http.MethodPost, body: `{}`, status: http.StatusBadRequest},
		{name: "blank name", method: http.MethodPost, body: `{"name":"   "}`, status: http.StatusBadRequest},
		{
			name:   "oversized body",
			method: http.MethodPost,
			body:   `{"name":"` + strings.Repeat("x", maxProfileRequestBytes) + `"}`,
			status: http.StatusBadRequest,
		},
		{name: "unsupported method", method: http.MethodPut, status: http.StatusMethodNotAllowed},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := profileHandlerRequest(mux, tc.method, "/api/profiles", tc.body)
			assert.Equal(t, tc.status, rec.Code, rec.Body.String())
		})
	}
}

func TestHandleByIDLifecycleAndRouting(t *testing.T) {
	s := newStore(t)
	primary := s.ActiveProfile()
	kid, err := s.Create("Kid")
	require.NoError(t, err)
	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	rec := profileHandlerRequest(mux, http.MethodPatch, "/api/profiles/"+kid.ID, `{"name":"  Renamed Kid  "}`)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var renamed map[string]string
	require.NoError(t, json.NewDecoder(rec.Body).Decode(&renamed))
	assert.Equal(t, "Renamed Kid", renamed["name"])
	stored, ok := s.Get(kid.ID)
	require.True(t, ok)
	assert.Equal(t, "Renamed Kid", stored.Name)

	rec = profileHandlerRequest(mux, http.MethodPatch, "/api/profiles/"+kid.ID+"/unknown", `{"name":"Wrong"}`)
	assert.Equal(t, http.StatusNotFound, rec.Code)
	stored, _ = s.Get(kid.ID)
	assert.Equal(t, "Renamed Kid", stored.Name)

	rec = profileHandlerRequest(mux, http.MethodPost, "/api/profiles/"+kid.ID+"/activate", "")
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	assert.Equal(t, kid.ID, s.ActiveProfileID())
	var active Profile
	require.NoError(t, json.NewDecoder(rec.Body).Decode(&active))
	assert.Equal(t, kid.ID, active.ID)

	cases := []struct {
		name   string
		method string
		path   string
		body   string
		status int
	}{
		{
			name:   "invalid ID",
			method: http.MethodPatch,
			path:   "/api/profiles/invalid.id",
			body:   `{"name":"Name"}`,
			status: http.StatusBadRequest,
		},
		{
			name:   "malformed rename",
			method: http.MethodPatch,
			path:   "/api/profiles/" + kid.ID,
			body:   `{`,
			status: http.StatusBadRequest,
		},
		{
			name:   "blank rename",
			method: http.MethodPatch,
			path:   "/api/profiles/" + kid.ID,
			body:   `{"name":" "}`,
			status: http.StatusBadRequest,
		},
		{
			name:   "oversized rename",
			method: http.MethodPatch,
			path:   "/api/profiles/" + kid.ID,
			body:   `{"name":"` + strings.Repeat("x", maxProfileRequestBytes) + `"}`,
			status: http.StatusBadRequest,
		},
		{
			name:   "rename missing profile",
			method: http.MethodPatch,
			path:   "/api/profiles/missing-id",
			body:   `{"name":"Name"}`,
			status: http.StatusBadRequest,
		},
		{
			name:   "activate wrong method",
			method: http.MethodGet,
			path:   "/api/profiles/" + kid.ID + "/activate",
			status: http.StatusMethodNotAllowed,
		},
		{
			name:   "activate missing profile",
			method: http.MethodPost,
			path:   "/api/profiles/missing-id/activate",
			status: http.StatusBadRequest,
		},
		{
			name:   "delete missing profile",
			method: http.MethodDelete,
			path:   "/api/profiles/missing-id",
			status: http.StatusBadRequest,
		},
		{
			name:   "delete primary",
			method: http.MethodDelete,
			path:   "/api/profiles/" + primary.ID,
			status: http.StatusBadRequest,
		},
		{
			name:   "unsupported base method",
			method: http.MethodGet,
			path:   "/api/profiles/" + kid.ID,
			status: http.StatusMethodNotAllowed,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := profileHandlerRequest(mux, tc.method, tc.path, tc.body)
			assert.Equal(t, tc.status, rec.Code, rec.Body.String())
		})
	}

	rec = profileHandlerRequest(mux, http.MethodDelete, "/api/profiles/"+kid.ID, "")
	assert.Equal(t, http.StatusNoContent, rec.Code, rec.Body.String())
	_, ok = s.Get(kid.ID)
	assert.False(t, ok)
}

func TestHandlerPersistenceFailuresReturnServerError(t *testing.T) {
	t.Run("create", func(t *testing.T) {
		s := newStore(t)
		before := s.All()
		forceProfileWriteFailure(t, s)
		mux := http.NewServeMux()
		s.SetupHandlers(mux)

		rec := profileHandlerRequest(mux, http.MethodPost, "/api/profiles", `{"name":"Kid"}`)
		assert.Equal(t, http.StatusInternalServerError, rec.Code, rec.Body.String())
		assert.Equal(t, before, s.All())
	})

	t.Run("rename", func(t *testing.T) {
		s := newStore(t)
		before := s.ActiveProfile()
		forceProfileWriteFailure(t, s)
		mux := http.NewServeMux()
		s.SetupHandlers(mux)

		rec := profileHandlerRequest(mux, http.MethodPatch, "/api/profiles/"+before.ID, `{"name":"Changed"}`)
		assert.Equal(t, http.StatusInternalServerError, rec.Code, rec.Body.String())
		assert.Equal(t, before, s.ActiveProfile())
	})

	t.Run("activate", func(t *testing.T) {
		s := newStore(t)
		primaryID := s.ActiveProfileID()
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		forceProfileWriteFailure(t, s)
		mux := http.NewServeMux()
		s.SetupHandlers(mux)

		rec := profileHandlerRequest(mux, http.MethodPost, "/api/profiles/"+kid.ID+"/activate", "")
		assert.Equal(t, http.StatusInternalServerError, rec.Code, rec.Body.String())
		assert.Equal(t, primaryID, s.ActiveProfileID())
	})

	t.Run("delete", func(t *testing.T) {
		s := newStore(t)
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		forceProfileWriteFailure(t, s)
		mux := http.NewServeMux()
		s.SetupHandlers(mux)

		rec := profileHandlerRequest(mux, http.MethodDelete, "/api/profiles/"+kid.ID, "")
		assert.Equal(t, http.StatusInternalServerError, rec.Code, rec.Body.String())
		_, ok := s.Get(kid.ID)
		assert.True(t, ok)
	})
}
