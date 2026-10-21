package lodgebilling.dao;

import lodgebilling.model.User;
import lodgebilling.model.security.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL for the users table. Every query uses a PreparedStatement with ? placeholders,
 * so a username like  x' OR '1'='1  is treated as text, never as SQL (no injection).
 */
public class SqliteUserDao implements UserDao {

    private static final String COLUMNS =
            "id, username, full_name, role, active, must_change_password, password_hash, failed_attempts, locked_until, "
            + "last_login_at";

    private final Connection db;

    public SqliteUserDao(Database database) {
        this.db = database.connection();
    }

    @Override
    public int count() {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DataException("Could not count users", e);
        }
    }

    @Override
    public int countActiveAdmins() {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND active = 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DataException("Could not count admins", e);
        }
    }

    @Override
    public Optional<Credentials> findCredentials(String username) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE username = ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String locked = rs.getString("locked_until");
                return Optional.of(new Credentials(mapUser(rs), rs.getString("password_hash"),
                        rs.getInt("failed_attempts"), locked == null ? null : Instant.parse(locked)));
            }
        } catch (SQLException e) {
            throw new DataException("Could not look up user " + username, e);
        }
    }

    @Override
    public Optional<User> findById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM users WHERE id = ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataException("Could not look up user #" + id, e);
        }
    }

    @Override
    public List<User> findAll() {
        String sql = "SELECT " + COLUMNS + " FROM users ORDER BY role, username";
        List<User> users = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                users.add(mapUser(rs));
            }
            return users;
        } catch (SQLException e) {
            throw new DataException("Could not list users", e);
        }
    }

    @Override
    public boolean usernameExists(String username) {
        return findCredentials(username).isPresent();
    }

    @Override
    public User insert(String username, String fullName, Role role, String passwordHash,
                       boolean mustChangePassword, Instant now) {
        String sql = "INSERT INTO users (username, full_name, role, password_hash, must_change_password, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, role.name());
            ps.setString(4, passwordHash);
            ps.setInt(5, mustChangePassword ? 1 : 0);
            ps.setString(6, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getLong(1), username, fullName, role, true, mustChangePassword);
            }
        } catch (SQLException e) {
            throw new DataException("Could not create user " + username, e);
        }
    }

    @Override
    public void recordFailedLogin(long userId, int failedAttempts, Instant lockedUntil) {
        update("UPDATE users SET failed_attempts = ?, locked_until = ? WHERE id = ?",
                failedAttempts, lockedUntil == null ? null : lockedUntil.toString(), userId);
    }

    @Override
    public void recordSuccessfulLogin(long userId, Instant at) {
        update("UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?",
                at.toString(), userId);
    }

    @Override
    public void updatePassword(long userId, String passwordHash, boolean mustChangePassword) {
        // A new password also clears any lock-out
        update("UPDATE users SET password_hash = ?, must_change_password = ?, failed_attempts = 0, "
                + "locked_until = NULL WHERE id = ?", passwordHash, mustChangePassword ? 1 : 0, userId);
    }

    @Override
    public void setActive(long userId, boolean active) {
        update("UPDATE users SET active = ? WHERE id = ?", active ? 1 : 0, userId);
    }

    @Override
    public void setRole(long userId, Role role) {
        update("UPDATE users SET role = ? WHERE id = ?", role.name(), userId);
    }

    private void update(String sql, Object... params) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not update the users table", e);
        }
    }

    private static User mapUser(ResultSet rs) throws SQLException {
        String lastLogin = rs.getString("last_login_at");
        return new User(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                Role.valueOf(rs.getString("role")), rs.getInt("active") == 1,
                rs.getInt("must_change_password") == 1, lastLogin == null ? null : Instant.parse(lastLogin));
    }
}
