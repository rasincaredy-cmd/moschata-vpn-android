package main

import (
	"log"
	"sync/atomic"
	"time"
)

type Stats struct {
	TotalBytesUp      atomic.Int64
	TotalBytesDown    atomic.Int64
	ActiveConnections atomic.Int32
}

func NewStats() *Stats {
	return &Stats{}
}

func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	var prevUp, prevDown int64
	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			active := s.ActiveConnections.Load()
			up := s.TotalBytesUp.Load()
			down := s.TotalBytesDown.Load()

			deltaDownMB := float64(down-prevDown) / (1024.0 * 1024.0)
			deltaUpMB := float64(up-prevUp) / (1024.0 * 1024.0)
			if deltaDownMB < 0 {
				deltaDownMB = 0
			}
			if deltaUpMB < 0 {
				deltaUpMB = 0
			}
			prevUp = up
			prevDown = down

			totalMB := float64(up+down) / (1024.0 * 1024.0)
			upMB := float64(up) / (1024.0 * 1024.0)
			downMB := float64(down) / (1024.0 * 1024.0)

			log.Printf("[СТАТИСТИКА] Активных: %d | Скорость: ↓ %.2f ↑ %.2f МБ/с | Трафик: %.2f МБ | ↓%.2f МБ / ↑%.2f МБ", active, deltaDownMB, deltaUpMB, totalMB, downMB, upMB)
		}
	}
}
