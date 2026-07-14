package handlers

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestZipSizeCalculation(t *testing.T) {
	// Create temp files to zip
	tmpDir := t.TempDir()

	testFiles := []struct {
		name string
		size int
	}{
		{"chapter01.m4b", 1024},
		{"chapter02.m4b", 2048},
		{"chapter03.m4b", 512},
	}

	for _, tf := range testFiles {
		path := filepath.Join(tmpDir, tf.name)
		data := make([]byte, tf.size)
		for i := range data {
			data[i] = byte(i % 256)
		}
		if err := os.WriteFile(path, data, 0644); err != nil {
			t.Fatalf("Failed to create test file: %v", err)
		}
	}

	// Calculate expected zip size using our formula (must match download.go)
	expectedSize := int64(0)
	for _, tf := range testFiles {
		nameLen := int64(len(tf.name))
		expectedSize += 30 + nameLen        // local file header
		expectedSize += int64(tf.size)      // file data (Store)
		expectedSize += 16                  // data descriptor
		expectedSize += 46 + nameLen        // central directory entry
	}
	expectedSize += 22 // end of central directory

	// Create actual zip with Store method and measure
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, tf := range testFiles {
		path := filepath.Join(tmpDir, tf.name)
		f, err := os.Open(path)
		if err != nil {
			t.Fatalf("Failed to open: %v", err)
		}

		header := &zip.FileHeader{
			Name:   tf.name,
			Method: zip.Store,
		}
		header.UncompressedSize64 = uint64(tf.size)

		w, err := zw.CreateHeader(header)
		if err != nil {
			f.Close()
			t.Fatalf("Failed to create header: %v", err)
		}

		io.Copy(w, f)
		f.Close()
	}
	zw.Close()

	actualSize := int64(buf.Len())

	if expectedSize != actualSize {
		t.Errorf("Zip size mismatch: calculated %d, actual %d (diff %d)",
			expectedSize, actualSize, actualSize-expectedSize)
	}

	// Verify the zip is valid
	reader, err := zip.NewReader(bytes.NewReader(buf.Bytes()), actualSize)
	if err != nil {
		t.Fatalf("Generated zip is invalid: %v", err)
	}

	if len(reader.File) != len(testFiles) {
		t.Errorf("Expected %d files in zip, got %d", len(testFiles), len(reader.File))
	}

	for i, zf := range reader.File {
		if zf.Name != testFiles[i].name {
			t.Errorf("File %d: expected name %q, got %q", i, testFiles[i].name, zf.Name)
		}
		if zf.UncompressedSize64 != uint64(testFiles[i].size) {
			t.Errorf("File %d: expected size %d, got %d", i, testFiles[i].size, zf.UncompressedSize64)
		}
	}
}
