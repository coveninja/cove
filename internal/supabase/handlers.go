//go:build supabase

// Copyright (c) 2025 coveninja. All Rights Reserved.
// This file is proprietary and is not covered by the AGPL-3.0 license
// that applies to the rest of the Cove repository.

package supabase

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coveninja/cove/internal/activity"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/utils"
)

// Server wires together all the auth + sync HTTP handlers.
type Server struct {
	cfg           *Config
	profileStore  *profiles.Store
	lib           *library.Library
	st            *settings.Store
	addonMgr      *addons.Manager
	nuvioMgr      *nuvio.Manager
	activityStore *activity.Store

	// pushMu serializes background pushes; pushQueued coalesces bursts so
	// rapid sync calls queue at most one extra push run instead of spawning
	// an unbounded pile of overlapping goroutines.
	pushMu     sync.Mutex
	pushQueued atomic.Bool

	// pushErrMu guards lastPushErr and lastPushAt, which are written by the
	// push goroutine and read by handleSync. lastPushErr holds a joined
	// summary of all push errors from the most-recently-completed push run,
	// or "" on full success. Note that handleSync reports the PREVIOUS
	// completed push — the current one triggered by the sync call is async.
	pushErrMu   sync.Mutex
	lastPushErr string
	lastPushAt  time.Time

	// The following fields are guarded by pushMu (the push goroutine is the
	// only writer; they are read only while holding pushMu as well).
	// lastPushedGen / lastPushedGenOK / lastPushedProfile track the library
	// generation and profile that were successfully uploaded on the last push,
	// so that redundant full-library upserts can be skipped when nothing
	// changed — important now that the frontend polls every 60 s.
	lastPushedGen     uint64
	lastPushedGenOK   bool
	lastPushedProfile string
}

// pushAsync uploads the profile's library/settings/addons/nuvio/activity in
// the background. A push already queued behind a running one will observe
// this call's data too, so additional requests are dropped rather than stacked.
// After the push completes (success or partial failure), lastPushErr and
// lastPushAt are updated so the next handleSync response can surface them.
func (s *Server) pushAsync(userJWT, profileID, context string) {
	activeProfile, profileRevision := s.profileStore.ActiveSnapshot()
	if activeProfile.ID != profileID {
		s.recordPushResult("profile changed before push started")
		return
	}
	if !s.pushQueued.CompareAndSwap(false, true) {
		return
	}
	go func() {
		s.pushMu.Lock()
		defer s.pushMu.Unlock()
		s.pushQueued.Store(false)

		var pushErrs []string
		stale := false
		run := func(label string, fn func() error) {
			if stale {
				return
			}
			err := s.profileStore.WithActiveSnapshot(profileID, profileRevision, fn)
			if errors.Is(err, profiles.ErrActiveProfileChanged) {
				stale = true
				pushErrs = append(pushErrs, "profile changed while push was in flight")
				return
			}
			if err != nil {
				log.Println(context+": push "+label+":", err)
				pushErrs = append(pushErrs, label+": "+err.Error())
			}
		}

		// Read the library generation before PushLibrary reads entries: if a
		// mutation lands mid-push the counter will be bumped again, ensuring
		// the next cycle re-pushes — the safe direction.
		// Skip the full-library upsert when nothing has changed since the last
		// successful push. With 60 s frontend polling this avoids an otherwise
		// constant stream of full-library upserts on an idle desktop.
		run("library", func() error {
			gen := s.lib.Generation()
			if s.lastPushedGenOK && gen == s.lastPushedGen && profileID == s.lastPushedProfile {
				return nil
			}
			if err := s.cfg.PushLibrary(userJWT, profileID, s.lib); err != nil {
				return err
			}
			s.lastPushedGen = gen
			s.lastPushedGenOK = true
			s.lastPushedProfile = profileID
			return nil
		})
		run("settings", func() error {
			return s.cfg.PushSettings(userJWT, profileID, s.st)
		})
		run("addons", func() error {
			return s.cfg.PushAddons(userJWT, profileID, s.addonMgr)
		})
		if s.nuvioMgr != nil {
			run("nuvio", func() error {
				return s.cfg.PushNuvio(userJWT, profileID, s.nuvioMgr)
			})
		}
		if s.activityStore != nil {
			run("activity", func() error {
				return s.cfg.PushActivity(userJWT, profileID, s.activityStore)
			})
		}

		if activeProfile.SupabaseUID != nil {
			nameTS := activeProfile.NameUpdatedAt
			if nameTS.IsZero() {
				nameTS = time.Now()
			}
			run("profile", func() error {
				return s.cfg.EnsureProfile(
					userJWT,
					activeProfile.ID,
					*activeProfile.SupabaseUID,
					activeProfile.Name,
					activeProfile.IsPrimary,
					nameTS,
				)
			})
		}

		s.recordPushResult(strings.Join(pushErrs, "; "))
	}()
}

func (s *Server) recordPushResult(result string) {
	s.pushErrMu.Lock()
	s.lastPushErr = result
	s.lastPushAt = time.Now()
	s.pushErrMu.Unlock()
}

// NewServer creates the auth handler set. cfg may be nil (Supabase not configured),
// in which case all auth endpoints return 503.
func NewServer(
	cfg *Config,
	ps *profiles.Store,
	lib *library.Library,
	st *settings.Store,
	mgr *addons.Manager,
	nuvioMgr *nuvio.Manager,
	activityStore *activity.Store,
) *Server {
	return &Server{
		cfg:           cfg,
		profileStore:  ps,
		lib:           lib,
		st:            st,
		addonMgr:      mgr,
		nuvioMgr:      nuvioMgr,
		activityStore: activityStore,
	}
}

// SetupHandlers registers all /api/auth/* endpoints on mux.
func (s *Server) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/auth/register", utils.CorsMiddleware(s.handleRegister))
	mux.HandleFunc("/api/auth/register/confirm", utils.CorsMiddleware(s.handleConfirmRegistration))
	mux.HandleFunc("/api/auth/login", utils.CorsMiddleware(s.handleLogin))
	mux.HandleFunc("/api/auth/otp", utils.CorsMiddleware(s.handleOTP))
	mux.HandleFunc("/api/auth/verify-otp", utils.CorsMiddleware(s.handleVerifyOTP))
	mux.HandleFunc("/api/auth/logout", utils.CorsMiddleware(s.handleLogout))
	mux.HandleFunc("/api/auth/me", utils.CorsMiddleware(s.handleMe))
	mux.HandleFunc("/api/auth/sync", utils.CorsMiddleware(s.handleSync))
}

func (s *Server) notConfigured(w http.ResponseWriter) {
	http.Error(w, "Supabase not configured", http.StatusServiceUnavailable)
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// POST /api/auth/register  {email, password, profile_name}
// Creates a Supabase account, links the active local profile, pushes local data.
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	var body struct {
		Email       string `json:"email"`
		Password    string `json:"password"`
		ProfileName string `json:"profile_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Email == "" || body.Password == "" {
		http.Error(w, "email and password required", http.StatusBadRequest)
		return
	}

	userID, accessToken, err := s.cfg.SignUp(body.Email, body.Password)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if accessToken == "" {
		// Email confirmation is required. Tell the frontend to show OTP input.
		jsonOK(w, map[string]any{"confirmation_required": true})
		return
	}

	// Immediate session (email confirmation disabled in Supabase project settings).
	s.finishRegistration(w, userID, accessToken, "", body.ProfileName)
}

// POST /api/auth/register/confirm  {email, token, profile_name}
// Verifies the OTP from the signup confirmation email and creates the session.
func (s *Server) handleConfirmRegistration(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	var body struct {
		Email       string `json:"email"`
		Token       string `json:"token"`
		Password    string `json:"password"`
		ProfileName string `json:"profile_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Email == "" || body.Token == "" || body.Password == "" {
		http.Error(w, "email, token, and password required", http.StatusBadRequest)
		return
	}

	// Confirm the email address via OTP.
	if err := s.cfg.VerifySignup(body.Email, body.Token); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Supabase may not return a session from /verify, so sign in explicitly.
	userID, accessToken, refreshToken, err := s.cfg.SignIn(body.Email, body.Password)
	if err != nil {
		http.Error(w, "email confirmed but sign-in failed: "+err.Error(), http.StatusInternalServerError)
		return
	}

	s.finishRegistration(w, userID, accessToken, refreshToken, body.ProfileName)
}

// finishRegistration links the Supabase user to the active local profile,
// creates the remote profile row, kicks off an async data push, and writes
// the session response. Shared by handleRegister and handleConfirmRegistration.
func (s *Server) finishRegistration(w http.ResponseWriter, userID, accessToken, refreshToken, profileName string) {
	activeProfile := s.profileStore.ActiveProfile()
	if profileName == "" {
		profileName = activeProfile.Name
	}

	if err := s.cfg.EnsureProfile(accessToken, activeProfile.ID, userID, profileName, activeProfile.IsPrimary, time.Now()); err != nil {
		http.Error(w, "could not create remote profile: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := s.profileStore.LinkSupabase(activeProfile.ID, userID); err != nil {
		// Do not report registration success with an unpersisted local link.
		// Best-effort remote cleanup keeps a failed local transaction retryable.
		if cleanupErr := s.cfg.DeleteProfileData(accessToken, activeProfile.ID); cleanupErr != nil {
			log.Println("supabase register: roll back remote profile:", cleanupErr)
		}
		http.Error(w, "could not link local profile: "+err.Error(), http.StatusInternalServerError)
		return
	}

	s.pushAsync(accessToken, activeProfile.ID, "supabase register")

	jsonOK(w, map[string]any{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"profile":       s.profileStore.ActiveProfile(),
	})
}

// POST /api/auth/login  {email, password}
// Signs in, pulls remote data, merges into local store.
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	var body struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Email == "" {
		http.Error(w, "email and password required", http.StatusBadRequest)
		return
	}

	userID, accessToken, refreshToken, err := s.cfg.SignIn(body.Email, body.Password)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	if err := s.mergeRemote(userID, accessToken); err != nil {
		http.Error(w, "signed in but initial sync failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	onboardingDone := s.resolveAccountOnboardingDone(accessToken)
	jsonOK(w, map[string]any{
		"access_token":    accessToken,
		"refresh_token":   refreshToken,
		"profiles":        s.profileStore.All(),
		"active":          s.profileStore.ActiveProfile(),
		"onboarding_done": onboardingDone,
	})
}

// POST /api/auth/otp  {email}
// Sends an OTP / magic-link email.
func (s *Server) handleOTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	var body struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Email == "" {
		http.Error(w, "email required", http.StatusBadRequest)
		return
	}

	if err := s.cfg.SendOTP(body.Email); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	jsonOK(w, map[string]string{"status": "ok"})
}

// POST /api/auth/verify-otp  {email, token}
func (s *Server) handleVerifyOTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	var body struct {
		Email string `json:"email"`
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Email == "" || body.Token == "" {
		http.Error(w, "email and token required", http.StatusBadRequest)
		return
	}

	userID, accessToken, refreshToken, err := s.cfg.VerifyOTP(body.Email, body.Token)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	if err := s.mergeRemote(userID, accessToken); err != nil {
		http.Error(w, "signed in but initial sync failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	onboardingDone := s.resolveAccountOnboardingDone(accessToken)
	jsonOK(w, map[string]any{
		"access_token":    accessToken,
		"refresh_token":   refreshToken,
		"profiles":        s.profileStore.All(),
		"active":          s.profileStore.ActiveProfile(),
		"onboarding_done": onboardingDone,
	})
}

// resolveAccountOnboardingDone extends the active profile's merged setting with
// any completed profile owned by the same account. Fresh-device reconciliation
// can adopt a different profile ID, but onboarding is an application-level
// first-run flow and must not restart for an established account.
func (s *Server) resolveAccountOnboardingDone(userJWT string) bool {
	current := s.st.Get()
	if current.OnboardingDone {
		return true
	}
	done, err := s.cfg.AccountOnboardingDone(userJWT)
	if err != nil {
		log.Println("supabase: resolve account onboarding state:", err)
		return false
	}
	if !done {
		return false
	}
	current.OnboardingDone = true
	if err := s.st.Set(current); err != nil {
		log.Println("supabase: persist account onboarding state:", err)
	}
	return true
}

// POST /api/auth/logout — clear the SupabaseUID link from the active profile.
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := s.profileStore.UnlinkSupabase(s.profileStore.ActiveProfile().ID); err != nil {
		http.Error(w, "could not unlink local profile: "+err.Error(), http.StatusInternalServerError)
		return
	}
	jsonOK(w, map[string]string{"status": "ok"})
}

// GET /api/auth/me — return current auth state.
func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	active := s.profileStore.ActiveProfile()
	jsonOK(w, map[string]any{
		"profile": active,
		"linked":  active.SupabaseUID != nil,
	})
}

// POST /api/auth/sync — pull remote data and merge.
func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg == nil {
		s.notConfigured(w)
		return
	}

	// Extract and validate the JWT from the Authorization header.
	token := BearerFromRequest(r)
	if token == "" {
		http.Error(w, "authorization required", http.StatusUnauthorized)
		return
	}
	userID, err := s.cfg.ValidateJWT(token)
	if err != nil {
		http.Error(w, "invalid token: "+err.Error(), http.StatusUnauthorized)
		return
	}

	// Pull remote → merge locally.
	if err := s.mergeRemote(userID, token); err != nil {
		http.Error(w, "sync pull failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	// Push local → remote (catches any records that failed during initial push).
	s.pushAsync(token, s.profileStore.ActiveProfile().ID, "supabase sync")

	// Snapshot the last completed push error before responding.
	// Note: this reflects the PREVIOUS completed push; the push triggered
	// above is async and its result will be visible on the next sync call.
	s.pushErrMu.Lock()
	lastErr := s.lastPushErr
	s.pushErrMu.Unlock()

	// library_generation lets the frontend tell whether the merge actually
	// changed anything, so an idle focus-triggered sync doesn't force a full
	// UI refetch.
	jsonOK(w, map[string]any{
		"status":             "ok",
		"library_generation": s.lib.Generation(),
		"push_error":         lastErr,
	})
}

// reconcileProfile makes the active local profile and the account's remote
// profile rows agree before any pull or push. Three cases:
//   - the local profile ID already exists remotely → nothing to do;
//   - the account owns remote profiles but none match → adopt the remote
//     primary's ID locally, so every device syncs the same rows;
//   - the account owns no remote profiles → create the remote row for the
//     local ID (registration used to be the only place this happened).
//
// Without this, a device that didn't perform the original registration
// pushes rows whose profile_id has no owner in `profiles` — which RLS
// rejects with 42501 — and pulls a profile ID that has no remote data.
func (s *Server) reconcileProfile(supabaseUID, userJWT string) (profiles.Profile, error) {
	active := s.profileStore.ActiveProfile()

	remotes, err := s.cfg.RemoteProfilesForUser(userJWT, supabaseUID)
	if err != nil {
		return active, fmt.Errorf("list remote profiles: %w", err)
	}
	for _, r := range remotes {
		if r.ID == active.ID {
			if r.Name != "" && r.Name != active.Name && r.UpdatedAt.After(active.NameUpdatedAt) {
				if err := s.profileStore.RenameFromRemote(active.ID, r.Name, r.UpdatedAt); err != nil {
					return active, fmt.Errorf("reconcile profile name: %w", err)
				}
				return s.profileStore.ActiveProfile(), nil
			}
			return active, nil
		}
	}

	if len(remotes) == 0 {
		nameTS := active.NameUpdatedAt
		if nameTS.IsZero() {
			nameTS = time.Now()
		}
		if err := s.cfg.EnsureProfile(userJWT, active.ID, supabaseUID, active.Name, active.IsPrimary, nameTS); err != nil {
			return active, fmt.Errorf("create remote profile: %w", err)
		}
		return active, nil
	}

	target := remotes[0]
	for _, r := range remotes {
		if r.IsPrimary {
			target = r
			break
		}
	}
	if err := s.profileStore.AdoptID(active.ID, target.ID); err != nil {
		// A different local profile already owns the remote ID (or the
		// rename failed) — register this profile ID remotely instead so
		// pushes stop violating RLS; it becomes another profile of the
		// same account.
		log.Println("supabase: adopt remote profile:", err)
		nameTS := active.NameUpdatedAt
		if nameTS.IsZero() {
			nameTS = time.Now()
		}
		if err := s.cfg.EnsureProfile(userJWT, active.ID, supabaseUID, active.Name, active.IsPrimary, nameTS); err != nil {
			return active, fmt.Errorf("create remote profile after adoption failed: %w", err)
		}
		return active, nil
	}
	if target.Name != "" && target.Name != active.Name {
		if err := s.profileStore.RenameFromRemote(target.ID, target.Name, target.UpdatedAt); err != nil {
			log.Println("supabase: rename adopted profile:", err)
		}
	}
	log.Printf("supabase: local profile adopted remote profile %s (%q)", target.ID, target.Name)
	return s.profileStore.ActiveProfile(), nil
}

// CleanupDeletedProfile removes all remote Supabase data for a deleted profile.
// Returns nil immediately when Supabase is not configured, no JWT is present,
// or the profile was not linked to a Supabase account.
func (s *Server) CleanupDeletedProfile(userJWT, profileID string, supabaseUID *string) error {
	if s.cfg == nil || userJWT == "" || supabaseUID == nil {
		return nil
	}
	return s.cfg.DeleteProfileData(userJWT, profileID)
}

// mergeRemote pulls all Supabase data for a user and merges it into the active profile.
func (s *Server) mergeRemote(supabaseUID, userJWT string) error {
	original := s.profileStore.ActiveProfile()
	active, err := s.reconcileProfile(supabaseUID, userJWT)
	if err != nil {
		return err
	}

	pulled, err := s.cfg.PullAll(userJWT, active.ID)
	if err != nil {
		s.rollbackReconciliation(original, active)
		return fmt.Errorf("pull remote data: %w", err)
	}

	_, revision := s.profileStore.ActiveSnapshot()
	err = s.profileStore.WithActiveSnapshot(active.ID, revision, func() error {
		if err := s.lib.MergeFrom(pulled.Entries, pulled.Progress, pulled.Dismissals, pulled.Removals); err != nil {
			return err
		}

		if pulled.Settings != nil {
			if err := s.st.MergeFrom(*pulled.Settings); err != nil {
				return err
			}
		}

		// Addons: guard against no-remote-row so an account that never pushed
		// addons doesn't wipe locally configured ones.
		if pulled.AddonsPresent {
			if err := s.addonMgr.MergeFrom(pulled.Addons, pulled.AddonsUpdatedAt); err != nil {
				return err
			}
		}

		// Nuvio: guard against no-remote-row.
		if pulled.NuvioPresent && s.nuvioMgr != nil && len(pulled.NuvioData) > 0 {
			if err := s.nuvioMgr.MergeFromJSON(pulled.NuvioData, pulled.NuvioUpdatedAt); err != nil {
				return fmt.Errorf("merge nuvio: %w", err)
			}
		}

		// Activity: guard against no-remote-row.
		if pulled.ActivityPresent && s.activityStore != nil && len(pulled.ActivityData) > 0 {
			if err := s.activityStore.MergeFromJSON(pulled.ActivityData); err != nil {
				return fmt.Errorf("merge activity: %w", err)
			}
		}
		return nil
	})
	if err != nil {
		s.rollbackReconciliation(original, active)
		return fmt.Errorf("apply remote data: %w", err)
	}

	// Link only after the initial pull has been applied durably. A failed pull
	// must leave a guest profile retryable rather than half-signed-in.
	if active.SupabaseUID == nil {
		if err := s.profileStore.LinkSupabase(active.ID, supabaseUID); err != nil {
			s.rollbackReconciliation(original, active)
			return fmt.Errorf("link profile: %w", err)
		}
	}
	return nil
}

func (s *Server) rollbackReconciliation(original, reconciled profiles.Profile) {
	current := s.profileStore.ActiveProfile()
	if current.ID != original.ID && current.ID == reconciled.ID {
		if err := s.profileStore.AdoptID(current.ID, original.ID); err != nil {
			log.Println("supabase: roll back adopted profile:", err)
			return
		}
	}
	current = s.profileStore.ActiveProfile()
	if current.ID == original.ID &&
		(current.Name != original.Name || !current.NameUpdatedAt.Equal(original.NameUpdatedAt)) {
		if err := s.profileStore.RenameFromRemote(original.ID, original.Name, original.NameUpdatedAt); err != nil {
			log.Println("supabase: roll back profile name:", err)
		}
	}
}
