package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

var scratchPattern = regexp.MustCompile(`^report-delivery-[0-9]+\.pdf$`)

// Only delivery-owned, regular files older than the maximum operation lifetime
// plus a conservative 24-hour grace period are eligible. Never follow symlinks.
func cleanScratch(root string, now time.Time) error {
	if err := os.MkdirAll(root, 0700); err != nil {
		return err
	}
	info, err := os.Lstat(root)
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode().Perm() != 0700 {
		return fmt.Errorf("scratch directory must be private (0700) and not a symlink")
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !scratchPattern.MatchString(entry.Name()) || entry.Type()&os.ModeSymlink != 0 {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Mode().IsRegular() && now.Sub(info.ModTime()) > 24*time.Hour {
			if err := os.Remove(filepath.Join(root, entry.Name())); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
	}
	return nil
}
func scratchRoot() string {
	if root := os.Getenv("DELIVERY_SCRATCH_ROOT"); root != "" {
		return root
	}
	return filepath.Join(os.TempDir(), "report-engine-delivery")
}
