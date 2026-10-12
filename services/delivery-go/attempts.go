package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"time"
)

type Attempt struct {
	Number    int        `json:"number"`
	Phase     string     `json:"phase"`
	Status    string     `json:"status"`
	Code      string     `json:"code"`
	StartedAt time.Time  `json:"startedAt"`
	EndedAt   *time.Time `json:"endedAt"`
}

func (w *Worker) publishAttempts(ctx context.Context, id string) error {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	rows, err := w.db.Query(ctx, "SELECT number,phase,status,code,started_at,ended_at FROM delivery_attempt WHERE task_id=$1 ORDER BY number", id)
	if err != nil {
		return err
	}
	defer rows.Close()
	attempts := []Attempt{}
	for rows.Next() {
		var a Attempt
		if err := rows.Scan(&a.Number, &a.Phase, &a.Status, &a.Code, &a.StartedAt, &a.EndedAt); err != nil {
			return err
		}
		attempts = append(attempts, a)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	body, err := json.Marshal(map[string]any{"attempts": attempts})
	if err != nil {
		return err
	}
	response, err := w.core.request(ctx, "POST", "/"+id+"/attempts", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 204 {
		return fmt.Errorf("attempt projection rejected: %d", response.StatusCode)
	}
	return nil
}
