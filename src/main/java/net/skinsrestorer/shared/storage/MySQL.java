/*
 * #%L
 * SkinsRestorer
 * %%
 * Copyright (C) 2021 SkinsRestorer
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package net.skinsrestorer.shared.storage;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;
import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MySQL {
    private Connection con;
    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;
    private final String options;
    private final ExecutorService exe;

    public MySQL(String host, String port, String database, String username, String password, String options) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.options = options;
        exe = Executors.newCachedThreadPool();
    }

    public void createTable() {
        execute("CREATE TABLE IF NOT EXISTS `" + Config.MYSQL_PLAYERTABLE + "` ("
                + "`Nick` varchar(16) COLLATE utf8_unicode_ci NOT NULL,"
                + "`Skin` varchar(16) COLLATE utf8_unicode_ci NOT NULL,"
                + "PRIMARY KEY (`Nick`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci");

        execute("CREATE TABLE IF NOT EXISTS `" + Config.MYSQL_SKINTABLE + "` ("
                + "`Nick` varchar(16) COLLATE utf8_unicode_ci NOT NULL,"
                + "`Value` text COLLATE utf8_unicode_ci,"
                + "`Signature` text COLLATE utf8_unicode_ci,"
                + "`timestamp` text COLLATE utf8_unicode_ci,"
                + "PRIMARY KEY (`Nick`)) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci");

        execute("ALTER TABLE `" + Config.MYSQL_SKINTABLE + "` ADD `timestamp` text COLLATE utf8_unicode_ci;");
    }

    public Connection openConnection() throws SQLException {
        try {
            Connection con = null;
            Class.forName("com.mysql.cj.jdbc.Driver");
            con = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + options, username, password);

            System.out.println("[SkinsRestorer] Connected to MySQL!");
            this.con = con;
            return con;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Connection getConnection() {
        try {
            if (con == null || !this.con.isValid(1)) {
                System.out.println("[SkinsRestorer] MySQL connection lost! Creation a new one.");
                con = this.openConnection();
            }
        } catch (SQLException e) {
            System.out.println("[SkinsRestorer] Could NOT connect to MySQL: " + e.getMessage());
        }

        try (PreparedStatement stmt = this.con.prepareStatement("SELECT 1")) {
            stmt.execute();
        } catch (SQLException e) {
            System.out.println("[SkinsRestorer] MySQL SELECT 1 failed. Reconnecting");

            try {
                con = this.openConnection();
                return con;
            } catch (SQLException e1) {
                System.out.println("[SkinsRestorer] Couldn't reconnect to MySQL.");
                e1.printStackTrace();
            }
        }

        return con;
    }

    private PreparedStatement prepareStatement(Connection conn, String query, Object... vars) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                int i = 0;
                if (query.contains("?") && vars.length != 0)
                    for (Object obj : vars) {
                        i++;
                        ps.setObject(i, obj);
                    }
                return ps;
            }

        } catch (SQLException e) {
            System.out.println("[SkinsRestorer] MySQL error: " + e.getMessage());
        }

        return null;
    }

    public void execute(final String query, final Object... vars) {
        Connection conn = this.getConnection();

        try (PreparedStatement ps = prepareStatement(conn, query, vars)) {
            assert ps != null;
            ps.execute();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1060) {
                return;
            }
            e.printStackTrace();
            System.out.println("[SkinsRestorer] MySQL error: " + e.getMessage());
        }
    }

    public CachedRowSet query(final String query, final Object... vars) {
        Connection conn = this.getConnection();
        CachedRowSet rowSet = null;

        try {
            Future<CachedRowSet> future = exe.submit(() -> {
                try (PreparedStatement ps = prepareStatement(conn, query, vars)) {

                    assert ps != null;
                    try (ResultSet rs = ps.executeQuery()) {
                        CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
                        crs.populate(rs);
                        rs.close();
                        ps.close();

                        if (crs.next())
                            return crs;
                    }

                } catch (SQLException e) {
                    System.out.println("[SkinsRestorer] MySQL error: " + e.getMessage());
                }

                return null;
            });

            if (future.get() != null)
                rowSet = future.get();

        } catch (Exception e) {
            System.out.println("[SkinsRestorer] MySQL error: " + e.getMessage());
        }

        return rowSet;
    }

}
