package profiles

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func writeProfilesFixture(t *testing.T, disk diskStore) string {
	t.Helper()
	path, err := utils.ConfigPath("profiles.json")
	require.NoError(t, err)
	data, err := json.Marshal(disk)
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(path, data, 0o644))
	return path
}

func forceProfileWriteFailure(t *testing.T, s *Store) {
	t.Helper()
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	require.NoError(t, os.WriteFile(blocker, []byte("file"), 0o644))
	s.path = filepath.Join(blocker, "profiles.json")
}

func TestNewLoadsExistingStore(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	uid := "user-123"
	want := diskStore{
		Profiles: []Profile{
			{ID: "primary-id", Name: "Primary", IsPrimary: true, SupabaseUID: &uid},
			{ID: "secondary-id", Name: "Kid"},
		},
		ActiveProfileID: "secondary-id",
	}
	writeProfilesFixture(t, want)

	s, err := New(nil)
	require.NoError(t, err)
	assert.Equal(t, "secondary-id", s.ActiveProfileID())
	assert.Equal(t, want.Profiles, s.All())
}

func TestNewRepairsAndPersistsStoreInvariants(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	path := writeProfilesFixture(t, diskStore{
		Profiles: []Profile{
			{ID: "first-id", Name: "First"},
			{ID: "second-id", Name: "Second"},
		},
		ActiveProfileID: "missing-id",
	})

	s, err := New(nil)
	require.NoError(t, err)
	assert.Equal(t, "first-id", s.ActiveProfileID())
	profiles := s.All()
	require.Len(t, profiles, 2)
	assert.True(t, profiles[0].IsPrimary)
	assert.False(t, profiles[1].IsPrimary)

	data, err := os.ReadFile(path)
	require.NoError(t, err)
	var persisted diskStore
	require.NoError(t, json.Unmarshal(data, &persisted))
	assert.Equal(t, "first-id", persisted.ActiveProfileID)
	assert.True(t, persisted.Profiles[0].IsPrimary)
}

func TestNewRepairsEmptyStore(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	path := writeProfilesFixture(t, diskStore{})

	s, err := New(nil)
	require.NoError(t, err)
	require.Len(t, s.All(), 1)
	assert.True(t, s.All()[0].IsPrimary)
	assert.Equal(t, s.All()[0].ID, s.ActiveProfileID())

	data, err := os.ReadFile(path)
	require.NoError(t, err)
	var persisted diskStore
	require.NoError(t, json.Unmarshal(data, &persisted))
	require.Len(t, persisted.Profiles, 1)
	assert.Equal(t, persisted.Profiles[0].ID, persisted.ActiveProfileID)
}

func TestNewRejectsInvalidStores(t *testing.T) {
	t.Run("malformed JSON", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		path, err := utils.ConfigPath("profiles.json")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte("{"), 0o644))
		_, err = New(nil)
		assert.Error(t, err)
	})

	t.Run("profiles path is a directory", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		path, err := utils.ConfigPath("profiles.json")
		require.NoError(t, err)
		require.NoError(t, os.Mkdir(path, 0o755))
		_, err = New(nil)
		assert.Error(t, err)
	})

	t.Run("invalid profile ID", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		writeProfilesFixture(t, diskStore{
			Profiles:        []Profile{{ID: "../escape", Name: "Bad", IsPrimary: true}},
			ActiveProfileID: "../escape",
		})
		_, err := New(nil)
		assert.ErrorContains(t, err, "invalid profile id")
	})

	t.Run("duplicate profile ID", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		writeProfilesFixture(t, diskStore{
			Profiles: []Profile{
				{ID: "duplicate", Name: "One", IsPrimary: true},
				{ID: "duplicate", Name: "Two"},
			},
			ActiveProfileID: "duplicate",
		})
		_, err := New(nil)
		assert.ErrorContains(t, err, "duplicate profile id")
	})

	t.Run("config directory failure", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", "relative-config")
		t.Setenv("HOME", "")
		_, err := New(nil)
		assert.Error(t, err)
	})
}

func TestNormalizeDiskStoreDemotesExtraPrimary(t *testing.T) {
	disk := diskStore{
		Profiles: []Profile{
			{ID: "primary-one", IsPrimary: true},
			{ID: "primary-two", IsPrimary: true},
		},
		ActiveProfileID: "primary-two",
	}
	changed, err := normalizeDiskStore(&disk)
	require.NoError(t, err)
	assert.True(t, changed)
	assert.True(t, disk.Profiles[0].IsPrimary)
	assert.False(t, disk.Profiles[1].IsPrimary)
	assert.Equal(t, "primary-two", disk.ActiveProfileID)
}

func TestNewMigratesLegacyFilesAfterPersistingProfile(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	for _, base := range []string{"library", "settings", "addons"} {
		path, err := utils.ConfigPath(base + ".json")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte(base+"-data"), 0o644))
	}

	s, err := New(nil)
	require.NoError(t, err)
	profileID := s.ActiveProfileID()

	for _, base := range []string{"library", "settings", "addons"} {
		oldPath, err := utils.ConfigPath(base + ".json")
		require.NoError(t, err)
		_, err = os.Stat(oldPath)
		assert.True(t, os.IsNotExist(err), "%s should be migrated", oldPath)

		newPath, err := utils.ConfigPath(ProfileFileName(base, profileID))
		require.NoError(t, err)
		data, err := os.ReadFile(newPath)
		require.NoError(t, err)
		assert.Equal(t, base+"-data", string(data))
	}
}

func TestNewRetriesInterruptedLegacyMigration(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	writeProfilesFixture(t, diskStore{
		Profiles:        []Profile{{ID: "existing-primary", Name: "Primary", IsPrimary: true}},
		ActiveProfileID: "existing-primary",
	})
	legacyPath, err := utils.ConfigPath("settings.json")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(legacyPath, []byte("legacy-settings"), 0o644))

	_, err = New(nil)
	require.NoError(t, err)

	_, err = os.Stat(legacyPath)
	assert.True(t, os.IsNotExist(err))
	migratedPath, err := utils.ConfigPath(ProfileFileName("settings", "existing-primary"))
	require.NoError(t, err)
	data, err := os.ReadFile(migratedPath)
	require.NoError(t, err)
	assert.Equal(t, "legacy-settings", string(data))
}

func TestMigrateLegacyFilesPreservesExistingDestination(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	src, err := utils.ConfigPath("library.json")
	require.NoError(t, err)
	dst, err := utils.ConfigPath(ProfileFileName("library", "existing-id"))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(src, []byte("legacy"), 0o644))
	require.NoError(t, os.WriteFile(dst, []byte("existing"), 0o644))

	migrateLegacyFiles("existing-id")

	srcData, err := os.ReadFile(src)
	require.NoError(t, err)
	dstData, err := os.ReadFile(dst)
	require.NoError(t, err)
	assert.Equal(t, "legacy", string(srcData))
	assert.Equal(t, "existing", string(dstData))
}

func TestReturnedProfilesDoNotAliasSupabaseUID(t *testing.T) {
	s := newStore(t)
	id := s.ActiveProfileID()
	require.NoError(t, s.LinkSupabase(id, "original-uid"))

	active := s.ActiveProfile()
	require.NotNil(t, active.SupabaseUID)
	*active.SupabaseUID = "changed-active"

	all := s.All()
	require.NotNil(t, all[0].SupabaseUID)
	*all[0].SupabaseUID = "changed-all"

	got, ok := s.Get(id)
	require.True(t, ok)
	require.NotNil(t, got.SupabaseUID)
	assert.Equal(t, "original-uid", *got.SupabaseUID)
	*got.SupabaseUID = "changed-get"

	again := s.ActiveProfile()
	require.NotNil(t, again.SupabaseUID)
	assert.Equal(t, "original-uid", *again.SupabaseUID)
}

func TestActiveProfileFallbacks(t *testing.T) {
	s := &Store{disk: diskStore{
		Profiles:        []Profile{{ID: "first-id", Name: "First"}},
		ActiveProfileID: "missing",
	}}
	assert.Equal(t, "first-id", s.ActiveProfile().ID)

	s.disk.Profiles = nil
	assert.Equal(t, Profile{}, s.ActiveProfile())
}

func TestActiveSnapshotRejectsStaleTransitions(t *testing.T) {
	s := newStore(t)
	active, revision := s.ActiveSnapshot()
	assert.Equal(t, s.ActiveProfileID(), active.ID)

	called := false
	require.NoError(t, s.WithActiveSnapshot(active.ID, revision, func() error {
		called = true
		return nil
	}))
	assert.True(t, called)
	require.ErrorIs(
		t,
		s.WithActiveSnapshot("other-profile", revision, func() error {
			t.Fatal("stale snapshot callback must not run")
			return nil
		}),
		ErrActiveProfileChanged,
	)

	kid, err := s.Create("Kid")
	require.NoError(t, err)
	require.NoError(t, s.SetActive(kid.ID))
	require.ErrorIs(
		t,
		s.WithActiveSnapshot(active.ID, revision, func() error {
			t.Fatal("superseded revision callback must not run")
			return nil
		}),
		ErrActiveProfileChanged,
	)

	empty := &Store{}
	got, gotRevision := empty.ActiveSnapshot()
	assert.Equal(t, Profile{}, got)
	assert.Zero(t, gotRevision)
}

func TestSetActiveSameIDIsNoOp(t *testing.T) {
	called := make(chan string, 1)
	s := newStore(t)
	s.onChange = func(_ string, id string) error {
		called <- id
		return nil
	}
	forceProfileWriteFailure(t, s)

	require.NoError(t, s.SetActive(s.ActiveProfileID()))
	select {
	case id := <-called:
		t.Fatalf("onChange called for no-op activation: %s", id)
	default:
	}
}

func TestSetActiveRollsBackWhenTransitionHookFails(t *testing.T) {
	s := newStore(t)
	primaryID := s.ActiveProfileID()
	kid, err := s.Create("Kid")
	require.NoError(t, err)

	var transitions [][2]string
	s.SetOnChange(func(previousID, profileID string) error {
		transitions = append(transitions, [2]string{previousID, profileID})
		if profileID == kid.ID {
			return fmt.Errorf("corrupt target store")
		}
		return nil
	})

	err = s.SetActive(kid.ID)
	require.ErrorContains(t, err, "corrupt target store")
	assert.Equal(t, primaryID, s.ActiveProfileID())
	assert.Equal(t, [][2]string{
		{primaryID, kid.ID},
		{kid.ID, primaryID},
	}, transitions)

	reloaded, reloadErr := New(nil)
	require.NoError(t, reloadErr)
	assert.Equal(t, primaryID, reloaded.ActiveProfileID(), "rollback must be persisted")
}

func TestDeleteActiveRollsBackBeforeRemovingFilesWhenTransitionFails(t *testing.T) {
	s := newStore(t)
	kid, err := s.Create("Kid")
	require.NoError(t, err)
	require.NoError(t, s.SetActive(kid.ID))

	kidLibrary, err := utils.ConfigPath(ProfileFileName("library", kid.ID))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(kidLibrary, []byte(`{"entries":[]}`), 0o600))

	s.SetOnChange(func(_, profileID string) error {
		if profileID != kid.ID {
			return fmt.Errorf("primary reload failed")
		}
		return nil
	})

	err = s.Delete(kid.ID)
	require.ErrorContains(t, err, "primary reload failed")
	assert.Equal(t, kid.ID, s.ActiveProfileID())
	_, ok := s.Get(kid.ID)
	assert.True(t, ok)
	_, statErr := os.Stat(kidLibrary)
	assert.NoError(t, statErr, "data files must survive a rolled-back deletion")
}

func TestMutationNotFoundErrors(t *testing.T) {
	s := newStore(t)
	assert.Error(t, s.SetActive("missing-id"))
	assert.Error(t, s.Rename("missing-id", "Name"))
	assert.Error(t, s.Delete("missing-id"))
}

func TestMutationWriteFailuresRollBack(t *testing.T) {
	t.Run("create", func(t *testing.T) {
		s := newStore(t)
		before := s.All()
		forceProfileWriteFailure(t, s)
		p, err := s.Create("Not Persisted")
		assert.Error(t, err)
		assert.Equal(t, Profile{}, p)
		assert.Equal(t, before, s.All())
	})

	t.Run("set active", func(t *testing.T) {
		s := newStore(t)
		primaryID := s.ActiveProfileID()
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		called := make(chan string, 1)
		s.onChange = func(_ string, id string) error {
			called <- id
			return nil
		}
		forceProfileWriteFailure(t, s)

		assert.Error(t, s.SetActive(kid.ID))
		assert.Equal(t, primaryID, s.ActiveProfileID())
		select {
		case id := <-called:
			t.Fatalf("onChange called after failed write: %s", id)
		default:
		}
	})

	t.Run("rename", func(t *testing.T) {
		s := newStore(t)
		before := s.ActiveProfile()
		forceProfileWriteFailure(t, s)
		assert.Error(t, s.Rename(before.ID, "Not Persisted"))
		assert.Equal(t, before, s.ActiveProfile())
	})

	t.Run("remote rename", func(t *testing.T) {
		s := newStore(t)
		before := s.ActiveProfile()
		forceProfileWriteFailure(t, s)
		assert.Error(t, s.RenameFromRemote(before.ID, "Remote", time.Now()))
		assert.Equal(t, before, s.ActiveProfile())
	})

	t.Run("link", func(t *testing.T) {
		s := newStore(t)
		id := s.ActiveProfileID()
		forceProfileWriteFailure(t, s)
		assert.Error(t, s.LinkSupabase(id, "not-persisted"))
		assert.Nil(t, s.ActiveProfile().SupabaseUID)
	})

	t.Run("unlink", func(t *testing.T) {
		s := newStore(t)
		id := s.ActiveProfileID()
		require.NoError(t, s.LinkSupabase(id, "persisted-uid"))
		forceProfileWriteFailure(t, s)
		assert.Error(t, s.UnlinkSupabase(id))
		require.NotNil(t, s.ActiveProfile().SupabaseUID)
		assert.Equal(t, "persisted-uid", *s.ActiveProfile().SupabaseUID)
	})

	t.Run("delete", func(t *testing.T) {
		s := newStore(t)
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		dataPath, err := utils.ConfigPath(ProfileFileName("library", kid.ID))
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(dataPath, []byte("{}"), 0o644))
		forceProfileWriteFailure(t, s)

		assert.Error(t, s.Delete(kid.ID))
		_, ok := s.Get(kid.ID)
		assert.True(t, ok)
		_, err = os.Stat(dataPath)
		assert.NoError(t, err)
	})
}

func TestAdoptIDDestinationCollisionRollsBackEarlierMoves(t *testing.T) {
	s := newStore(t)
	oldID := s.ActiveProfileID()
	newID := "remote-profile-id"

	oldLibrary, err := utils.ConfigPath(ProfileFileName("library", oldID))
	require.NoError(t, err)
	oldSettings, err := utils.ConfigPath(ProfileFileName("settings", oldID))
	require.NoError(t, err)
	newSettings, err := utils.ConfigPath(ProfileFileName("settings", newID))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(oldLibrary, []byte("library"), 0o644))
	require.NoError(t, os.WriteFile(oldSettings, []byte("settings"), 0o644))
	require.NoError(t, os.WriteFile(newSettings, []byte("existing"), 0o644))

	err = s.AdoptID(oldID, newID)
	assert.ErrorContains(t, err, "already exists")
	assert.Equal(t, oldID, s.ActiveProfileID())

	data, err := os.ReadFile(oldLibrary)
	require.NoError(t, err)
	assert.Equal(t, "library", string(data))
	data, err = os.ReadFile(oldSettings)
	require.NoError(t, err)
	assert.Equal(t, "settings", string(data))
	data, err = os.ReadFile(newSettings)
	require.NoError(t, err)
	assert.Equal(t, "existing", string(data))
}

func TestAdoptIDWriteFailureRollsBackFilesAndState(t *testing.T) {
	s := newStore(t)
	oldID := s.ActiveProfileID()
	newID := "remote-profile-id"
	oldPath, err := utils.ConfigPath(ProfileFileName("library", oldID))
	require.NoError(t, err)
	newPath, err := utils.ConfigPath(ProfileFileName("library", newID))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(oldPath, []byte("library"), 0o644))
	forceProfileWriteFailure(t, s)

	assert.Error(t, s.AdoptID(oldID, newID))
	assert.Equal(t, oldID, s.ActiveProfileID())
	_, ok := s.Get(oldID)
	assert.True(t, ok)
	_, ok = s.Get(newID)
	assert.False(t, ok)
	data, err := os.ReadFile(oldPath)
	require.NoError(t, err)
	assert.Equal(t, "library", string(data))
	_, err = os.Stat(newPath)
	assert.True(t, os.IsNotExist(err))
}

func TestAdoptIDRejectsNonRegularSource(t *testing.T) {
	s := newStore(t)
	oldID := s.ActiveProfileID()
	path, err := utils.ConfigPath(ProfileFileName("library", oldID))
	require.NoError(t, err)
	require.NoError(t, os.Mkdir(path, 0o755))

	err = s.AdoptID(oldID, "remote-profile-id")
	assert.ErrorContains(t, err, "not a regular file")
	assert.Equal(t, oldID, s.ActiveProfileID())
}

func TestAdoptIDNonActiveProfileLeavesActiveProfileUnchanged(t *testing.T) {
	s := newStore(t)
	primaryID := s.ActiveProfileID()
	kid, err := s.Create("Kid")
	require.NoError(t, err)
	called := make(chan string, 1)
	s.onChange = func(_ string, id string) error {
		called <- id
		return nil
	}

	require.NoError(t, s.AdoptID(kid.ID, "adopted-kid-id"))
	assert.Equal(t, primaryID, s.ActiveProfileID())
	_, ok := s.Get(kid.ID)
	assert.False(t, ok)
	_, ok = s.Get("adopted-kid-id")
	assert.True(t, ok)
	select {
	case id := <-called:
		t.Fatalf("onChange called for non-active adoption: %s", id)
	default:
	}
}

func TestTransitionRollbackReportsPersistenceAndReloadFailures(t *testing.T) {
	breakRollback := func(t *testing.T, s *Store) {
		t.Helper()
		blocker := filepath.Join(t.TempDir(), "not-a-directory")
		require.NoError(t, os.WriteFile(blocker, []byte("blocked"), 0o600))
		s.path = filepath.Join(blocker, "profiles.json")
	}

	t.Run("set active", func(t *testing.T) {
		s := newStore(t)
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		s.SetOnChange(func(_, _ string) error {
			breakRollback(t, s)
			return fmt.Errorf("reload failed")
		})

		err = s.SetActive(kid.ID)
		require.ErrorContains(t, err, "rollback store")
		require.ErrorContains(t, err, "rollback reload")
	})

	t.Run("delete active", func(t *testing.T) {
		s := newStore(t)
		kid, err := s.Create("Kid")
		require.NoError(t, err)
		require.NoError(t, s.SetActive(kid.ID))
		s.SetOnChange(func(_, _ string) error {
			breakRollback(t, s)
			return fmt.Errorf("reload failed")
		})

		err = s.Delete(kid.ID)
		require.ErrorContains(t, err, "rollback store")
		require.ErrorContains(t, err, "rollback reload")
	})

	t.Run("adopt active ID", func(t *testing.T) {
		s := newStore(t)
		oldID := s.ActiveProfileID()
		s.SetOnChange(func(_, _ string) error {
			breakRollback(t, s)
			return fmt.Errorf("reload failed")
		})

		err := s.AdoptID(oldID, "remote-profile-id")
		require.ErrorContains(t, err, "rollback store")
		require.ErrorContains(t, err, "rollback reload")
	})
}

// TestAdoptActiveIDReloadFailureRollsBackCleanly covers the branch where the
// forward reload hook fails but both the store rollback and the reverse reload
// succeed (profiles.go line 641): the returned error names only the original
// reload failure, without the compound rollback suffix.
func TestAdoptActiveIDReloadFailureRollsBackCleanly(t *testing.T) {
	s := newStore(t)
	oldID := s.ActiveProfileID()

	var calls int
	s.SetOnChange(func(_, _ string) error {
		calls++
		if calls == 1 {
			return fmt.Errorf("forward reload failed")
		}
		return nil
	})

	err := s.AdoptID(oldID, "remote-profile-id")
	require.ErrorContains(t, err, "forward reload failed")
	assert.NotContains(t, err.Error(), "rollback store")
	assert.Equal(t, 2, calls, "reverse reload must run after the forward failure")

	// The store rolled back to the original ID and it is still active.
	assert.Equal(t, oldID, s.ActiveProfileID())
	_, ok := s.Get(oldID)
	assert.True(t, ok)
	_, ok = s.Get("remote-profile-id")
	assert.False(t, ok)
}

// TestAdoptIDRenameFailureRollsBack covers the os.Rename error branch
// (profiles.go lines 610-612): a read-only config directory prevents the
// per-profile data file from being renamed to the adopted ID, so AdoptID
// fails and leaves the source file in place.
func TestAdoptIDRenameFailureRollsBack(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("read-only directory denial does not apply to root")
	}
	s := newStore(t)
	oldID := s.ActiveProfileID()

	src, err := utils.ConfigPath(ProfileFileName("library", oldID))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(src, []byte("library"), 0o644))

	// Make the config directory read-only so os.Rename cannot move the file.
	configDir := filepath.Dir(src)
	require.NoError(t, os.Chmod(configDir, 0o555))
	t.Cleanup(func() { _ = os.Chmod(configDir, 0o755) })

	err = s.AdoptID(oldID, "remote-profile-id")
	require.Error(t, err)
	assert.Equal(t, oldID, s.ActiveProfileID())

	require.NoError(t, os.Chmod(configDir, 0o755))
	data, readErr := os.ReadFile(src)
	require.NoError(t, readErr)
	assert.Equal(t, "library", string(data), "source file must be left in place")
}

// TestAdoptIDStatSourceErrorRollsBack covers the branch where stat-ing a
// per-profile source file fails with an error other than "not exist"
// (profiles.go lines 590-594): a config directory without execute permission
// makes the source file unreachable, so AdoptID reports the stat failure.
func TestAdoptIDStatSourceErrorRollsBack(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("directory permission denial does not apply to root")
	}
	s := newStore(t)
	oldID := s.ActiveProfileID()

	src, err := utils.ConfigPath(ProfileFileName("library", oldID))
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(src, []byte("library"), 0o644))

	// Remove execute permission so the directory cannot be traversed to stat
	// the file inside it — os.Stat returns a permission error, not NotExist.
	configDir := filepath.Dir(src)
	require.NoError(t, os.Chmod(configDir, 0o644))
	t.Cleanup(func() { _ = os.Chmod(configDir, 0o755) })

	err = s.AdoptID(oldID, "remote-profile-id")
	require.Error(t, err)
	assert.NotErrorIs(t, err, os.ErrNotExist)
	assert.Equal(t, oldID, s.ActiveProfileID())
}

// TestNewReturnsWriteErrorOnFirstRun covers the first-run write-failure branch
// in New (profiles.go lines 162-164): the config directory exists but is
// read-only, so persisting the freshly created primary profile fails.
func TestNewReturnsWriteErrorOnFirstRun(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("read-only directory denial does not apply to root")
	}
	xdg := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", xdg)
	coveDir := filepath.Join(xdg, "cove")
	require.NoError(t, os.Mkdir(coveDir, 0o555))
	t.Cleanup(func() { _ = os.Chmod(coveDir, 0o755) })

	s, err := New(nil)
	assert.Nil(t, s)
	assert.Error(t, err)
}

// TestNewReturnsWriteErrorWhenNormalizationPersistFails covers the branch where
// loading an existing store requires normalization but persisting the repaired
// store fails (profiles.go lines 182-184).
func TestNewReturnsWriteErrorWhenNormalizationPersistFails(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("read-only directory denial does not apply to root")
	}
	xdg := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", xdg)
	// A store with two primaries needs normalization (the second is demoted),
	// which sets changed=true and triggers a persisting write.
	writeProfilesFixture(t, diskStore{
		Profiles: []Profile{
			{ID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Name: "One", IsPrimary: true},
			{ID: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Name: "Two", IsPrimary: true},
		},
		ActiveProfileID: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
	})

	coveDir := filepath.Join(xdg, "cove")
	require.NoError(t, os.Chmod(coveDir, 0o555))
	t.Cleanup(func() { _ = os.Chmod(coveDir, 0o755) })

	s, err := New(nil)
	assert.Nil(t, s)
	assert.Error(t, err)
}

// TestDeleteActiveFallsBackToFirstProfileWhenNoPrimary covers the defensive
// fallback in Delete (profiles.go lines 487-489): when the active profile is
// removed and no remaining profile is marked primary, the first remaining
// profile becomes active. Normalization normally guarantees a primary exists,
// so the store is seeded directly to reach this branch.
func TestDeleteActiveFallsBackToFirstProfileWhenNoPrimary(t *testing.T) {
	s := newStore(t)
	s.mu.Lock()
	s.disk.Profiles = []Profile{
		{ID: "11111111-1111-1111-1111-111111111111", Name: "First", IsPrimary: false},
		{ID: "22222222-2222-2222-2222-222222222222", Name: "Second", IsPrimary: false},
	}
	s.disk.ActiveProfileID = "22222222-2222-2222-2222-222222222222"
	s.mu.Unlock()

	require.NoError(t, s.Delete("22222222-2222-2222-2222-222222222222"))
	assert.Equal(t, "11111111-1111-1111-1111-111111111111", s.ActiveProfileID(),
		"deleting the active profile with no primary must fall back to the first remaining profile")
}
