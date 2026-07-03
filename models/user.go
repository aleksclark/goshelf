package models

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type User struct {
	ID           int64
	Username     string
	PasswordHash string
	IsAdmin      bool
	CreatedAt    time.Time
}

func UserCount(db *sql.DB) (int, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM users").Scan(&count)
	return count, err
}

func CreateUser(db *sql.DB, username, password string, isAdmin bool) (*User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("hash password: %w", err)
	}

	adminInt := 0
	if isAdmin {
		adminInt = 1
	}

	result, err := db.Exec(
		"INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, ?)",
		username, string(hash), adminInt,
	)
	if err != nil {
		return nil, fmt.Errorf("insert user: %w", err)
	}

	id, _ := result.LastInsertId()
	return &User{
		ID:           id,
		Username:     username,
		PasswordHash: string(hash),
		IsAdmin:      isAdmin,
		CreatedAt:    time.Now(),
	}, nil
}

func AuthenticateUser(db *sql.DB, username, password string) (*User, error) {
	user := &User{}
	var isAdmin int
	err := db.QueryRow(
		"SELECT id, username, password_hash, is_admin, created_at FROM users WHERE username = ?",
		username,
	).Scan(&user.ID, &user.Username, &user.PasswordHash, &isAdmin, &user.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("user not found")
	}
	user.IsAdmin = isAdmin == 1

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
		return nil, fmt.Errorf("invalid password")
	}

	return user, nil
}

func GetAllUsers(db *sql.DB) ([]User, error) {
	rows, err := db.Query("SELECT id, username, password_hash, is_admin, created_at FROM users ORDER BY created_at")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []User
	for rows.Next() {
		var u User
		var isAdmin int
		if err := rows.Scan(&u.ID, &u.Username, &u.PasswordHash, &isAdmin, &u.CreatedAt); err != nil {
			return nil, err
		}
		u.IsAdmin = isAdmin == 1
		users = append(users, u)
	}
	return users, nil
}

func SetAdmin(db *sql.DB, userID int64, isAdmin bool) error {
	adminInt := 0
	if isAdmin {
		adminInt = 1
	}
	_, err := db.Exec("UPDATE users SET is_admin = ? WHERE id = ?", adminInt, userID)
	return err
}

func DeleteUser(db *sql.DB, id int64) error {
	_, err := db.Exec("DELETE FROM users WHERE id = ?", id)
	return err
}

// Session management

func CreateSession(db *sql.DB, userID int64) (string, error) {
	token := generateToken()
	_, err := db.Exec("INSERT INTO sessions (token, user_id) VALUES (?, ?)", token, userID)
	if err != nil {
		return "", err
	}
	return token, nil
}

func GetUserBySession(db *sql.DB, token string) (*User, error) {
	user := &User{}
	var isAdmin int
	err := db.QueryRow(`
		SELECT u.id, u.username, u.password_hash, u.is_admin, u.created_at
		FROM users u
		JOIN sessions s ON s.user_id = u.id
		WHERE s.token = ?
	`, token).Scan(&user.ID, &user.Username, &user.PasswordHash, &isAdmin, &user.CreatedAt)
	if err != nil {
		return nil, err
	}
	user.IsAdmin = isAdmin == 1
	return user, nil
}

func DeleteSession(db *sql.DB, token string) error {
	_, err := db.Exec("DELETE FROM sessions WHERE token = ?", token)
	return err
}

func generateToken() string {
	b := make([]byte, 32)
	rand.Read(b)
	return hex.EncodeToString(b)
}
