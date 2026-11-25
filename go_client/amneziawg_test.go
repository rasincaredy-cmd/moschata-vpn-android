package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestParseWgQuick_AmneziaWGParamsAndIpc(t *testing.T) {
	priv := base64.StdEncoding.EncodeToString(make([]byte, 32))
	pub := base64.StdEncoding.EncodeToString(make([]byte, 32))

	conf := strings.Join([]string{
		"[Interface]",
		"PrivateKey = " + priv,
		"Address = 10.8.0.2/32",
		"DNS = 1.1.1.1, 1.0.0.1",
		"Jc = 6",
		"Jmin = 62",
		"Jmax = 101",
		"S1 = 62",
		"S2 = 63",
		"H1 = 1537899624",
		"H2 = 1106308852",
		"H3 = 1531159004",
		"H4 = 1596529676",
		"",
		"[Peer]",
		"PublicKey = " + pub,
		"AllowedIPs = 0.0.0.0/0, ::/0",
		"Endpoint = 48.102.173.96:585",
		"PersistentKeepalive = 25",
	}, "\n")

	cfg, err := parseWgQuick(conf)
	if err != nil {
		t.Fatalf("parseWgQuick failed: %v", err)
	}

	if got := pickPrimaryIPv4(cfg.addresses); got != "10.8.0.2" {
		t.Fatalf("expected IP 10.8.0.2, got %q", got)
	}
	if cfg.jc != "6" || cfg.s1 != "62" || cfg.h1 != "1537899624" {
		t.Fatalf("AmneziaWG fields not parsed: jc=%q s1=%q h1=%q", cfg.jc, cfg.s1, cfg.h1)
	}

	ipc := cfg.ipcRequest()
	for _, want := range []string{
		"jc=6\n",
		"jmin=62\n",
		"jmax=101\n",
		"s1=62\n",
		"s2=63\n",
		"h1=1537899624\n",
		"h4=1596529676\n",
		"endpoint=48.102.173.96:585\n",
		"persistent_keepalive_interval=25\n",
	} {
		if !strings.Contains(ipc, want) {
			t.Fatalf("ipcRequest missing %q\ngot:\n%s", want, ipc)
		}
	}
}

func TestParseAwgIpcMetrics(t *testing.T) {
	sample := "private_key=00\npublic_key=11\nrx_bytes=2097152\ntx_bytes=1048576\nlast_handshake_time_sec=1700000000\n"
	m := parseAwgIpcMetrics(sample)
	if m.rxBytes != 2097152 || m.txBytes != 1048576 || m.lastHandshakeSec != 1700000000 {
		t.Fatalf("unexpected metrics: %+v", m)
	}
}
