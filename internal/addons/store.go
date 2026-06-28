package addons

import (
	"encoding/json"
	"os"

	"github.com/Arcadyi/cove/internal/utils"
)

type addonStore struct {
	StremioAddons   []AddonEntry    `json:"stremioAddons"`
	OfficialEnabled map[string]bool `json:"officialEnabled,omitempty"`
}

func loadStore(path string) (addonStore, error) {
	var s addonStore
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return s, err
	}
	err = json.Unmarshal(data, &s)
	return s, err
}

func saveStore(path string, s addonStore) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(path, data, 0o644)
}
