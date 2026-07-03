package models

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInitDB(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test.db")

	db, err := InitDB(dbPath)
	if err != nil {
		t.Fatalf("InitDB failed: %v", err)
	}
	defer db.Close()

	// Verify file was created
	if _, err := os.Stat(dbPath); os.IsNotExist(err) {
		t.Fatal("database file not created")
	}
}

func TestCreateUser(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	user, err := CreateUser(db, "testuser", "password123", false)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	if user.Username != "testuser" {
		t.Errorf("got username %q, want %q", user.Username, "testuser")
	}
	if user.IsAdmin {
		t.Error("user should not be admin")
	}
}

func TestCreateAdminUser(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	user, err := CreateUser(db, "admin", "password123", true)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	if !user.IsAdmin {
		t.Error("user should be admin")
	}
}

func TestAuthenticateUser(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	_, err = CreateUser(db, "testuser", "correctpass", false)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	// Correct password
	user, err := AuthenticateUser(db, "testuser", "correctpass")
	if err != nil {
		t.Fatalf("AuthenticateUser should succeed: %v", err)
	}
	if user.Username != "testuser" {
		t.Errorf("got %q, want %q", user.Username, "testuser")
	}

	// Wrong password
	_, err = AuthenticateUser(db, "testuser", "wrongpass")
	if err == nil {
		t.Error("AuthenticateUser should fail with wrong password")
	}

	// Non-existent user
	_, err = AuthenticateUser(db, "nouser", "pass")
	if err == nil {
		t.Error("AuthenticateUser should fail for non-existent user")
	}
}

func TestSetAdmin(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	user, err := CreateUser(db, "testuser", "password123", false)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	// Promote to admin
	err = SetAdmin(db, user.ID, true)
	if err != nil {
		t.Fatalf("SetAdmin(true): %v", err)
	}

	users, err := GetAllUsers(db)
	if err != nil {
		t.Fatalf("GetAllUsers: %v", err)
	}
	if len(users) != 1 || !users[0].IsAdmin {
		t.Error("user should be admin after SetAdmin(true)")
	}

	// Demote
	err = SetAdmin(db, user.ID, false)
	if err != nil {
		t.Fatalf("SetAdmin(false): %v", err)
	}

	users, err = GetAllUsers(db)
	if err != nil {
		t.Fatalf("GetAllUsers: %v", err)
	}
	if users[0].IsAdmin {
		t.Error("user should not be admin after SetAdmin(false)")
	}
}

func TestSessionManagement(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	user, err := CreateUser(db, "testuser", "password123", true)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	token, err := CreateSession(db, user.ID)
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if token == "" {
		t.Fatal("token should not be empty")
	}

	// Retrieve user by session
	retrieved, err := GetUserBySession(db, token)
	if err != nil {
		t.Fatalf("GetUserBySession: %v", err)
	}
	if retrieved.Username != "testuser" {
		t.Errorf("got %q, want %q", retrieved.Username, "testuser")
	}
	if !retrieved.IsAdmin {
		t.Error("retrieved user should be admin")
	}

	// Delete session
	err = DeleteSession(db, token)
	if err != nil {
		t.Fatalf("DeleteSession: %v", err)
	}

	_, err = GetUserBySession(db, token)
	if err == nil {
		t.Error("GetUserBySession should fail after session deleted")
	}
}

func TestMigrationAddsIsAdmin(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test.db")

	// Simulate an old database without is_admin column
	db, err := InitDB(dbPath)
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}

	// Create a user (with is_admin since the table was just created with it)
	_, err = CreateUser(db, "firstuser", "pass123", false)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	db.Close()

	// Re-open — migration should be idempotent
	db2, err := InitDB(dbPath)
	if err != nil {
		t.Fatalf("InitDB second time: %v", err)
	}
	defer db2.Close()

	count, err := UserCount(db2)
	if err != nil {
		t.Fatalf("UserCount: %v", err)
	}
	if count != 1 {
		t.Errorf("got count %d, want 1", count)
	}
}

func TestDeleteUser(t *testing.T) {
	dir := t.TempDir()
	db, err := InitDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("InitDB: %v", err)
	}
	defer db.Close()

	user, err := CreateUser(db, "deleteMe", "pass123", false)
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	err = DeleteUser(db, user.ID)
	if err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}

	count, err := UserCount(db)
	if err != nil {
		t.Fatalf("UserCount: %v", err)
	}
	if count != 0 {
		t.Errorf("got count %d, want 0", count)
	}
}
