package handlers

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMapReadarrPath_EbookAndAudiobookRoots(t *testing.T) {
	const (
		rr = "/media"
		mr = "/audiobooks"
	)
	cases := []struct {
		name string
		in   string
		want string
		ok   bool
	}{
		{"ebook", "/media/ebooks/Author/Book/file.epub", "/audiobooks/ebooks/Author/Book/file.epub", true},
		{"audiobook", "/media/audiobooks/Author/Book/file.m4b", "/audiobooks/audiobooks/Author/Book/file.m4b", true},
		{"root exact", "/media", "/audiobooks", true},
		{"trailing slash cleaned", "/media/ebooks/", "/audiobooks/ebooks", true},
		{"dot segments cleaned", "/media/ebooks/./Author/file.epub", "/audiobooks/ebooks/Author/file.epub", true},
		{"spaces", "/media/ebooks/Author Name/Book Title/file name.epub", "/audiobooks/ebooks/Author Name/Book Title/file name.epub", true},
		{"unicode", "/media/ebooks/José/Ñandú/libro.epub", "/audiobooks/ebooks/José/Ñandú/libro.epub", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := MapReadarrPath(rr, mr, tc.in)
			if ok != tc.ok {
				t.Fatalf("ok=%v want %v", ok, tc.ok)
			}
			if got != tc.want {
				t.Fatalf("got %q want %q", got, tc.want)
			}
		})
	}
}

func TestMapReadarrPath_RejectsTraversalAndOutsideRoot(t *testing.T) {
	const (
		rr = "/media"
		mr = "/audiobooks"
	)
	rejects := []string{
		"",
		"relative/path.epub",
		"/etc/passwd",
		"/media/../etc/passwd",
		"/media/ebooks/../../etc/passwd",
		"/media/ebooks/../../../etc/passwd",
		"/other/ebooks/file.epub",
		"/mediaevil/ebooks/file.epub", // prefix-sibling trap
		"/Media/ebooks/file.epub",     // case-sensitive outside
	}
	for _, in := range rejects {
		t.Run(in, func(t *testing.T) {
			got, ok := MapReadarrPath(rr, mr, in)
			if ok || got != "" {
				t.Fatalf("expected reject for %q, got %q ok=%v", in, got, ok)
			}
		})
	}
}

func TestMapReadarrPath_NoArbitrarySegmentStrip(t *testing.T) {
	// Old code stripped to 3rd segment for any absolute path. New mapping must not
	// accept paths outside READARR_MEDIA_ROOT just because they have enough segments.
	got, ok := MapReadarrPath("/media", "/audiobooks", "/var/data/books/Author/file.epub")
	if ok || got != "" {
		t.Fatalf("must not strip arbitrary segments: got %q ok=%v", got, ok)
	}
}

func TestMapReadarrPath_RequiresAbsoluteRoots(t *testing.T) {
	if _, ok := MapReadarrPath("media", "/audiobooks", "/media/ebooks/a"); ok {
		t.Fatal("relative readarr root must fail")
	}
	if _, ok := MapReadarrPath("/media", "audiobooks", "/media/ebooks/a"); ok {
		t.Fatal("relative media root must fail")
	}
}

func TestResolveFilePath_MissingFileStillMaps(t *testing.T) {
	// Missing files should still map (caller decides 404) as long as path is in-root.
	h := New(nil, nil, "/audiobooks", "/media")
	got, ok := h.resolveFilePath("/media/ebooks/Missing/Author/book.epub")
	if !ok {
		t.Fatal("expected ok for in-root missing path")
	}
	want := "/audiobooks/ebooks/Missing/Author/book.epub"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestResolveFilePath_SymlinkEscapeRejected(t *testing.T) {
	tmp := t.TempDir()
	mediaRoot := filepath.Join(tmp, "media")
	outside := filepath.Join(tmp, "outside")
	if err := os.MkdirAll(filepath.Join(mediaRoot, "ebooks"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(outside, 0o755); err != nil {
		t.Fatal(err)
	}
	secret := filepath.Join(outside, "secret.txt")
	if err := os.WriteFile(secret, []byte("nope"), 0o644); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(mediaRoot, "ebooks", "escape")
	if err := os.Symlink(secret, link); err != nil {
		t.Fatal(err)
	}

	// Handlers map /media → mediaRoot; feed a path that lands on the symlink.
	h := New(nil, nil, mediaRoot, "/media")
	// readarr path that maps to the symlink location
	_, ok := h.resolveFilePath("/media/ebooks/escape")
	if ok {
		t.Fatal("symlink escape must be rejected")
	}
}

func TestResolveFilePath_InRootSymlinkAllowed(t *testing.T) {
	tmp := t.TempDir()
	mediaRoot := filepath.Join(tmp, "media")
	targetDir := filepath.Join(mediaRoot, "ebooks", "real")
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(targetDir, "book.epub")
	if err := os.WriteFile(target, []byte("ok"), 0o644); err != nil {
		t.Fatal(err)
	}
	linkDir := filepath.Join(mediaRoot, "ebooks", "alias")
	if err := os.MkdirAll(filepath.Dir(linkDir), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(targetDir, linkDir); err != nil {
		t.Fatal(err)
	}

	h := New(nil, nil, mediaRoot, "/media")
	got, ok := h.resolveFilePath("/media/ebooks/alias/book.epub")
	if !ok {
		t.Fatal("in-root symlink should resolve")
	}
	real, err := filepath.EvalSymlinks(got)
	if err != nil {
		t.Fatal(err)
	}
	want, _ := filepath.EvalSymlinks(target)
	if real != want {
		t.Fatalf("got real %q want %q", real, want)
	}
}

func TestMapReadarrPath_PreservesAudiobookUnderSameRoot(t *testing.T) {
	// Regression: MEDIA_PATH must be the mount root, not the audiobooks leaf,
	// so both library classes map correctly under one rewrite.
	got, ok := MapReadarrPath("/media", "/audiobooks", "/media/audiobooks/A/B/c.m4b")
	if !ok || got != "/audiobooks/audiobooks/A/B/c.m4b" {
		t.Fatalf("got %q ok=%v", got, ok)
	}
	got, ok = MapReadarrPath("/media", "/audiobooks", "/media/ebooks/A/B/c.epub")
	if !ok || got != "/audiobooks/ebooks/A/B/c.epub" {
		t.Fatalf("got %q ok=%v", got, ok)
	}
}
