package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"time"

	"github.com/katachinosekai/quill/internal/folders"
)

func main() {
	if len(os.Args) < 5 {
		fmt.Fprintln(os.Stderr, "usage: foldersd <certdir> <port> <key> <sessions-json> [events-file]")
		os.Exit(2)
	}
	cert, fp, err := folders.LoadOrCreateCert(os.Args[1])
	if err != nil {
		panic(err)
	}
	port, _ := strconv.Atoi(os.Args[2])
	s, err := folders.Listen(cert, port, os.Args[3])
	if err != nil {
		panic(err)
	}
	var sessions map[string]string
	if err := json.Unmarshal([]byte(os.Args[4]), &sessions); err != nil {
		panic(err)
	}
	s.Set(folders.Build("emu-test", []string{"Active", "Zomboid", "Inactive", "Archive", "Created by Claude"}, sessions, time.Now()).JSON())
	s.SetEvents(true)
	s.SetUsageSource(func() []byte {
		now := time.Now().UnixMilli()
		return []byte(fmt.Sprintf(`{"version":1,"usage":{"at":%d,"fiveHour":{"utilization":0.64,"resetsAt":%d},"weekly":{"utilization":0.14,"resetsAt":%d},"credits":"available","today":12345678,"week":98765432},"meta":{"session_C3":{"last":%d,"msgs":12,"status":"waiting","kmem":"Zomboid Tester","tokens":1500000,"context":45000,"contextMax":200000},"session_A1":{"last":%d,"msgs":3,"status":"running","tokens":2500,"context":1000,"contextMax":200000}}}`, now, now/1000+3600, now/1000+86400, now-120000, now))
	})
	if len(os.Args) > 5 {
		file := os.Args[5]
		go func() {
			sent := 0
			for range time.Tick(300 * time.Millisecond) {
				var evs []folders.Event
				b, err := os.ReadFile(file)
				if err != nil || json.Unmarshal(b, &evs) != nil {
					continue
				}
				for ; sent < len(evs); sent++ {
					s.Emit(evs[sent])
				}
			}
		}()
	}
	fmt.Println(s.Port, fp)
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)
	<-ch
}
