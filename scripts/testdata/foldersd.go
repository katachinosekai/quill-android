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
	if len(os.Args) != 5 {
		fmt.Fprintln(os.Stderr, "usage: foldersd <certdir> <port> <key> <sessions-json>")
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
	fmt.Println(s.Port, fp)
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)
	<-ch
}
