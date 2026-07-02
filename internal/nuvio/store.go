package nuvio

import (
	"encoding/json"
	"os"

	"github.com/coveninja/cove/internal/utils"
)

type nuvioStore struct {
	Repos []Repo `json:"repos"`
}

func loadStore(path string) (nuvioStore, error) {
	var s nuvioStore
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

func saveStore(path string, s nuvioStore) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(path, data, 0o644)
}
