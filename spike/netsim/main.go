// netsim: userspace proxy that makes a local link behave like phone-hotspot
// Wi-Fi. No root needed (no tc/netem).
//
// Model, per profile:
//   - one shared medium: Wi-Fi is half duplex, so both directions queue for
//     the same airtime (rate + per-packet overhead for contention/ACK)
//   - base one-way delay plus exponential jitter
//   - "busy" periods (interference, retries, neighbour traffic) that hold
//     every packet until they end
//   - residual UDP loss (after MAC retries); TCP gets delay instead of loss
//   - optional client power-save: if nothing reached the client for 100 ms
//     it is asleep, and the next downlink packet waits for the next beacon
//
// Ordering is kept per direction, like a real Wi-Fi queue. Numbers in the
// profiles are educated guesses; calibrate them against the car reports.
//
// Usage:
//   netsim -profile wifi24 -listen 127.0.0.2 \
//          -tcp 8080=127.0.0.1:28080 -udp 4433=127.0.0.1:24433 -udp 4434=127.0.0.1:24434
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"math/rand/v2"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type profile struct {
	rateMbps     float64       // usable airtime rate
	overhead     time.Duration // per-packet contention + ACK
	base         time.Duration // one-way base delay
	jitterMean   time.Duration // mean of exponential extra delay
	busyProb     float64       // chance per 100 ms that a busy period starts
	busyMin      time.Duration
	busyMax      time.Duration
	loss         float64 // residual UDP loss
	powerSave    bool
	beaconPeriod time.Duration
}

var profiles = map[string]profile{
	"none": {},
	"wifi5": {rateMbps: 250, overhead: 60 * time.Microsecond, base: 700 * time.Microsecond,
		jitterMean: 400 * time.Microsecond, busyProb: 0.005, busyMin: 5 * time.Millisecond, busyMax: 25 * time.Millisecond,
		loss: 0.0002},
	"wifi24": {rateMbps: 45, overhead: 150 * time.Microsecond, base: 1500 * time.Microsecond,
		jitterMean: 1500 * time.Microsecond, busyProb: 0.03, busyMin: 15 * time.Millisecond, busyMax: 70 * time.Millisecond,
		loss: 0.002},
	"wifi24-busy": {rateMbps: 22, overhead: 300 * time.Microsecond, base: 2500 * time.Microsecond,
		jitterMean: 4 * time.Millisecond, busyProb: 0.10, busyMin: 30 * time.Millisecond, busyMax: 150 * time.Millisecond,
		loss: 0.01},
}

// link is the shared Wi-Fi medium.
type link struct {
	p         profile
	mu        sync.Mutex
	airFree   time.Time
	busyUntil time.Time
	lastDown  time.Time // last time a packet was delivered to the client
	dropped   atomic.Int64
	packets   atomic.Int64
}

func newLink(p profile) *link {
	l := &link{p: p}
	if p.busyProb > 0 {
		go func() {
			for range time.Tick(100 * time.Millisecond) {
				if rand.Float64() < p.busyProb {
					d := p.busyMin + time.Duration(rand.Int64N(int64(p.busyMax-p.busyMin)+1))
					l.mu.Lock()
					if t := time.Now().Add(d); t.After(l.busyUntil) {
						l.busyUntil = t
					}
					l.mu.Unlock()
				}
			}
		}()
	}
	return l
}

// schedule returns when a packet of n bytes sent now arrives, or ok=false if
// it is lost. down = towards the browser (phone -> car).
func (l *link) schedule(n int, down, lossy bool) (time.Time, bool) {
	l.packets.Add(1)
	now := time.Now()
	if l.p.rateMbps == 0 {
		return now, true
	}
	// Residual loss hits TCP too: the segment arrives late after a
	// retransmit (about two RTTs) instead of disappearing.
	lost := l.p.loss > 0 && rand.Float64() < l.p.loss
	if lost && lossy {
		l.dropped.Add(1)
		return time.Time{}, false
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	start := now
	if l.busyUntil.After(start) {
		start = l.busyUntil
	}
	if l.airFree.After(start) {
		start = l.airFree
	}
	if down && l.p.powerSave && start.Sub(l.lastDown) > 100*time.Millisecond {
		// Client is dozing: wait for the next beacon (TIM), then it polls.
		bp := l.p.beaconPeriod
		start = start.Truncate(bp).Add(bp)
	}
	air := l.p.overhead + time.Duration(float64(n*8)/l.p.rateMbps*1000)*time.Nanosecond
	l.airFree = start.Add(air)
	at := l.airFree.Add(l.p.base + time.Duration(rand.ExpFloat64()*float64(l.p.jitterMean)))
	if lost {
		at = at.Add(4*l.p.base + 10*time.Millisecond)
	}
	if down {
		l.lastDown = at
	}
	return at, true
}

// pipe delivers packets in order at their scheduled times.
type pipe struct {
	ch chan item
}

type item struct {
	at   time.Time
	data []byte
}

func newPipe(deliver func([]byte)) *pipe {
	p := &pipe{ch: make(chan item, 4096)}
	go func() {
		var last time.Time
		for it := range p.ch {
			at := it.at
			if at.Before(last) {
				at = last
			}
			last = at
			if d := time.Until(at); d > 0 {
				time.Sleep(d)
			}
			deliver(it.data)
		}
	}()
	return p
}

func (p *pipe) send(l *link, data []byte, down, lossy bool) {
	at, ok := l.schedule(len(data)+48, down, lossy)
	if !ok {
		return
	}
	if !lossy {
		p.ch <- item{at, data} // TCP: block (backpressure), never drop bytes
		return
	}
	select {
	case p.ch <- item{at, data}:
	default: // queue overflow: drop, like a full AP queue
		l.dropped.Add(1)
	}
}

// ---- UDP ----

func proxyUDP(l *link, listen, target string) {
	laddr, err := net.ResolveUDPAddr("udp", listen)
	if err != nil {
		log.Fatal(err)
	}
	taddr, err := net.ResolveUDPAddr("udp", target)
	if err != nil {
		log.Fatal(err)
	}
	ln, err := net.ListenUDP("udp", laddr)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("udp %s -> %s", listen, target)
	type session struct {
		up   *net.UDPConn
		toUp *pipe
		seen atomic.Int64
	}
	var mu sync.Mutex
	sessions := map[string]*session{}
	buf := make([]byte, 65536)
	for {
		n, client, err := ln.ReadFromUDP(buf)
		if err != nil {
			log.Fatal(err)
		}
		key := client.String()
		mu.Lock()
		s := sessions[key]
		if s == nil {
			up, err := net.DialUDP("udp", nil, taddr)
			if err != nil {
				mu.Unlock()
				log.Printf("dial %s: %v", target, err)
				continue
			}
			s = &session{up: up}
			s.toUp = newPipe(func(b []byte) { _, _ = up.Write(b) })
			toClient := newPipe(func(b []byte) { _, _ = ln.WriteToUDP(b, client) })
			sessions[key] = s
			go func() {
				b := make([]byte, 65536)
				for {
					_ = up.SetReadDeadline(time.Now().Add(2 * time.Minute))
					m, err := up.Read(b)
					if err != nil {
						mu.Lock()
						delete(sessions, key)
						mu.Unlock()
						up.Close()
						return
					}
					toClient.send(l, append([]byte(nil), b[:m]...), true, true)
				}
			}()
		}
		mu.Unlock()
		s.toUp.send(l, append([]byte(nil), buf[:n]...), false, true)
	}
}

// ---- TCP ----

func proxyTCP(l *link, listen, target string) {
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("tcp %s -> %s", listen, target)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go func() {
			u, err := net.Dial("tcp", target)
			if err != nil {
				c.Close()
				return
			}
			done := make(chan struct{}, 2)
			relay := func(dst, src net.Conn, down bool) {
				p := newPipe(func(b []byte) {
					if b == nil {
						if tc, ok := dst.(*net.TCPConn); ok {
							_ = tc.CloseWrite()
						}
						return
					}
					_, _ = dst.Write(b)
				})
				b := make([]byte, 1448) // one Wi-Fi frame worth per chunk
				for {
					n, err := src.Read(b)
					if n > 0 {
						p.send(l, append([]byte(nil), b[:n]...), down, false)
					}
					if err != nil {
						if err != io.EOF {
							c.Close()
							u.Close()
						}
						p.ch <- item{time.Now(), nil}
						done <- struct{}{}
						return
					}
				}
			}
			go relay(u, c, false)
			go relay(c, u, true)
			<-done
			<-done
			time.Sleep(time.Second)
			c.Close()
			u.Close()
		}()
	}
}

type mappings []string

func (m *mappings) String() string     { return strings.Join(*m, ",") }
func (m *mappings) Set(v string) error { *m = append(*m, v); return nil }

func main() {
	name := flag.String("profile", "wifi24", "none | wifi5 | wifi24 | wifi24-busy")
	listen := flag.String("listen", "127.0.0.2", "address to listen on")
	powerSave := flag.Bool("psm", false, "emulate client Wi-Fi power-save (beacon wake-up)")
	var tcp, udp mappings
	flag.Var(&tcp, "tcp", "port=host:port, repeatable")
	flag.Var(&udp, "udp", "port=host:port, repeatable")
	flag.Parse()

	p, ok := profiles[*name]
	if !ok {
		log.Fatalf("unknown profile %q", *name)
	}
	if *powerSave {
		p.powerSave = true
		p.beaconPeriod = 102400 * time.Microsecond
		if p.rateMbps == 0 {
			p.rateMbps = 1e6
		}
	}
	l := newLink(p)
	log.Printf("profile %s %+v", *name, p)

	for _, m := range tcp {
		port, target, _ := strings.Cut(m, "=")
		go proxyTCP(l, fmt.Sprintf("%s:%s", *listen, port), target)
	}
	for _, m := range udp {
		port, target, _ := strings.Cut(m, "=")
		go proxyUDP(l, fmt.Sprintf("%s:%s", *listen, port), target)
	}
	for range time.Tick(10 * time.Second) {
		log.Printf("packets %d dropped %d", l.packets.Load(), l.dropped.Load())
	}
}
