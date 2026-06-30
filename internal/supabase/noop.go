//go:build !supabase

// Package supabase provides a no-op stub when Cove is built without the
// proprietary Supabase integration. Auth endpoints are not registered and
// all credentials are ignored. Build with -tags supabase to enable.
package supabase

import (
	"net/http"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
)

// Config is a placeholder so main.go compiles without the real implementation.
type Config struct{}

// ConfigFromEnv always returns nil when built without -tags supabase.
func ConfigFromEnv(url, anonKey, serviceKey, jwtSecret string) *Config { return nil }

// Server is a no-op auth server.
type Server struct{}

// NewServer returns a no-op server. No auth routes will be registered.
func NewServer(
	_ *Config,
	_ *profiles.Store,
	_ *library.Library,
	_ *settings.Store,
	_ *addons.Manager,
) *Server {
	return &Server{}
}

// SetupHandlers registers nothing — Supabase integration not compiled in.
func (s *Server) SetupHandlers(_ *http.ServeMux) {}
