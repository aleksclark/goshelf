package handlers

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aleksclark/goshelf/readarr"
)

type fakeBook struct {
	media    string
	cacheDir string
	mu       sync.Mutex
	paths    []string
}

// newFakeBook serves book 1 from a fake Readarr whose files live under a temp
// media root mapped from /media.
func newFakeBook(t *testing.T, files map[string]int) (*Handlers, *fakeBook) {
	t.Helper()
	fb := &fakeBook{media: t.TempDir(), cacheDir: t.TempDir()}
	var paths []string
	for name, size := range files {
		local := filepath.Join(fb.media, "audiobooks", "Author", "Book", name)
		if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
			t.Fatal(err)
		}
		data := bytes.Repeat([]byte(name[:1]), size)
		if err := os.WriteFile(local, data, 0o644); err != nil {
			t.Fatal(err)
		}
		paths = append(paths, "/media/audiobooks/Author/Book/"+name)
	}
	fb.paths = paths

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/book/1":
			fmt.Fprint(w, `{"id":1,"title":"Test: Book","statistics":{"bookFileCount":1}}`)
		case "/api/v1/bookfile":
			fb.mu.Lock()
			var out []map[string]any
			for i, p := range fb.paths {
				out = append(out, map[string]any{"id": i + 1, "path": p, "bookId": 1})
			}
			fb.mu.Unlock()
			json.NewEncoder(w).Encode(out)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)

	h := &Handlers{
		client:           readarr.NewTestClient(srv.URL, srv.Client()),
		mediaPath:        fb.media,
		readarrMediaRoot: "/media",
		zipCacheDir:      fb.cacheDir,
	}
	return h, fb
}

func serve(method, target string, handler http.HandlerFunc, header map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, nil)
	req.SetPathValue("id", "1")
	for k, v := range header {
		req.Header.Set(k, v)
	}
	rr := httptest.NewRecorder()
	handler(rr, req)
	return rr
}

type downloadInfoResp struct {
	Status       string `json:"status"`
	TotalSize    int64  `json:"totalSize"`
	ETag         string `json:"etag"`
	Filename     string `json:"filename"`
	FileCount    int    `json:"fileCount"`
	SkippedFiles int    `json:"skippedFiles"`
}

func getDownloadInfo(t *testing.T, h *Handlers, query string) (*httptest.ResponseRecorder, downloadInfoResp) {
	t.Helper()
	rr := serve(http.MethodGet, "/api/books/1/download-info"+query, h.APIDownloadInfo, nil)
	var info downloadInfoResp
	if rr.Code == http.StatusOK || rr.Code == http.StatusAccepted {
		if err := json.Unmarshal(rr.Body.Bytes(), &info); err != nil {
			t.Fatalf("decode download-info: %v (%s)", err, rr.Body.String())
		}
	}
	return rr, info
}

func TestDownloadInfoMatchesServedZip(t *testing.T) {
	h, _ := newFakeBook(t, map[string]int{"ch01.mp3": 3000, "ch02.mp3": 5000, "cover.jpg": 100})

	rr, info := getDownloadInfo(t, h, "")
	if rr.Code != http.StatusOK {
		t.Fatalf("download-info status=%d body=%s", rr.Code, rr.Body.String())
	}
	if info.Status != "ready" || info.FileCount != 3 || info.Filename != "Test_ Book.zip" {
		t.Fatalf("unexpected info: %+v", info)
	}

	dl := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, nil)
	if dl.Code != http.StatusOK {
		t.Fatalf("download status=%d", dl.Code)
	}
	if got := int64(dl.Body.Len()); got != info.TotalSize {
		t.Fatalf("served %d bytes, download-info said %d", got, info.TotalSize)
	}
	if got := dl.Header().Get("ETag"); got != info.ETag {
		t.Fatalf("ETag header %q != download-info etag %q", got, info.ETag)
	}

	zr, err := zip.NewReader(bytes.NewReader(dl.Body.Bytes()), int64(dl.Body.Len()))
	if err != nil {
		t.Fatalf("invalid zip: %v", err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	if strings.Join(names, ",") != "ch01.mp3,ch02.mp3,cover.jpg" {
		t.Fatalf("entries not sorted deterministically: %v", names)
	}
}

func TestDownloadRangeResumeHonorsIfRange(t *testing.T) {
	h, _ := newFakeBook(t, map[string]int{"a.mp3": 4096, "b.mp3": 4096})
	_, info := getDownloadInfo(t, h, "")

	full := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, nil).Body.Bytes()

	part := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, map[string]string{
		"Range":    "bytes=1000-",
		"If-Range": info.ETag,
	})
	if part.Code != http.StatusPartialContent {
		t.Fatalf("matching If-Range: status=%d want 206", part.Code)
	}
	wantRange := fmt.Sprintf("bytes 1000-%d/%d", info.TotalSize-1, info.TotalSize)
	if got := part.Header().Get("Content-Range"); got != wantRange {
		t.Fatalf("Content-Range=%q want %q", got, wantRange)
	}
	if !bytes.Equal(part.Body.Bytes(), full[1000:]) {
		t.Fatal("resumed bytes differ from full download")
	}

	stale := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, map[string]string{
		"Range":    "bytes=1000-",
		"If-Range": `"stale-etag"`,
	})
	if stale.Code != http.StatusOK {
		t.Fatalf("stale If-Range: status=%d want 200 full body", stale.Code)
	}
	if int64(stale.Body.Len()) != info.TotalSize {
		t.Fatalf("stale If-Range body=%d want %d", stale.Body.Len(), info.TotalSize)
	}
}

func TestRebuiltZipIsByteIdentical(t *testing.T) {
	h, fb := newFakeBook(t, map[string]int{"01.mp3": 2000, "02.mp3": 2000, "03.mp3": 2000})
	_, first := getDownloadInfo(t, h, "")
	firstBytes := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, nil).Body.Bytes()

	// Simulate cache expiry and Readarr returning files in a different order.
	cleanupTempZips(fb.cacheDir, -time.Second)
	fb.mu.Lock()
	for i, j := 0, len(fb.paths)-1; i < j; i, j = i+1, j-1 {
		fb.paths[i], fb.paths[j] = fb.paths[j], fb.paths[i]
	}
	fb.mu.Unlock()

	_, second := getDownloadInfo(t, h, "")
	secondBytes := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, nil).Body.Bytes()
	if first.ETag != second.ETag {
		t.Fatalf("etag changed across rebuild: %s vs %s", first.ETag, second.ETag)
	}
	if !bytes.Equal(firstBytes, secondBytes) {
		t.Fatal("rebuilt zip with same etag is not byte-identical")
	}
}

func TestUnreadableFileExcludedFromSizeAndETag(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root can read chmod 000 files")
	}
	h, fb := newFakeBook(t, map[string]int{"good.mp3": 1000, "bad.mp3": 1000})
	bad := filepath.Join(fb.media, "audiobooks", "Author", "Book", "bad.mp3")
	if err := os.Chmod(bad, 0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(bad, 0o644) })

	rr, info := getDownloadInfo(t, h, "")
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if info.FileCount != 1 || info.SkippedFiles != 1 {
		t.Fatalf("unexpected counts: %+v", info)
	}
	dl := serve(http.MethodGet, "/books/1/download.zip", h.DownloadZipResumable, nil)
	if int64(dl.Body.Len()) != info.TotalSize {
		t.Fatalf("served %d bytes, advertised %d", dl.Body.Len(), info.TotalSize)
	}
}

func TestDownloadInfoAsyncReturnsPreparingThenReady(t *testing.T) {
	h, _ := newFakeBook(t, map[string]int{"a.mp3": 1000})

	release := make(chan struct{})
	var builds atomic.Int32
	origHook, origWait := beforeZipBuild, downloadInfoWait
	beforeZipBuild = func() {
		builds.Add(1)
		<-release
	}
	downloadInfoWait = 50 * time.Millisecond
	t.Cleanup(func() { beforeZipBuild, downloadInfoWait = origHook, origWait })

	for i := range 3 {
		rr, info := getDownloadInfo(t, h, "?async=1")
		if rr.Code != http.StatusAccepted || info.Status != "preparing" {
			t.Fatalf("poll %d: status=%d info=%+v", i, rr.Code, info)
		}
		if rr.Header().Get("Retry-After") == "" {
			t.Fatal("missing Retry-After")
		}
	}
	close(release)

	deadline := time.Now().Add(5 * time.Second)
	for {
		rr, info := getDownloadInfo(t, h, "?async=1")
		if rr.Code == http.StatusOK {
			if info.Status != "ready" || info.TotalSize == 0 {
				t.Fatalf("unexpected ready info: %+v", info)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("build never became ready")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if n := builds.Load(); n != 1 {
		t.Fatalf("polling started %d builds, want 1 shared build", n)
	}
}

func TestConcurrentPrepareSharesOneBuild(t *testing.T) {
	h, _ := newFakeBook(t, map[string]int{"a.mp3": 1000, "b.mp3": 1000})
	var builds atomic.Int32
	origHook := beforeZipBuild
	beforeZipBuild = func() {
		builds.Add(1)
		time.Sleep(20 * time.Millisecond)
	}
	t.Cleanup(func() { beforeZipBuild = origHook })

	var wg sync.WaitGroup
	etags := make([]string, 8)
	for i := range etags {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			art, err := h.prepareZip(context.Background(), 1)
			if err != nil {
				t.Error(err)
				return
			}
			etags[i] = art.ETag
		}(i)
	}
	wg.Wait()
	if n := builds.Load(); n != 1 {
		t.Fatalf("got %d builds, want 1", n)
	}
	for _, e := range etags {
		if e != etags[0] {
			t.Fatalf("inconsistent etags: %v", etags)
		}
	}
}

func TestPartialZipNeverServed(t *testing.T) {
	h, fb := newFakeBook(t, map[string]int{"a.mp3": 1000})
	files, _, err := h.statBookFiles(1)
	if err != nil {
		t.Fatal(err)
	}
	path := zipTempPath(fb.cacheDir, 1, computeCacheKey(1, files))
	// A leftover file at the final path without a manifest (e.g. from an older
	// server version or a crash mid-build) must be rebuilt, not served.
	if err := os.WriteFile(path, []byte("truncated"), 0o644); err != nil {
		t.Fatal(err)
	}
	art, err := h.prepareZip(context.Background(), 1)
	if err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(art.Path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	data, _ := io.ReadAll(f)
	if _, err := zip.NewReader(bytes.NewReader(data), int64(len(data))); err != nil {
		t.Fatalf("served invalid zip: %v", err)
	}
}

func TestCleanupTempZipsRemovesStaleArtifacts(t *testing.T) {
	dir := t.TempDir()
	old := time.Now().Add(-3 * time.Hour)
	write := func(name string, mtime time.Time) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
		os.Chtimes(p, mtime, mtime)
		return p
	}
	staleZip := write("goshelf_zip_1_aaaa.zip", old)
	staleManifest := write("goshelf_zip_1_aaaa.zip.json", time.Now())
	stalePartial := write("goshelf_zip_2_bbbb_123.partial", old)
	orphanManifest := write("goshelf_zip_3_cccc.zip.json", old)
	freshZip := write("goshelf_zip_4_dddd.zip", time.Now())
	freshManifest := write("goshelf_zip_4_dddd.zip.json", old)
	unrelated := write("other.zip", old)

	cleanupTempZips(dir, zipCacheMaxAge)

	for _, p := range []string{staleZip, staleManifest, stalePartial, orphanManifest} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Errorf("%s should be removed", filepath.Base(p))
		}
	}
	for _, p := range []string{freshZip, freshManifest, unrelated} {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("%s should be kept: %v", filepath.Base(p), err)
		}
	}
}
