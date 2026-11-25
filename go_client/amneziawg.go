package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/unix"
)

// androidFDDevice адаптирует *os.File от Android VpnService.Builder.establish()
// к интерфейсу tun.Device без системных вызовов netlink/TUNGETIFF, которые
// запрещены SELinux на Android без root.
type androidFDDevice struct {
	file      *os.File
	mtu       int
	events    chan tun.Event
	closeOnce sync.Once
}

func newAndroidFDDevice(file *os.File, mtu int) (*androidFDDevice, error) {
	if file == nil {
		return nil, errors.New("nil tun file")
	}
	// Клонируем fd и выставляем O_NONBLOCK ДО os.NewFile(), чтобы Go
	// зарегистрировал дескриптор в epoll runtime poller вместо возврата EAGAIN
	// при Read/Write и корректно будил Read при dev.Close().
	rawFd := int(file.Fd())
	dupFd, err := unix.Dup(rawFd)
	if err != nil {
		return nil, fmt.Errorf("dup tun fd: %w", err)
	}
	if err := unix.SetNonblock(dupFd, true); err != nil {
		_ = unix.Close(dupFd)
		return nil, fmt.Errorf("set nonblock tun fd: %w", err)
	}
	nbFile := os.NewFile(uintptr(dupFd), "tun")
	if nbFile == nil {
		_ = unix.Close(dupFd)
		return nil, errors.New("os.NewFile returned nil for tun fd")
	}
	_ = file.Close()

	d := &androidFDDevice{
		file:   nbFile,
		mtu:    mtu,
		events: make(chan tun.Event, 4),
	}
	d.events <- tun.EventUp
	return d, nil
}

func (d *androidFDDevice) File() *os.File {
	return d.file
}

func (d *androidFDDevice) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 || len(sizes) == 0 {
		return 0, nil
	}
	n, err := d.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (d *androidFDDevice) Write(bufs [][]byte, offset int) (int, error) {
	written := 0
	for _, buf := range bufs {
		if offset >= len(buf) {
			continue
		}
		if _, err := d.file.Write(buf[offset:]); err != nil {
			return written, err
		}
		written++
	}
	return written, nil
}

func (d *androidFDDevice) MTU() (int, error) {
	return d.mtu, nil
}

func (d *androidFDDevice) Name() (string, error) {
	return "tun0", nil
}

func (d *androidFDDevice) Events() <-chan tun.Event {
	return d.events
}

func (d *androidFDDevice) Close() error {
	var err error
	d.closeOnce.Do(func() {
		close(d.events)
		err = d.file.Close()
	})
	return err
}

func (d *androidFDDevice) BatchSize() int {
	return 1
}

type awgIpcMetrics struct {
	rxBytes           int64
	txBytes           int64
	lastHandshakeSec  int64
}

func parseAwgIpcMetrics(ipcGetOut string) awgIpcMetrics {
	var m awgIpcMetrics
	scanner := bufio.NewScanner(strings.NewReader(ipcGetOut))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch k {
		case "rx_bytes":
			if val, err := strconv.ParseInt(v, 10, 64); err == nil {
				m.rxBytes += val
			}
		case "tx_bytes":
			if val, err := strconv.ParseInt(v, 10, 64); err == nil {
				m.txBytes += val
			}
		case "last_handshake_time_sec":
			if val, err := strconv.ParseInt(v, 10, 64); err == nil && val > m.lastHandshakeSec {
				m.lastHandshakeSec = val
			}
		}
	}
	return m
}

func pickPrimaryIPv4(addrs []netip.Addr) string {
	for _, a := range addrs {
		if a.Is4() {
			return a.String()
		}
	}
	if len(addrs) > 0 {
		return addrs[0].String()
	}
	return ""
}

func formatDNSCSV(addrs []netip.Addr) string {
	parts := make([]string, 0, len(addrs))
	for _, a := range addrs {
		parts = append(parts, a.String())
	}
	if len(parts) == 0 {
		return "1.1.1.1, 1.0.0.1"
	}
	return strings.Join(parts, ", ")
}

func runAmneziaWGMode(ctx context.Context, confPath string, tunFdSockPath string) {
	if confPath == "" {
		log.Fatal("[AWG] Не указан путь к конфигу -awg-conf")
	}
	if tunFdSockPath == "" {
		log.Fatal("[AWG] Не указан unix-сокет -tun-fd-sock")
	}

	rawBytes, err := os.ReadFile(confPath)
	if err != nil {
		log.Fatalf("[AWG] Ошибка чтения конфига: %v", err)
	}
	cfg, err := parseWgQuick(string(rawBytes))
	if err != nil {
		log.Fatalf("[AWG] Ошибка разбора конфига: %v", err)
	}

	ipStr := pickPrimaryIPv4(cfg.addresses)
	if ipStr == "" {
		log.Fatal("[AWG] В конфиге отсутствует IPv4 Address")
	}
	dnsCSV := formatDNSCSV(cfg.dns)

	hasObfs := cfg.jc != "" || cfg.s1 != "" || cfg.h1 != ""
	if hasObfs {
		log.Printf("[VPN] Запуск AmneziaWG (Jc=%s, S1=%s, S2=%s, endpoint=%s)", cfg.jc, cfg.s1, cfg.s2, cfg.endpoint)
	} else {
		log.Printf("[VPN] Запуск WireGuard (endpoint=%s)", cfg.endpoint)
	}

	// Сообщаем Android-клиенту параметры TUN через стандартный RAW Конфиг блок
	fmt.Println()
	fmt.Println("╔══════════════ RAW Конфиг ══════════════╗")
	fmt.Printf("║ %-40s ║\n", fmt.Sprintf("IP = %s", ipStr))
	fmt.Printf("║ %-40s ║\n", fmt.Sprintf("DNS = %s", dnsCSV))
	fmt.Printf("║ %-40s ║\n", fmt.Sprintf("MTU = %d", cfg.mtu))
	fmt.Println("╚════════════════════════════════════════╝")

	log.Println("[RAW] Ожидание TUN fd от Android...")
	var tunFile *os.File
	var fdErr error
	for attempt := 1; ; attempt++ {
		tunFile, fdErr = recvTunFD(tunFdSockPath)
		if fdErr == nil {
			break
		}
		rawDiagf("recvTunFD попытка #%d неудачна: %v", attempt, fdErr)
		select {
		case <-ctx.Done():
			return
		case <-time.After(200 * time.Millisecond):
		}
	}

	tunDev, err := newAndroidFDDevice(tunFile, cfg.mtu)
	if err != nil {
		_ = tunFile.Close()
		log.Fatalf("[AWG] Ошибка инициализации TUN: %v", err)
	}

	awgLogger := device.NewLogger(device.LogLevelError, "[AWG] ")
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), awgLogger)

	if err := dev.IpcSet(cfg.ipcRequest()); err != nil {
		dev.Close()
		log.Fatalf("[AWG] Ошибка применения параметров AmneziaWG: %v", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		log.Fatalf("[AWG] Ошибка подъёма AmneziaWG: %v", err)
	}

	log.Printf("[VPN] Интерфейс AmneziaWG поднят, ожидание рукопожатия с %s...", cfg.endpoint)

	var (
		handshakeConfirmed bool
		warnedTimeout      bool
		prevRx, prevTx     int64
		startedAt          = time.Now()
	)

	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("[AWG] Остановка интерфейса AmneziaWG...")
			dev.Close()
			return
		case <-dev.Wait():
			log.Println("[AWG] Устройство AmneziaWG закрыто")
			return
		case <-ticker.C:
			var sb strings.Builder
			if err := dev.IpcGetOperation(io.Writer(&sb)); err != nil {
				continue
			}
			m := parseAwgIpcMetrics(sb.String())

			if m.lastHandshakeSec > 0 && !handshakeConfirmed {
				handshakeConfirmed = true
				log.Printf("[VPN] Рукопожатие AmneziaWG подтверждено ✓")
			} else if !handshakeConfirmed && !warnedTimeout && time.Since(startedAt) > 12*time.Second {
				warnedTimeout = true
				log.Printf("[ОШИБКА] Нет ответа от сервера AmneziaWG (%s) — возможно оператор глушит UDP или активен Белый Список; попробуйте «Обход БС»", cfg.endpoint)
			}

			deltaRx := float64(m.rxBytes-prevRx) / (1024.0 * 1024.0)
			deltaTx := float64(m.txBytes-prevTx) / (1024.0 * 1024.0)
			if deltaRx < 0 {
				deltaRx = 0
			}
			if deltaTx < 0 {
				deltaTx = 0
			}
			prevRx = m.rxBytes
			prevTx = m.txBytes

			totalMb := float64(m.rxBytes+m.txBytes) / (1024.0 * 1024.0)
			rxMb := float64(m.rxBytes) / (1024.0 * 1024.0)
			txMb := float64(m.txBytes) / (1024.0 * 1024.0)

			stateLabel := 0
			if handshakeConfirmed {
				stateLabel = 1
			}
			fmt.Printf(
				"[СТАТИСТИКА] Активных: %d | Скорость: ↓ %.2f ↑ %.2f МБ/с | Трафик: %.2f МБ | ↓ %.2f МБ / ↑ %.2f МБ\n",
				stateLabel, deltaRx, deltaTx, totalMb, rxMb, txMb,
			)
		}
	}
}
