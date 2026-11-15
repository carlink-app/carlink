// spike-server: benchmark server for checking what the Tesla browser can do.
//
// Serves the benchmark page over HTTP (and HTTPS if given a cert), streams
// H.264/HEVC test clips over WebSocket and WebTransport, echoes pings, and
// stores reports sent from the page.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/coder/websocket"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/quic-go/webtransport-go"
	"golang.org/x/sys/unix"
)

//go:embed web
var webFS embed.FS

// seedHex is the 32-byte private key seed for the WebTransport certificate.
// Set at build time by build.sh (-ldflags "-X main.seedHex=...").
var seedHex = "00000000000000000000000000000000000000000000000000000000carl1nk0"

const frameInterval = time.Second / 60

// DSCP EF (46), written as the full TOS byte. Wi-Fi WMM maps it to the voice
// access category: shortest contention window, highest priority on air.
const tosPriority = 0xB8

type clip struct {
	raw []byte
	aus [][]byte
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "hashes" {
		printHashes()
		return
	}

	httpAddr := flag.String("http", ":8080", "plain HTTP listen address")
	httpsAddr := flag.String("https", ":8443", "HTTPS listen address (only used with -cert/-key)")
	certFile := flag.String("cert", "", "TLS certificate (PEM, full chain) for HTTPS, e.g. from Let's Encrypt")
	keyFile := flag.String("key", "", "TLS private key (PEM) for HTTPS")
	wtPort := flag.Int("wt", 4433, "WebTransport UDP port; port+1 is the same server with DSCP EF marking")
	wtBind := flag.String("wt-bind", "", "IP address for the WebTransport listeners (default: all)")
	clipDir := flag.String("clips", "", "directory with clip.h264 / clip.hevc (default: next to the binary)")
	reportDir := flag.String("reports", "", "directory for reports (default: reports/ next to the binary)")
	flag.Parse()

	// quic-go sets the TOS byte per packet for ECN, which would overwrite the
	// DSCP set on the socket. Wi-Fi gains nothing from ECN here; turn it off.
	os.Setenv("QUIC_GO_DISABLE_ECN", "true")

	exeDir := "."
	if exe, err := os.Executable(); err == nil {
		exeDir = filepath.Dir(exe)
	}
	if *clipDir == "" {
		*clipDir = exeDir
	}
	if *reportDir == "" {
		*reportDir = filepath.Join(exeDir, "reports")
	}

	srv := &server{clips: map[string]*clip{}, reportDir: *reportDir, wtPort: *wtPort}
	for _, codec := range []string{"h264", "hevc"} {
		raw, err := os.ReadFile(filepath.Join(*clipDir, "clip."+codec))
		if err != nil {
			log.Printf("clip %s: %v (run gen-clip.sh)", codec, err)
			continue
		}
		aus := splitAccessUnits(raw, codec)
		if len(aus) == 0 {
			log.Printf("clip %s has no access units (needs AUD NAL units)", codec)
			continue
		}
		srv.clips[codec] = &clip{raw: raw, aus: aus}
		log.Printf("clip %s: %d frames, %.1f MB", codec, len(aus), float64(len(raw))/1e6)
	}
	if srv.clips["h264"] == nil {
		log.Fatal("clip.h264 is required")
	}

	mux := http.NewServeMux()
	web, _ := fs.Sub(webFS, "web")
	mux.Handle("/", http.FileServer(http.FS(web)))
	mux.HandleFunc("/clip.h264", srv.handleClip("h264"))
	mux.HandleFunc("/clip.hevc", srv.handleClip("hevc"))
	mux.HandleFunc("/info", srv.handleInfo)
	mux.HandleFunc("/report", srv.handleReport)
	mux.HandleFunc("/ws", srv.handleWS)
	handler := withCORS(mux)

	srv.httpPort = portOf(*httpAddr)
	go func() {
		log.Printf("HTTP on %s", *httpAddr)
		log.Fatal(newHTTPServer(*httpAddr, handler).ListenAndServe())
	}()
	if *certFile != "" && *keyFile != "" {
		srv.httpsPort = portOf(*httpsAddr)
		go func() {
			log.Printf("HTTPS on %s", *httpsAddr)
			log.Fatal(newHTTPServer(*httpsAddr, handler).ListenAndServeTLS(*certFile, *keyFile))
		}()
	}

	wtCert, notBefore := makeWTCert(time.Now())
	printURLs(srv)
	log.Printf("WebTransport cert valid from %s, sha-256 %s", notBefore.Format("2006-01-02"), certHash(wtCert))
	go func() { log.Fatal(srv.serveWT(*wtBind, *wtPort+1, wtCert, tosPriority)) }()
	log.Fatal(srv.serveWT(*wtBind, *wtPort, wtCert, 0))
}

type server struct {
	clips     map[string]*clip
	reportDir string
	httpPort  string
	httpsPort string
	wtPort    int
}

// ---- WebTransport certificate ----
//
// Chromium accepts a self-signed certificate over WebTransport when the page
// passes its SHA-256 hash and the certificate is ECDSA P-256 valid for at most
// 14 days. The certificate here is deterministic: fixed key, validity window
// aligned to Monday 00:00 UTC, 13 days long. So hashes for future weeks can be
// computed in advance and shipped in web/config.json; nothing has to be typed
// in the car.

func wtKey() *ecdsa.PrivateKey {
	seed, err := hex.DecodeString(seedHex)
	if err != nil || len(seed) != 32 {
		log.Fatalf("bad seed: must be 64 hex chars")
	}
	k, err := ecdsa.ParseRawPrivateKey(elliptic.P256(), seed)
	if err != nil {
		log.Fatalf("seed is not a valid P-256 key: %v", err)
	}
	return k
}

func weekStart(t time.Time) time.Time {
	t = t.UTC()
	d := time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
	return d.AddDate(0, 0, -int((d.Weekday()+6)%7))
}

func makeWTCert(now time.Time) (tls.Certificate, time.Time) {
	key := wtKey()
	from := weekStart(now)
	tmpl := x509.Certificate{
		SerialNumber:          big.NewInt(from.Unix()),
		Subject:               pkix.Name{CommonName: "carlink-spike"},
		NotBefore:             from,
		NotAfter:              from.Add(13 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(nil, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		log.Fatalf("create cert: %v", err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, from
}

func certHash(c tls.Certificate) string {
	h := sha256.Sum256(c.Certificate[0])
	return hex.EncodeToString(h[:])
}

// printHashes writes web/config.json content: hashes for last week, this week
// and the next 51. Usage: spike-server hashes > web/config.json
func printHashes() {
	var hashes []string
	now := time.Now()
	for i := -1; i < 52; i++ {
		c, _ := makeWTCert(now.AddDate(0, 0, 7*i))
		hashes = append(hashes, certHash(c))
	}
	out, _ := json.MarshalIndent(map[string]any{
		"generated":  now.UTC().Format(time.RFC3339),
		"wtPort":     4433,
		"certHashes": hashes,
	}, "", "  ")
	fmt.Println(string(out))
}

// ---- H.264 / HEVC ----

func nalType(b byte, codec string) int {
	if codec == "hevc" {
		return int(b>>1) & 0x3f
	}
	return int(b) & 0x1f
}

// splitAccessUnits splits an Annex B stream into access units at AUD NAL units
// (H.264 type 9, HEVC type 35).
func splitAccessUnits(b []byte, codec string) [][]byte {
	aud := 9
	if codec == "hevc" {
		aud = 35
	}
	var aus [][]byte
	start := -1
	for i := 0; i+3 < len(b); i++ {
		if b[i] != 0 || b[i+1] != 0 || b[i+2] != 1 {
			continue
		}
		if nalType(b[i+3], codec) == aud {
			pos := i
			if pos > 0 && b[pos-1] == 0 {
				pos--
			}
			if start >= 0 {
				aus = append(aus, b[start:pos])
			}
			start = pos
		}
		i += 2
	}
	if start >= 0 {
		aus = append(aus, b[start:])
	}
	return aus
}

func isKeyFrame(au []byte, codec string) bool {
	for i := 0; i+3 < len(au); i++ {
		if au[i] == 0 && au[i+1] == 0 && au[i+2] == 1 {
			t := nalType(au[i+3], codec)
			if codec == "hevc" && t >= 16 && t <= 21 || codec != "hevc" && t == 5 {
				return true
			}
		}
	}
	return false
}

// Frame header: u32 seq | f64 server send time (ms since stream start) | u8 flags (1 = key).
const headerLen = 13

// streamVideo sends one access unit every 1/60 s. It never skips frames: if
// the link cannot keep up, latency grows and the page shows it.
func (s *server) streamVideo(ctx context.Context, codec string, send func([]byte) error) {
	c := s.clips[codec]
	if c == nil {
		log.Printf("no %s clip", codec)
		return
	}
	start := time.Now()
	for seq := 0; ; seq++ {
		due := start.Add(time.Duration(seq) * frameInterval)
		if d := time.Until(due); d > 0 {
			select {
			case <-ctx.Done():
				return
			case <-time.After(d):
			}
		} else if ctx.Err() != nil {
			return
		}
		au := c.aus[seq%len(c.aus)]
		buf := make([]byte, headerLen+len(au))
		binary.BigEndian.PutUint32(buf[0:], uint32(seq))
		binary.BigEndian.PutUint64(buf[4:], math.Float64bits(float64(time.Since(start).Microseconds())/1000))
		if isKeyFrame(au, codec) {
			buf[12] = 1
		}
		copy(buf[headerLen:], au)
		if err := send(buf); err != nil {
			return
		}
	}
}

// ---- socket priority ----

func setTOS(c syscall.RawConn, tos int) {
	_ = c.Control(func(fd uintptr) {
		_ = unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_TOS, tos)
		_ = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_TCLASS, tos)
	})
}

type connKey struct{}

// newHTTPServer keeps each TCP connection in the request context so the
// WebSocket handler can set DSCP on it (?dscp=1).
func newHTTPServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:    addr,
		Handler: h,
		ConnContext: func(ctx context.Context, c net.Conn) context.Context {
			return context.WithValue(ctx, connKey{}, c)
		},
	}
}

// ---- WebTransport ----

func (s *server) serveWT(bind string, port int, cert tls.Certificate, tos int) error {
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(bind), Port: port})
	if err != nil {
		return err
	}
	if tos != 0 {
		if rc, err := udp.SyscallConn(); err == nil {
			setTOS(rc, tos)
		}
	}
	h3 := &http3.Server{
		TLSConfig: http3.ConfigureTLSConfig(&tls.Config{Certificates: []tls.Certificate{cert}}),
		QUICConfig: &quic.Config{
			EnableDatagrams:                  true,
			EnableStreamResetPartialDelivery: true,
			KeepAlivePeriod:                  5 * time.Second,
		},
	}
	webtransport.ConfigureHTTP3Server(h3)
	wt := &webtransport.Server{H3: h3, CheckOrigin: func(*http.Request) bool { return true }}
	mux := http.NewServeMux()
	h3.Handler = mux
	mux.HandleFunc("/wt", func(w http.ResponseWriter, r *http.Request) {
		sess, err := wt.Upgrade(w, r)
		if err != nil {
			log.Printf("wt upgrade: %v", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		log.Printf("wt session on :%d from %s (origin %q)", port, sess.RemoteAddr(), r.Header.Get("Origin"))
		s.handleWTSession(sess)
	})
	log.Printf("WebTransport on UDP :%d (tos 0x%02x)", port, tos)
	return wt.Serve(udp)
}

func (s *server) handleWTSession(sess *webtransport.Session) {
	ctx := sess.Context()
	// Datagram echo, used for RTT.
	go func() {
		for {
			d, err := sess.ReceiveDatagram(ctx)
			if err != nil {
				return
			}
			_ = sess.SendDatagram(d)
		}
	}()
	// Commands arrive on bidirectional streams: "video <codec>" or "report <json>".
	for {
		str, err := sess.AcceptStream(ctx)
		if err != nil {
			log.Printf("wt session closed: %v", err)
			return
		}
		go func() {
			defer str.Close()
			data, err := io.ReadAll(io.LimitReader(str, 1<<20))
			if err != nil {
				return
			}
			cmd, rest, _ := strings.Cut(string(data), " ")
			switch strings.TrimSpace(cmd) {
			case "video":
				codec := strings.TrimSpace(rest)
				if codec == "" {
					codec = "h264"
				}
				log.Printf("wt video %s start", codec)
				s.streamVideo(ctx, codec, func(frame []byte) error {
					// One unidirectional stream per frame: a lost packet only
					// delays its own frame, not the ones after it.
					us, err := sess.OpenUniStream()
					if err != nil {
						return err
					}
					if _, err := us.Write(frame); err != nil {
						return err
					}
					return us.Close()
				})
				log.Printf("wt video stop")
			case "report":
				s.saveReport([]byte(rest), "wt")
				_, _ = str.Write([]byte("ok"))
			}
		}()
	}
}

// ---- HTTP handlers ----

func withCORS(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		w.Header().Set("Cache-Control", "no-store")
		if r.Method == http.MethodOptions {
			return
		}
		h.ServeHTTP(w, r)
	})
}

func (s *server) handleClip(codec string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c := s.clips[codec]
		if c == nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(c.raw)
	}
}

func (s *server) handleInfo(w http.ResponseWriter, r *http.Request) {
	c, _ := makeWTCert(time.Now())
	codecs := []string{}
	for k := range s.clips {
		codecs = append(codecs, k)
	}
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ips":        localIPs(),
		"httpPort":   s.httpPort,
		"httpsPort":  s.httpsPort,
		"wtPort":     s.wtPort,
		"codecs":     codecs,
		"certHashes": []string{certHash(c)},
	})
}

func (s *server) handleReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.saveReport(body, "http")
	_, _ = w.Write([]byte("ok"))
}

func (s *server) saveReport(body []byte, via string) {
	if err := os.MkdirAll(s.reportDir, 0o755); err != nil {
		log.Printf("report dir: %v", err)
		return
	}
	name := filepath.Join(s.reportDir, time.Now().Format("20060102-150405")+"-"+via+".json")
	if err := os.WriteFile(name, body, 0o644); err != nil {
		log.Printf("save report: %v", err)
		return
	}
	log.Printf("report saved: %s", name)
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if q.Get("dscp") == "1" {
		if c, ok := r.Context().Value(connKey{}).(net.Conn); ok {
			if sc, ok := c.(syscall.Conn); ok {
				if rc, err := sc.SyscallConn(); err == nil {
					setTOS(rc, tosPriority)
				}
			}
		}
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(1 << 20)
	ctx := r.Context()
	log.Printf("ws mode=%s codec=%s dscp=%s from %s", q.Get("mode"), q.Get("codec"), q.Get("dscp"), r.RemoteAddr)

	// Every connection echoes binary messages (RTT pings). With mode=video
	// it also streams a clip.
	if q.Get("mode") == "video" {
		codec := q.Get("codec")
		if codec == "" {
			codec = "h264"
		}
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		go s.streamVideo(ctx, codec, func(frame []byte) error {
			return c.Write(ctx, websocket.MessageBinary, frame)
		})
	}
	for {
		typ, msg, err := c.Read(ctx)
		if err != nil {
			return
		}
		if err := c.Write(ctx, typ, msg); err != nil {
			return
		}
	}
}

// ---- misc ----

func portOf(addr string) string {
	_, p, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return p
}

func localIPs() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok || ipn.IP.IsLoopback() || ipn.IP.To4() == nil {
			continue
		}
		ips = append(ips, ipn.IP.String())
	}
	return ips
}

func printURLs(s *server) {
	ips := localIPs()
	if len(ips) == 0 {
		log.Printf("could not list interfaces; use the hotspot IP from Android settings")
		return
	}
	for _, ip := range ips {
		log.Printf("open http://%s:%s/  (WebTransport %s:%s, priority %s:%s)", ip, s.httpPort,
			ip, strconv.Itoa(s.wtPort), ip, strconv.Itoa(s.wtPort+1))
	}
}
